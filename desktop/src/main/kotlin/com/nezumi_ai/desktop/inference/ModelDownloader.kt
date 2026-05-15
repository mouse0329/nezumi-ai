package com.nezumi_ai.desktop.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * GGUF モデルの自動ダウンローダー。
 *
 * Hugging Face は匿名でも多くのリポジトリにアクセスできますが、
 * ゲート済みモデル（例: `google/gemma-*-GGUF`）は [HF アクセストークン](https://huggingface.co/settings/tokens) が必要です。
 * 環境変数 `HF_TOKEN` または `HUGGINGFACE_HUB_TOKEN` に `hf_...` を設定してください。
 */
object ModelDownloader {

    data class ModelInfo(
        val name: String,
        val displayName: String,
        val url: String,
        val size: String,
        val description: String,
    )

    /**
     * 匿名ダウンロード可能なものを優先（[unsloth/gemma-4-E2B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/tree/main) など）。
     * 画像入力を使う場合は別途 `mmproj-*.gguf` が必要になることがあります。
     */
    val RECOMMENDED_MODELS = listOf(
        ModelInfo(
            name = "gemma-4-E2B-it-Q4_K_M.gguf",
            displayName = "Gemma 4 E2B IT (Q4_K_M)",
            url = hfResolve("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q4_K_M.gguf"),
            size = "約 3.1 GB",
            description = "Unsloth 配布の Gemma 4（テキストチャット向け。画像は mmproj 別途）",
        ),
        ModelInfo(
            name = "gemma-4-E2B-it-Q3_K_M.gguf",
            displayName = "Gemma 4 E2B IT (Q3_K_M)",
            url = hfResolve("unsloth/gemma-4-E2B-it-GGUF", "gemma-4-E2B-it-Q3_K_M.gguf"),
            size = "約 2.5 GB",
            description = "やや軽量な Gemma 4 量子化",
        ),
        ModelInfo(
            name = "qwen2.5-3b-instruct-q4_k_m.gguf",
            displayName = "Qwen 2.5 3B Instruct (Q4_K_M)",
            url = hfResolve("Qwen/Qwen2.5-3B-Instruct-GGUF", "qwen2.5-3b-instruct-q4_k_m.gguf"),
            size = "約 2.0 GB",
            description = "バランス型の指示追従モデル",
        ),
        ModelInfo(
            name = "gemma-2b-it-q4_k_m.gguf",
            displayName = "Gemma 2 2B IT (公式・要トークン)",
            url = hfResolve("google/gemma-2b-it-GGUF", "gemma-2b-it-q4_k_m.gguf"),
            size = "約 1.7 GB",
            description = "Hugging Face でライセンス同意後、`HF_TOKEN` 環境変数が必要な場合があります",
        ),
    )

    private fun hfResolve(repo: String, file: String): String =
        "https://huggingface.co/$repo/resolve/main/$file"

    private fun hfAuthHeaders(): List<Pair<String, String>> {
        val token = System.getenv("HF_TOKEN")
            ?: System.getenv("HUGGINGFACE_HUB_TOKEN")
            ?: return emptyList()
        if (token.isBlank()) return emptyList()
        return listOf("Authorization" to "Bearer $token")
    }

    private fun openDownloadConnection(url: String): HttpURLConnection {
        val withQuery = if ("?" in url) url else "$url?download=1"
        val conn = URL(withQuery).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "nezumi-ai-desktop/1.0")
        conn.setRequestProperty("Accept", "*/*")
        hfAuthHeaders().forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.connectTimeout = 30_000
        conn.readTimeout = 0 // 大容量用（必要なら上限を別途）
        return conn
    }

    private fun getModelsDir(): File {
        val userHome = System.getProperty("user.home")
        val modelsDir = File(userHome, ".nezumi-ai/models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        return modelsDir
    }

    fun getModelPath(modelName: String): String {
        return File(getModelsDir(), modelName).absolutePath
    }

    fun isModelDownloaded(modelName: String): Boolean {
        return File(getModelsDir(), modelName).exists()
    }

    suspend fun downloadModel(
        modelInfo: ModelInfo,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val modelsDir = getModelsDir()
            val modelFile = File(modelsDir, modelInfo.name)

            if (modelFile.exists()) {
                println("Model already exists: ${modelFile.absolutePath}")
                return@withContext Result.success(modelFile.absolutePath)
            }

            println("Downloading model: ${modelInfo.displayName}")
            println("URL: ${modelInfo.url}")
            println("Size: ${modelInfo.size}")
            if (hfAuthHeaders().isEmpty()) {
                println("(ヒント) ゲート済みモデルは HF_TOKEN または HUGGINGFACE_HUB_TOKEN を設定してください。")
            }

            val connection = openDownloadConnection(modelInfo.url)
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                val hint = when (responseCode) {
                    401, 403 -> " ライセンス同意とアクセストークン（HF_TOKEN）を確認してください。"
                    else -> ""
                }
                throw IOException("HTTP $responseCode for ${modelInfo.url}$hint ${errBody.take(300)}")
            }

            val totalSize = connection.contentLengthLong
            var downloadedSize = 0L
            var nextLogAtBytes = 10L * 1024 * 1024

            connection.inputStream.use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192 * 4)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedSize += bytesRead.toLong()
                        onProgress(downloadedSize, totalSize)

                        if (downloadedSize >= nextLogAtBytes) {
                            nextLogAtBytes += 10L * 1024 * 1024
                            val mbNow = downloadedSize / (1024 * 1024)
                            if (totalSize > 0) {
                                val percent = ((downloadedSize * 100) / totalSize).toInt().coerceAtMost(100)
                                println("Progress: $percent% (${mbNow} MB / ${totalSize / 1024 / 1024} MB)")
                            } else {
                                println("Progress: ${mbNow} MB (総サイズ不明)")
                            }
                        }
                    }
                }
            }
            connection.disconnect()

            println("Download complete: ${modelFile.absolutePath}")
            Result.success(modelFile.absolutePath)
        } catch (e: Exception) {
            println("Failed to download model: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    fun listDownloadedModels(): List<File> {
        val modelsDir = getModelsDir()
        return modelsDir.listFiles { file ->
            file.extension.lowercase() == "gguf"
        }?.toList() ?: emptyList()
    }
}
