package com.nezumi_ai.data.inference

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ModelFileManager {

    enum class LocalModel {
        E2B,
        E4B
    }

    private const val E2B_FILENAME = "gemma-3n-e2b.task"
    private const val E4B_FILENAME = "gemma-3n-e4b.task"

    private const val E2B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task"
    private const val E4B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task"

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
        val deletedMain = !file.exists() || file.delete()
        val deletedTmp = !tmpFile.exists() || tmpFile.delete()
        val deletedLegacyMain = model != LocalModel.E2B || !legacyE2B.exists() || legacyE2B.delete()
        val deletedLegacyTmp = model != LocalModel.E2B || !legacyE2BTmp.exists() || legacyE2BTmp.delete()
        return deletedMain && deletedTmp && deletedLegacyMain && deletedLegacyTmp
    }

    fun ensureDownloaded(
        context: Context,
        model: LocalModel,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> {
        val file = modelFile(context, model)
        if (file.exists()) return Result.success(file)

        val url = when (model) {
            LocalModel.E2B -> E2B_HF_URL
            LocalModel.E4B -> E4B_HF_URL
        }

        return runCatching {
            downloadFile(context, url, file, onProgress)
            file
        }
    }

    private fun downloadFile(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        val tmpFile = File("${outFile.absolutePath}.download")
        if (tmpFile.exists()) tmpFile.delete()
        val token = HfAuthManager.getToken(context)

        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "nezumi-ai/1.0")
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            connection.disconnect()
            val message = when (code) {
                401 -> "Download failed (HTTP 401). Hugging Faceのライセンス同意後、HF token (hf_xxx) を設定してください。"
                403 -> "Download failed (HTTP 403). gemma規約を見て承認してください。"
                else -> "Download failed (HTTP $code)."
            }
            throw IllegalStateException(message)
        }

        val totalBytes = connection.contentLengthLong
        onProgress?.invoke(0L, totalBytes)

        connection.inputStream.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var downloaded = 0L
                while (input.read(buffer).also { read = it } >= 0) {
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, totalBytes)
                }
            }
        }
        connection.disconnect()

        if (outFile.exists()) outFile.delete()
        if (!tmpFile.renameTo(outFile)) {
            throw IllegalStateException("Failed to save model file: ${outFile.absolutePath}")
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
