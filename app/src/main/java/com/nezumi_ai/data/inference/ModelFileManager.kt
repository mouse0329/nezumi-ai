package com.nezumi_ai.data.inference

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

object ModelFileManager {

    enum class LocalModel {
        GEMMA3N_2B,
        GEMMA3N_4B,
        GEMMA4_2B,
        GEMMA4_4B
    }

    private const val TAG = "ModelFileManager"
    private const val GEMMA3N_2B_FILENAME = "gemma-3n-2b.task"
    private const val GEMMA3N_4B_FILENAME = "gemma-3n-4b.task"
    private const val GEMMA4_2B_FILENAME = "gemma-4-2b.litertlm"
    private const val GEMMA4_4B_FILENAME = "gemma-4-4b.litertlm"
    
    private const val E2B_LEGACY_LITERTLM_FILENAME = "gemma-3n-e2b.litertlm"
    private const val E4B_LEGACY_LITERTLM_FILENAME = "gemma-3n-e4b.litertlm"

    // Gemma 3n モデル URL（Google 公式）
    private const val GEMMA3N_2B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task"
    private const val GEMMA3N_4B_HF_URL =
        "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview/resolve/main/gemma-3n-E4B-it-int4.task"
    
    // Gemma 4 モデル URL（litert-community 最適化版）
    private const val GEMMA4_2B_HF_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
    private const val GEMMA4_4B_HF_URL =
        "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true"

    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 2000L
    private const val MIN_FREE_DISK_BYTES = 100 * 1024 * 1024 // 100MB
    private const val MIN_VALID_MODEL_BYTES = 50L * 1024L * 1024L // 50MB
    private const val BUFFER_SIZE = 32 * 1024 // 32KB buffer
    private const val WARN_SMALL_IMPORTED_TASK_BYTES = 64L * 1024L

    private data class RemoteMetadata(
        val contentLength: Long,
        val sha256: String?
    )

    data class ImportedTaskModel(
        val name: String,
        val path: String
    )

    private data class ModelMetadata(
        val expectedBytes: Long,
        val expectedSha256: String?
    )

    fun resolveModelName(modelName: String): LocalModel {
        return when (modelName.lowercase()) {
            "gemma3n-4b", "gemma-3n-4b", "gemma3n_4b", "3n:4b" -> LocalModel.GEMMA3N_4B
            "gemma3n-2b", "gemma-3n-2b", "gemma3n_2b", "3n:2b" -> LocalModel.GEMMA3N_2B
            "gemma4-2b", "gemma-4-2b", "gemma4_2b", "4:2b" -> LocalModel.GEMMA4_2B
            "gemma4-4b", "gemma-4-4b", "gemma4_4b", "4:4b" -> LocalModel.GEMMA4_4B
            else -> LocalModel.GEMMA4_2B  // default
        }
    }

