package com.nezumi_ai.data.inference

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ModelFileManager {

    enum class LocalModel {
        E2B,
        E4B
    }

    private const val TAG = "ModelFileManager"
    private const val E2B_FILENAME = "gemma-3n-e2b.task"
    private const val E4B_FILENAME = "gemma-3n-e4b.task"

    private const val E2B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task"
    private const val E4B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task"

    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    private const val MIN_FREE_DISK_BYTES = 100 * 1024 * 1024 // 100MB
    private const val BUFFER_SIZE = 32 * 1024 // 32KB buffer

    data class ImportedTaskModel(
        val name: String,
        val path: String
    )

    fun resolveModelName(modelName: String): LocalModel {
        return when (modelName.lowercase()) {
            "gemma-3.2:e4b", "e4b", "gemma_e4b" -> LocalModel.E4B
            else -> LocalModel.E2B
        }
    }

    fun modelFile(context: Context, model: LocalModel): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val filename = when (model) {
            LocalModel.E2B -> E2B_FILENAME
            LocalModel.E4B -> E4B_FILENAME
        }
        return File(dir, filename)
    }

    fun listImportedTaskModels(context: Context): List<ImportedTaskModel> {
        val dir = File(context.filesDir, "models/imported")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".task") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ImportedTaskModel(name = it.nameWithoutExtension, path = it.absolutePath) }
            ?.toList()
            ?: emptyList()
    }

    fun importTaskFromUri(context: Context, uri: Uri): Result<File> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "custom_model.task"
        if (!displayName.lowercase().endsWith(".task")) {
            throw IllegalArgumentException(".task ファイルのみ追加できます")
        }
        val importedDir = File(context.filesDir, "models/imported")
        if (!importedDir.exists()) {
            importedDir.mkdirs()
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var outFile = File(importedDir, safeName)
        if (outFile.exists()) {
            val base = safeName.removeSuffix(".task")
            outFile = File(importedDir, "${base}_${System.currentTimeMillis()}.task")
        }
        context.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("ファイルを開けませんでした")
        outFile
    }

    fun isModelAvailable(context: Context, modelName: String): Boolean {
        if ((modelName.endsWith(".task") || modelName.endsWith(".litertlm")) && modelName.startsWith("/")) {
            return File(modelName).exists()
        }
        return isDownloaded(context, resolveModelName(modelName))
    }

    fun isDownloaded(context: Context, model: LocalModel): Boolean =
        modelFile(context, model).exists()

    fun previewTreeUrl(model: LocalModel): String {
        return when (model) {
            LocalModel.E2B -> "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/tree/main"
            LocalModel.E4B -> "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/tree/main"
        }
    }

    fun deleteModel(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        val tmpFile = File("${file.absolutePath}.download")
        val legacyE2B = File(file.parentFile, "gemma-3n-e2b.litertlm")
        val legacyE2BTmp = File("${legacyE2B.absolutePath}.download")
        
        // クリーンアップ一時ファイル
        cleanupTempFiles(file)
        
        val deletedMain = !file.exists() || file.delete()
        val deletedTmp = !tmpFile.exists() || tmpFile.delete()
        val deletedLegacyMain = model != LocalModel.E2B || !legacyE2B.exists() || legacyE2B.delete()
        val deletedLegacyTmp = model != LocalModel.E2B || !legacyE2BTmp.exists() || legacyE2BTmp.delete()
        return deletedMain && deletedTmp && deletedLegacyMain && deletedLegacyTmp
    }

    fun deleteTempDownload(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        val tmpFile = File("${file.absolutePath}.download")
        return !tmpFile.exists() || tmpFile.delete()
    }

    fun ensureDownloaded(
        context: Context,
        model: LocalModel,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        val file = modelFile(context, model)
        if (file.exists() && file.length() > 0) {
            Log.d(TAG, "Model file already exists: ${file.absolutePath}")
            return Result.success(file)
        }

        val url = when (model) {
            LocalModel.E2B -> E2B_HF_URL
            LocalModel.E4B -> E4B_HF_URL
        }

        return try {
            // ディスク容量チェック
            val requiredSpace = getContentLength(url, HfAuthManager.getToken(context))
            if (!hasEnoughSpace(file, requiredSpace)) {
                return Result.failure(
                    IllegalStateException(
                        "ディスク容量不足です。${requiredSpace / (1024 * 1024)}MB以上の空き容量が必要です"
                    )
                )
            }

            downloadFileWithRetry(context, url, file, onProgress)
            
            // ファイル検証
            if (!file.exists() || file.length() == 0L) {
                return Result.failure(IllegalStateException("ダウンロードファイルが空です"))
            }
            
            Log.d(TAG, "Model download completed: ${file.absolutePath} (${file.length()} bytes)")
            Result.success(file)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }

    private fun downloadFileWithRetry(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        var lastException: Exception? = null
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Download attempt ${attempt + 1}/$MAX_RETRIES: $urlString")
                downloadFile(context, urlString, outFile, onProgress)
                return // 成功時はリターン
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                
                if (attempt < MAX_RETRIES - 1) {
                    // リトライ待機
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }
        }
        
        throw lastException ?: Exception("Download failed after $MAX_RETRIES attempts")
    }

    private fun downloadFile(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val tmpFile = File("${outFile.absolutePath}.download")
        tmpFile.parentFile?.mkdirs()

        var resumeFrom = if (tmpFile.exists()) tmpFile.length().coerceAtLeast(0L) else 0L
        val token = HfAuthManager.getToken(context)

        fun openDownloadConnection(rangeStart: Long): HttpURLConnection {
            return (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "nezumi-ai/1.0")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
        }

        var connection = openDownloadConnection(resumeFrom)
        try {
            connection.connect()
            val code = connection.responseCode

            if (resumeFrom > 0L && code == HttpURLConnection.HTTP_OK) {
                // サーバーがRangeを受け付けない場合は先頭から取り直す
                connection.disconnect()
                tmpFile.delete()
                resumeFrom = 0L
                connection = openDownloadConnection(0L)
                connection.connect()
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                connection.disconnect()
                val message = when (responseCode) {
                    401 -> "Download failed (HTTP 401). Hugging Faceのライセンス同意後、HF token (hf_xxx) を設定してください。"
                    403 -> "Download failed (HTTP 403). gemma規約を見て承認してください。"
                    else -> "Download failed (HTTP $responseCode)."
                }
                throw IllegalStateException(message)
            }

            val totalBytes = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val contentRange = connection.getHeaderField("Content-Range")
                val rangeTotal = contentRange
                    ?.substringAfterLast('/')
                    ?.toLongOrNull()
                rangeTotal ?: (resumeFrom + connection.contentLengthLong).takeIf { it > 0L } ?: -1L
            } else {
                connection.contentLengthLong
            }

            if (totalBytes <= 0L) {
                throw IllegalStateException("Cannot determine file size")
            }

            if (resumeFrom >= totalBytes) {
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    throw IllegalStateException("Failed to save model file: ${outFile.absolutePath}")
                }
                onProgress?.invoke(totalBytes, totalBytes)
                return
            }

            onProgress?.invoke(resumeFrom, totalBytes)
            Log.d(TAG, "Downloading: $resumeFrom / $totalBytes bytes")

            connection.inputStream.use { input ->
                val append = responseCode == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0L
                FileOutputStream(tmpFile, append).buffered(BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var read: Int
                    var downloaded = resumeFrom

                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress?.invoke(downloaded, totalBytes)

                        // タイムアウト検出
                        if (downloaded > totalBytes * 1.1) {
                            throw IllegalStateException("Downloaded more than expected")
                        }
                    }
                    output.flush()
                }
            }

            // ファイル整合性確認
            if (tmpFile.length() != totalBytes) {
                throw IllegalStateException(
                    "File size mismatch: ${tmpFile.length()} vs $totalBytes"
                )
            }

            // ファイルを確定
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                throw IllegalStateException("Failed to save model file: ${outFile.absolutePath}")
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun getContentLength(urlString: String, token: String): Long {
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "HEAD"
                setRequestProperty("User-Agent", "nezumi-ai/1.0")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            connection.connect()
            val length = connection.contentLengthLong
            connection.disconnect()
            if (length > 0) length else 500 * 1024 * 1024 // デフォルト500MB
        } catch (e: Exception) {
            Log.w(TAG, "Cannot determine content length", e)
            500 * 1024 * 1024 // デフォルト推定500MB
        }
    }

    private fun hasEnoughSpace(file: File, requiredBytes: Long): Boolean {
        val stat = StatFs(file.parentFile?.absolutePath ?: return false)
        val availableBytes = stat.availableBytes
        val required = requiredBytes + MIN_FREE_DISK_BYTES
        return availableBytes >= required
    }

    private fun cleanupTempFiles(baseFile: File) {
        try {
            val tmpFile = File("${baseFile.absolutePath}.download")
            if (tmpFile.exists()) {
                tmpFile.delete()
                Log.d(TAG, "Cleaned up temp file: ${tmpFile.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup temp files", e)
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }
}