    fun modelFile(context: Context, model: LocalModel): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val filename = when (model) {
            LocalModel.GEMMA3N_2B -> GEMMA3N_2B_FILENAME
            LocalModel.GEMMA3N_4B -> GEMMA3N_4B_FILENAME
            LocalModel.GEMMA4_2B -> GEMMA4_2B_FILENAME
            LocalModel.GEMMA4_4B -> GEMMA4_4B_FILENAME
        }
        return File(dir, filename)
    }

    /**
     * `models/imported` に残っている `.litertlm` を `.task` にリネームする。
     * Room の selectedModel など保存パスを更新するため、(旧パス, 新パス) を返す。
     */
    fun migrateImportedLegacyLiteRtLmFiles(context: Context): List<Pair<String, String>> {
        val importedDir = File(context.filesDir, "models/imported")
        if (!importedDir.isDirectory) return emptyList()
        val legacyFiles = importedDir.listFiles()
            ?.filter { it.isFile && it.name.lowercase().endsWith(".litertlm") }
            ?: return emptyList()
        if (legacyFiles.isEmpty()) return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        for (legacy in legacyFiles.sortedBy { it.name }) {
            if (validateImportedTaskFile(legacy).isFailure) {
                Log.w(TAG, "Skip imported litertlm migrate (invalid): ${legacy.name}")
                continue
            }
            val base = legacy.nameWithoutExtension
            var dest = File(importedDir, "$base.task")
            if (dest.exists()) {
                dest = File(importedDir, "${base}_${System.currentTimeMillis()}.task")
            }
            val oldPath = legacy.absolutePath
            val legacyMeta = metadataFile(legacy)
            val destMeta = metadataFile(dest)
            if (!legacy.renameTo(dest)) {
                Log.w(TAG, "Failed to rename imported litertlm: ${legacy.name}")
                continue
            }
            if (legacyMeta.exists()) {
                if (destMeta.exists()) destMeta.delete()
                if (!legacyMeta.renameTo(destMeta)) {
                    runCatching {
                        legacyMeta.copyTo(destMeta, overwrite = true)
                        legacyMeta.delete()
                    }.onFailure { Log.w(TAG, "Failed to copy legacy meta for imported model", it) }
                }
            }
            results.add(oldPath to dest.absolutePath)
        }
        return results
    }

    fun listImportedTaskModels(context: Context): List<ImportedTaskModel> {
        val dir = File(context.filesDir, "models/imported")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && (it.name.lowercase().endsWith(".task") || it.name.lowercase().endsWith(".litertlm")) }
            ?.filter { validateImportedTaskFile(it).isSuccess }
            ?.sortedByDescending { it.lastModified() }
            ?.map { ImportedTaskModel(name = it.nameWithoutExtension, path = it.absolutePath) }
            ?.toList()
            ?: emptyList()
    }

    fun importTaskFromUri(context: Context, uri: Uri): Result<File> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "custom_model.task"
        val lower = displayName.lowercase()
        if (!lower.endsWith(".task") && !lower.endsWith(".litertlm")) {
            throw IllegalArgumentException(".task または .litertlm ファイルのみ追加できます")
        }
        val importedDir = File(context.filesDir, "models/imported")
        if (!importedDir.exists()) {
            importedDir.mkdirs()
        }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var outFile = File(importedDir, safeName)
        if (outFile.exists()) {
            // .task と .litertlm の両方を正しく処理
            val base = if (lower.endsWith(".task")) {
                safeName.removeSuffix(".task")
            } else {
                safeName.removeSuffix(".litertlm")
            }
            outFile = File(importedDir, "${base}_${System.currentTimeMillis()}.task")
        }
        
        // ファイルコピー（バッファサイズを大きくして効率化＆同期化を確実に）
        var copiedBytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } > 0) {
                    output.write(buffer, 0, bytesRead)
                    copiedBytes += bytesRead
                }
                // バッファをフラッシュ
                output.flush()
                // ファイルディスクリプタを同期化（OSレベルで実際にディスクに書き込まれるまで待機）
                output.channel?.force(true)
            }
        } ?: throw IllegalStateException("ファイルを開けませんでした")
        
        // コピー後、ファイルシステムのキャッシュが確実に反映されるまで待機
        // (まれにディレイが必要なファイルシステムがある)
        if (!outFile.exists() || outFile.length() == 0L) {
            throw IllegalStateException("ファイルコピーが失敗した可能性があります：${outFile.absolutePath}")
        }
        
        Log.d(TAG, "Imported task file: ${outFile.absolutePath} ($copiedBytes bytes)")
        
        // インポート直後に厳密なバリデーション
        validateImportedTaskFile(outFile).getOrElse { reason ->
            outFile.delete()
            throw IllegalArgumentException("この .task は読み込みできません: ${reason.message}")
        }
        
        outFile
    }

    fun deleteImportedTask(context: Context, path: String): Result<Unit> = runCatching {
val importedDir = File(context.filesDir, "models/imported").canonicalFile
        val target = File(path).canonicalFile
        val importedPrefix = importedDir.path + File.separator

        // パス・トラバーサル対策（指定ディレクトリ外の操作を禁止）
        if (!target.path.startsWith(importedPrefix)) {
            throw IllegalArgumentException("削除対象のパスが不正です")
        }

        // ファイルが存在しない場合は何もしない（冪等性の確保）
        if (!target.exists()) return@runCatching

        // ファイル形式の厳密なチェック
        val lower = target.name.lowercase()
        if (!target.isFile || (!lower.endsWith(".task") && !lower.endsWith(".litertlm"))) {
            throw IllegalArgumentException(".task または .litertlm ファイルのみ削除できます")
        }

        // 削除実行
        if (!target.delete()) {
            throw IllegalStateException("ファイルの削除に失敗しました")
        }
    }

    fun isModelAvailable(context: Context, modelName: String): Boolean {
        val lowered = modelName.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && modelName.startsWith("/")) {
            return validateImportedTaskFile(File(modelName)).isSuccess
        }
        return isDownloaded(context, resolveModelName(modelName))
    }

/**
     * インポートされた .task/.litertlm ファイルの基本的なバリデーション
     * 
     * MediaPipe LiteRT の .task ファイルはバイナリフォーマット（ZIP ではない）
     * したがって、ファイルサイズとアクセス権限のみ検証する
     */
    fun validateImportedTaskFile(file: File): Result<File> = runCatching {
        if (!file.exists() || !file.isFile) {
            throw IllegalStateException("ファイルが見つかりません")
        }
        if (!file.canRead()) {
            throw IllegalStateException("ファイルを読み取れません")
        }
        val size = file.length()
        if (size <= 0L) {
            throw IllegalStateException("ファイルが空です")
        }
        // MediaPipe LiteRT モデルの最小サイズをチェック（通常は 100MB 以上）
        if (size < WARN_SMALL_IMPORTED_TASK_BYTES) {
            Log.w(TAG, "Imported task is very small: ${file.absolutePath} (${size} bytes)")
        }
        
        val lower = file.name.lowercase()
        
        // .task / .litertlm は両方ともバイナリフォーマット
        // ZIP ファイルかどうかの判定で誤った検証を避ける
        if (lower.endsWith(".task") || lower.endsWith(".litertlm")) {
            // バイナリフォーマットのモデルファイルは、ファイル先頭のマジックナンバーでチェック
            try {
                val header = ByteArray(4)
                file.inputStream().use { stream ->
                    stream.read(header)
                }
                
                // ZIP の マジックナンバーは 0x50 0x4B 0x03 0x04（"PK..") 
                val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                
                // もし ZIP マジックナンバーが見つかった場合は警告（誤った形式の可能性）
                if (isZip) {
                    Log.w(TAG, "Warning: .task file detected as ZIP format. This may not be a valid MediaPipe LiteRT model.")
                }
                
                Log.d(TAG, "Binary model file validated: ${file.absolutePath} (${size} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error reading model file header: ${e.message}", e)
                // ヘッダ読み込みエラーでもファイルは受け入れる（実運用時のエラーで詳細が分かる）
            }
        }
        
        file
    }

    /**
     * ZIPアーカイブの整合性を詳細にチェック
     * 破損している場合は詳細エラーログを出力
     */
    private fun validateZipIntegrity(file: File) {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var entryCount = 0
                var hasWasmFile = false
                var hasWebDirectory = false
                
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    entryCount++
                    
                    // Web用モデルの検出
                    val entryName = entry.name.lowercase()
                    if (entryName.endsWith(".wasm")) {
                        hasWasmFile = true
                        Log.w(TAG, "Web format model detected (WASM): ${entry.name}")
                    }
                    if (entryName.startsWith("web/") || entryName.contains("/web/")) {
                        hasWebDirectory = true
                        Log.w(TAG, "Web format model detected (directory): ${entry.name}")
                    }
                    
                    // エントリを実際に読み込み可能か確認
                    try {
                        zip.getInputStream(entry).use { stream ->
                            val buffer = ByteArray(8192)
                            while (stream.read(buffer) > 0) {
                                // ストリーム読み込みテスト
                            }
                        }
                    } catch (e: Exception) {
                        throw IOException("ZIPエントリ読み込み失敗: ${entry.name}, ${e.message}", e)
                    }
                }
                
                // Web用モデルの場合は即座に拒否
                if (hasWasmFile || hasWebDirectory) {
                    throw IllegalStateException("Web用モデルはAndroidアプリで使用できません。本体デバイス用の.taskファイルを使用してください。")
                }
                
                if (entryCount == 0) {
                    throw IllegalStateException("ZIPアーカイブが空です")
                }
                Log.d(TAG, "ZIP archive validation passed: ${file.absolutePath} ($entryCount entries)")
            }
        } catch (zipException: Exception) {
            // 詳細なエラーログを出力
            Log.e(TAG, "ZIPファイル読み込みエラー: ${file.absolutePath}", zipException)
            Log.e(TAG, "エラー詳細: ${zipException.message}")
            
            // 破損ファイルの自動削除を検討
            if (shouldDeleteCorruptedFile(zipException)) {
                Log.i(TAG, "破損ファイルを削除します: ${file.absolutePath}")
                try {
                    file.delete()
                    Log.i(TAG, "破損ファイル削除完了")
                } catch (delException: Exception) {
                    Log.w(TAG, "破損ファイルの削除に失敗", delException)
                }
            }
            
            throw IllegalStateException("ZIPファイルが破損しています: ${zipException.message}", zipException)
        }
    }
    
    /**
     * ファイルが破損しているか判定（削除すべきか）
     */
    private fun shouldDeleteCorruptedFile(exception: Exception): Boolean {
        val message = exception.message ?: return false
        return message.contains("invalid LFH signature", ignoreCase = true) ||
                message.contains("invalid data") ||
                message.contains("corrupt", ignoreCase = true) ||
                message.contains("truncated", ignoreCase = true) ||
                message.contains("unexpected end", ignoreCase = true)
    }
    
    /**
     * モデルファイルをクリアして再ダウンロード可能な状態にする
     */
    fun clearCorruptedModel(context: Context, model: LocalModel): Result<Unit> = runCatching {
        val file = modelFile(context, model)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "モデルファイルをクリアしました: ${file.absolutePath}")
        }
        val metadataFile = File(file.parentFile, file.name + ".metadata")
        if (metadataFile.exists()) {
            metadataFile.delete()
            Log.i(TAG, "メタデータをクリアしました: ${metadataFile.absolutePath}")
        }
    }

    /**
     * ダウンロード済みかどうかの確認
     */
    fun isDownloaded(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        // 以前のシンプルな exists() チェックを、より信頼性の高い isValidDownloadedFile に委譲
        return isValidDownloadedFile(file)
    }

    /**
     * モデル読み込み前の最終検証 (SHA-256 / サイズチェック)
     * 破損している場合は自動的に削除する
     */
    fun validatedModelFileForLoad(context: Context, model: LocalModel): Result<File> {
        val file = modelFile(context, model)
        val metadata = readMetadata(file)
            ?: return Result.failure(IllegalStateException("モデルの整合性メタデータがありません"))

        if (!file.exists() || file.length() <= 0L) {
            return Result.failure(IllegalStateException("モデルファイルが存在しません"))
        }

        // ファイルサイズ検証
        if (file.length() != metadata.expectedBytes) {
            deleteModel(context, model)
            return Result.failure(IllegalStateException("モデルサイズが不正です。再ダウンロードしてください"))
        }

        // ハッシュ値検証 (これが破損対策の要)
        val expectedSha = metadata.expectedSha256
        if (expectedSha.isNullOrBlank()) {
            deleteModel(context, model)
            return Result.failure(IllegalStateException("モデル整合性情報が不足しています。再ダウンロードしてください"))
        }

        val actualSha = runCatching { sha256Blocking(file) }.getOrElse {
            return Result.failure(IllegalStateException("モデル検証に失敗しました: ${it.message}"))
        }
        
        if (!actualSha.equals(expectedSha, ignoreCase = true)) {
            deleteModel(context, model)
            return Result.failure(IllegalStateException("モデルが破損しています。再ダウンロードしてください"))
        }

        return Result.success(file)
    }

    fun previewTreeUrl(model: LocalModel): String {
        return when (model) {
            LocalModel.GEMMA3N_2B -> "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview"
            LocalModel.GEMMA3N_4B -> "https://huggingface.co/google/gemma-3n-E4B-it-litert-preview"
            LocalModel.GEMMA4_2B -> "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/main"
            LocalModel.GEMMA4_4B -> "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/main"
        }
    }

    fun deleteModel(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        return withModelTryLock(context, model) {
            val tmpFile = File("${file.absolutePath}.download")
            val metaFile = metadataFile(file)
            val legacyLiteRtLm = File(file.parentFile, legacyLiteRtLmFilename(model))
            val legacyLiteRtLmTmp = File("${legacyLiteRtLm.absolutePath}.download")
            val legacyLiteRtLmMeta = File("${legacyLiteRtLm.absolutePath}.meta")

            cleanupTempFiles(file)

            val deletedMain = !file.exists() || file.delete()
            val deletedTmp = !tmpFile.exists() || tmpFile.delete()
            val deletedMeta = !metaFile.exists() || metaFile.delete()
            val deletedLegacyMain = !legacyLiteRtLm.exists() || legacyLiteRtLm.delete()
            val deletedLegacyTmp = !legacyLiteRtLmTmp.exists() || legacyLiteRtLmTmp.delete()
            val deletedLegacyMeta = !legacyLiteRtLmMeta.exists() || legacyLiteRtLmMeta.delete()
            deletedMain && deletedTmp && deletedMeta && deletedLegacyMain && deletedLegacyTmp && deletedLegacyMeta
        } ?: false
    }

    fun deleteTempDownload(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        val tmpFile = File("${file.absolutePath}.download")
        // UIスレッドから呼ばれることがあるので、ロックが取れない場合は無理に待たない
        val result = withModelTryLock(context, model) {
            !tmpFile.exists() || tmpFile.delete()
        }
        return result ?: (!tmpFile.exists() || tmpFile.delete())
    }

    suspend fun ensureDownloaded(
        context: Context,
        model: LocalModel,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val file = modelFile(context, model)
        withModelLock(context, model) {
            coroutineContext.ensureActive()
            migrateLegacyLiteRtLmToTaskIfNeeded(file, model)
            cleanupLegacyLiteRtLmFiles(file, model)
            // 1) 不完全ファイル（小さすぎる等）は削除
            if (file.exists() && file.length() < MIN_VALID_MODEL_BYTES) {
                Log.w(TAG, "Model file is too small, removing: ${file.length()} bytes")
                file.delete()
                metadataFile(file).delete()
            }

            // 2) 有効なファイルが存在するなら即返す（サイズ+SHAメタデータあり）
            if (isValidDownloadedFile(file)) {
                Log.d(TAG, "Valid model file already exists: ${file.absolutePath}")
                return@withModelLock Result.success(file)
            }

            val url = when (model) {
                LocalModel.GEMMA3N_2B -> GEMMA3N_2B_HF_URL
                LocalModel.GEMMA3N_4B -> GEMMA3N_4B_HF_URL
                LocalModel.GEMMA4_2B -> GEMMA4_2B_HF_URL
                LocalModel.GEMMA4_4B -> GEMMA4_4B_HF_URL
            }

            try {
                val remoteMetadata = getRemoteMetadata(url, HfAuthManager.getToken(context))
                if (recoverExistingDownloadedFile(
                        file = file,
                        remoteContentLength = remoteMetadata.contentLength,
                        expectedSha256 = remoteMetadata.sha256
                    )
                ) {
                    Log.d(TAG, "Recovered existing model file without re-download: ${file.absolutePath}")
                    return@withModelLock Result.success(file)
                }

                // 3) 復旧できない既存ファイルは削除
                if (file.exists()) {
                    Log.w(TAG, "Invalid model file detected. Redownloading: ${file.absolutePath}")
                    file.delete()
                    metadataFile(file).delete()
                }

                val requiredSpace = remoteMetadata.contentLength
                if (!hasEnoughSpace(file, requiredSpace)) {
                    return@withModelLock Result.failure(
                        IllegalStateException(
                            "ディスク容量不足です。${requiredSpace / (1024 * 1024)}MB以上の空き容量が必要です"
                        )
                    )
                }

                downloadFileWithRetry(context, url, file, onProgress)

                // 最低限のサイズ・メタデータ整合性チェック（SHAはメタデータ側で必須化）
                if (!isValidDownloadedFile(file)) {
                    return@withModelLock Result.failure(IllegalStateException("ダウンロードファイルの整合性検証に失敗しました"))
                }

                Log.d(TAG, "Model download completed: ${file.absolutePath} (${file.length()} bytes)")
                Result.success(file)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                Result.failure(e)
            }
        }
    }

    private fun recoverExistingDownloadedFile(
        file: File,
        remoteContentLength: Long,
        expectedSha256: String?
    ): Boolean {
        if (!file.exists() || !file.isFile) return false
        val length = file.length()
        if (length < MIN_VALID_MODEL_BYTES) return false

        // HEAD失敗時はcontentLengthが推定値の可能性があるため、SHA取得時のみサイズ厳密化
        if (!expectedSha256.isNullOrBlank() && remoteContentLength > 0L && length != remoteContentLength) {
            return false
        }

        val actualSha = runCatching { sha256Blocking(file) }.getOrNull() ?: return false
        if (!expectedSha256.isNullOrBlank() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
            Log.w(TAG, "Remote hash differs from local file hash. Trusting local measured hash for metadata recovery.")
        }

        // 検証時は「実ファイルから計算したSHA」を正として扱う
        writeMetadata(file, length, actualSha)
        return true
    }

    private suspend fun downloadFileWithRetry(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ) {
        var lastException: Exception? = null
        var restartFromZeroCount = 0
        var reachedFullDownloadOnce = false
        var reachedNearCompletionOnce = false
        
        repeat(MAX_RETRIES) { attempt ->
            try {
                Log.d(TAG, "Download attempt ${attempt + 1}/$MAX_RETRIES: $urlString")
                val restarted = downloadFile(
                    context = context,
                    urlString = urlString,
                    outFile = outFile,
                    onProgress = { downloaded, total ->
                        if (total > 0L && downloaded >= (total * 98L / 100L)) {
                            reachedNearCompletionOnce = true
                        }
                        if (total > 0L && downloaded >= total) {
                            reachedFullDownloadOnce = true
                        }
                        onProgress?.invoke(downloaded, total)
                    }
                )
                if (restarted) restartFromZeroCount++
                if (restartFromZeroCount >= 2) {
                    throw IllegalStateException(
                        "ダウンロード再開に失敗したため中断しました（0からの再取得が繰り返されています）。" +
                            "ネットワークやサーバー応答を確認してください。"
                    )
                }
                return // 成功時はリターン
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")

                // 100% 到達後の失敗は再取得ループに見えるため、同一ジョブ内での再ダウンロードを止める
                if (reachedFullDownloadOnce || reachedNearCompletionOnce) {
                    throw e
                }
                
                if (attempt < MAX_RETRIES - 1) {
                    // リトライ待機（キャンセル可能）
                    delay(RETRY_DELAY_MS)
                }
            }
        }
        
        throw lastException ?: Exception("Download failed after $MAX_RETRIES attempts")
    }

    /**
     * @return true if it had to restart from zero due to Range not supported.
     */
    private suspend fun downloadFile(
        context: Context,
        urlString: String,
        outFile: File,
        onProgress: ((downloadedBytes: Long, totalBytes: Long) -> Unit)?
    ): Boolean {
        val tmpFile = File("${outFile.absolutePath}.download")
        tmpFile.parentFile?.mkdirs()

        var resumeFrom = if (tmpFile.exists()) tmpFile.length().coerceAtLeast(0L) else 0L
        val token = HfAuthManager.getToken(context)
        var restartedFromZero = false

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
            coroutineContext.ensureActive()
            connection.connect()
            val code = connection.responseCode

            if (resumeFrom > 0L && code == HttpURLConnection.HTTP_OK) {
                // サーバーがRangeを受け付けない場合は先頭から取り直す
                connection.disconnect()
                tmpFile.delete()
                resumeFrom = 0L
                restartedFromZero = true
                connection = openDownloadConnection(0L)
                coroutineContext.ensureActive()
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

            val contentType = connection.contentType.orEmpty().lowercase()
            if (contentType.contains("text/html") || contentType.contains("application/json")) {
                throw IllegalStateException(
                    "Unexpected response Content-Type: $contentType. " +
                        "認証エラーや規約未同意の可能性があります。"
                )
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
            val expectedSha256 = extractSha256FromHeaders(connection)

            if (totalBytes <= 0L) {
                throw IllegalStateException("Cannot determine file size")
            }

            if (resumeFrom >= totalBytes) {
                if (outFile.exists()) outFile.delete()
                if (!tmpFile.renameTo(outFile)) {
                    throw IllegalStateException("Failed to save model file: ${outFile.absolutePath}")
                }
                // 既に全量あり：検証してメタデータを書き直す（キャンセル可能）
                val actualSha256 = sha256Suspend(outFile)
                if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    Log.w(TAG, "Checksum mismatch against remote header hash after resume-complete. Using local measured hash.")
                }
                writeMetadata(outFile, totalBytes, actualSha256)
                onProgress?.invoke(totalBytes, totalBytes)
                return restartedFromZero
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
                        coroutineContext.ensureActive()
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
            if (tmpFile.length() < MIN_VALID_MODEL_BYTES) {
                throw IllegalStateException(
                    "Downloaded file is unexpectedly small: ${tmpFile.length()} bytes"
                )
            }

            // ファイルを確定
            if (outFile.exists()) outFile.delete()
            if (!tmpFile.renameTo(outFile)) {
                throw IllegalStateException("Failed to save model file: ${outFile.absolutePath}")
            }

            // ここが「検証中...」で止まって見える主因になりやすいので、キャンセル可能にして確実にメタデータを書く
            val actualSha256 = sha256Suspend(outFile)
            if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                Log.w(TAG, "Checksum mismatch against remote header hash. Using local measured hash.")
            }
            writeMetadata(outFile, totalBytes, actualSha256)

        } finally {
            connection.disconnect()
        }
        return restartedFromZero
    }

    private fun getRemoteMetadata(urlString: String, token: String): RemoteMetadata {
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
            val etag = connection.getHeaderField("X-Linked-Etag")
                ?: connection.getHeaderField("ETag")
            connection.disconnect()
            val normalizedHash = normalizeSha256FromEtag(etag)
            RemoteMetadata(
                contentLength = if (length > 0) length else 500 * 1024 * 1024,
                sha256 = normalizedHash
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot determine content length", e)
            RemoteMetadata(contentLength = 500 * 1024 * 1024, sha256 = null)
        }
    }

    private fun normalizeSha256FromEtag(rawEtag: String?): String? {
        if (rawEtag.isNullOrBlank()) return null
        val cleaned = rawEtag
            .trim()
            .removePrefix("W/")
            .trim('"')
            .removePrefix("sha256:")
            .lowercase()
        return if (cleaned.matches(Regex("^[a-f0-9]{64}$"))) cleaned else null
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

    /**
     * 旧 `.litertlm` を削除せず `.task` へリネームしてダウンロードし直しを避ける。
     */
    private fun migrateLegacyLiteRtLmToTaskIfNeeded(taskFile: File, model: LocalModel) {
        val parent = taskFile.parentFile ?: return
        val legacy = File(parent, legacyLiteRtLmFilename(model))
        if (!legacy.isFile) return
        if (isValidDownloadedFile(taskFile)) return

        if (legacy.length() < MIN_VALID_MODEL_BYTES) {
            Log.w(TAG, "Legacy litertlm too small to migrate: ${legacy.absolutePath}")
            return
        }

        if (taskFile.exists()) {
            taskFile.delete()
            metadataFile(taskFile).delete()
        }

        val legacyMeta = metadataFile(legacy)
        val destMeta = metadataFile(taskFile)
        if (!legacy.renameTo(taskFile)) {
            Log.w(TAG, "Failed to rename legacy litertlm to .task: ${legacy.name}")
            return
        }
        if (legacyMeta.exists()) {
            if (destMeta.exists()) destMeta.delete()
            if (!legacyMeta.renameTo(destMeta)) {
                runCatching {
                    legacyMeta.copyTo(destMeta, overwrite = true)
                    legacyMeta.delete()
                }.onFailure { Log.w(TAG, "Failed to copy legacy meta for bundled model", it) }
            }
        }
        Log.i(TAG, "Migrated bundled litertlm to .task: ${taskFile.name}")
    }

    /**
     * 検証済み `.task` があるときだけ旧拡張子の本体・メタを削除する（移行失敗時のデータ破棄を防ぐ）。
     */
    private fun cleanupLegacyLiteRtLmFiles(baseFile: File, model: LocalModel) {
        try {
            val legacy = File(baseFile.parentFile, legacyLiteRtLmFilename(model))
            val legacyTmp = File("${legacy.absolutePath}.download")
            val legacyMeta = File("${legacy.absolutePath}.meta")
            val taskOk = isValidDownloadedFile(baseFile)
            if (taskOk) {
                if (legacy.exists()) legacy.delete()
                if (legacyMeta.exists()) legacyMeta.delete()
            }
            if (legacyTmp.exists()) legacyTmp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup legacy litertlm files", e)
        }
    }

    private fun legacyLiteRtLmFilename(model: LocalModel): String {
        return when (model) {
            LocalModel.GEMMA3N_2B -> E2B_LEGACY_LITERTLM_FILENAME
            LocalModel.GEMMA3N_4B -> E4B_LEGACY_LITERTLM_FILENAME
            LocalModel.GEMMA4_2B -> E2B_LEGACY_LITERTLM_FILENAME
            LocalModel.GEMMA4_4B -> E4B_LEGACY_LITERTLM_FILENAME
        }
    }

    private fun isValidDownloadedFile(file: File): Boolean {
        if (!file.exists()) return false
        val fileLength = file.length()
        if (fileLength <= 0L) return false

        val metadata = readMetadata(file) ?: return false
        // サイズ一致だけだと「破損してるのにダウンロード済み扱い」になりがちなので SHA を必須にする
        if (metadata.expectedSha256.isNullOrBlank()) return false
        return fileLength == metadata.expectedBytes
    }

    private fun metadataFile(file: File): File = File("${file.absolutePath}.meta")
    private fun cancelMarkerFile(context: Context, model: LocalModel): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, ".${model.name.lowercase()}.cancel")
    }

    fun markCancelRequested(context: Context, model: LocalModel, requested: Boolean) {
        runCatching {
            val marker = cancelMarkerFile(context, model)
            if (requested) {
                marker.writeText(System.currentTimeMillis().toString())
            } else {
                if (marker.exists()) marker.delete()
            }
        }.onFailure {
            Log.w(TAG, "Failed to update cancel marker", it)
        }
    }

    fun isCancelRequested(context: Context, model: LocalModel): Boolean {
        return runCatching { cancelMarkerFile(context, model).exists() }.getOrDefault(false)
    }

    private fun readMetadata(file: File): ModelMetadata? {
        return try {
            val meta = metadataFile(file)
            if (!meta.exists()) return null
            val lines = meta.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val expectedBytes = lines.first().toLongOrNull() ?: return null
            val expectedSha = lines.getOrNull(1)
                ?.takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) }
                ?.lowercase()
            ModelMetadata(expectedBytes, expectedSha)
        } catch (e: IOException) {
            Log.w(TAG, "Failed to read metadata: ${file.absolutePath}", e)
            null
        }
    }

    private fun writeMetadata(file: File, expectedBytes: Long, expectedSha256: String) {
        if (expectedBytes <= 0L) return
        try {
            metadataFile(file).writeText("$expectedBytes\n${expectedSha256.lowercase()}")
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write metadata: ${file.absolutePath}", e)
        }
    }

    private fun extractSha256FromHeaders(connection: HttpURLConnection): String? {
        val candidates = listOf("X-Linked-ETag", "X-Linked-Etag", "ETag", "Content-Digest")
        val shaRegex = Regex("([a-fA-F0-9]{64})")
        for (name in candidates) {
            val value = connection.getHeaderField(name) ?: continue
            val normalized = value.replace("\"", "").replace("W/", "")
            val match = shaRegex.find(normalized)?.groupValues?.get(1)
            if (!match.isNullOrBlank()) {
                return match.lowercase()
            }
        }
        return null
    }

    private fun sha256Blocking(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun sha256Suspend(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
    }

    private inline fun <T> withModelLock(context: Context, model: LocalModel, block: () -> T): T {
        val lockDir = File(context.filesDir, "models")
        if (!lockDir.exists()) lockDir.mkdirs()
        val lockFile = File(lockDir, ".${model.name.lowercase()}.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock = channel.lock()
            try {
                return block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    private inline fun <T> withModelTryLock(context: Context, model: LocalModel, block: () -> T): T? {
        val lockDir = File(context.filesDir, "models")
        if (!lockDir.exists()) lockDir.mkdirs()
        val lockFile = File(lockDir, ".${model.name.lowercase()}.lock")
        RandomAccessFile(lockFile, "rw").channel.use { channel ->
            val lock = channel.tryLock() ?: return null
            try {
                return block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }
}
