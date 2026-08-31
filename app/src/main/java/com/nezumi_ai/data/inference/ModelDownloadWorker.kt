package com.nezumi_ai.data.inference

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nezumi_ai.MainActivity
import com.nezumi_ai.R
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.utils.ImportedModelCapabilities
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    /** URL が HuggingFace のホストなら true。 */
    private fun isHuggingFaceUrl(url: String): Boolean {
        return try {
            val host = java.net.URL(url).host.orEmpty().lowercase()
            host == "huggingface.co" ||
                host.endsWith(".huggingface.co") ||
                host == "cdn-lfs.huggingface.co" ||
                host.endsWith(".hf.co")
        } catch (_: Exception) {
            false
        }
    }

    /**
     * ダウンロード完了 = 追加完了。
     * ダウンロード済みモデルの「素の状態」プリセットを即時作成し、
     * モデル管理画面・プリセット画面のリストへ反映させる（孤児プリセットも掃除）。
     */
    private suspend fun registerDownloadedModels() {
        runCatching {
            val db = NezumiAiDatabase.getInstance(applicationContext)
            PresetRepository(db.presetDao(), applicationContext).ensurePlainPresetsForDownloadedModels()
        }.onFailure {
            Log.w(TAG, "Failed to register downloaded models as presets", it)
        }
    }

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val downloadKind = inputData.getString(KEY_DOWNLOAD_KIND) ?: DOWNLOAD_KIND_BUILTIN

        return when (downloadKind) {
            DOWNLOAD_KIND_HF_CUSTOM -> doCustomHfWork(startedAt)
            DOWNLOAD_KIND_IMAGE_MODEL -> doImageModelWork(startedAt)
            DOWNLOAD_KIND_SAFETY_MODEL -> doSafetyModelWork()
            DOWNLOAD_KIND_VOICEVOX_MODEL -> doVoicevoxModelWork()
            else -> doBuiltinModelWork(startedAt)
        }
    }

    private suspend fun doSafetyModelWork(): Result {
        val notificationId = 8001
        setForeground(createForegroundInfo("Safety Model", 0L, -1L, notificationId))
        return try {
            val ok = downloadSafetyModelBlocking(
                applicationContext,
                onProgress = { downloaded, total ->
                    setProgressAsync(
                        workDataOf(
                            KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_SAFETY_MODEL,
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total
                        )
                    )
                    setForegroundAsync(createForegroundInfo("Safety Model", downloaded, total, notificationId))
                },
                isCancelled = { isStopped }
            )
            if (ok) Result.success()
            else if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_MESSAGE to "Safety model download failed"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Safety model download failed: ${e.message}", e)
            if (runAttemptCount < 2) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "failed")))
        }
    }

    private suspend fun doBuiltinModelWork(startedAt: Long): Result {

        val modelName = inputData.getString(KEY_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model is missing"))
        val model = modelFromName(modelName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown model"))

        // キャンセル要求が先に出ている場合、開始しない（WorkManagerのキャンセル反映レース対策）
        if (ModelFileManager.isCancelRequested(applicationContext, model)) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "cancelled"))
        }
        val notificationId = getNotificationIdForModel(model)
        setForeground(createForegroundInfo(modelName, 0L, -1L, notificationId))

        return try {
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var hasBaseline = false
            var lastForegroundUpdateTime = 0L
            var lastProgressUpdateTime = 0L
            var lastProgressDownloaded = -1L
            var reachedNearCompletion = false

            val result = ModelFileManager.ensureDownloaded(applicationContext, model) { downloaded, total ->
                if (ModelFileManager.isCancelRequested(applicationContext, model)) {
                    throw CancellationException("cancel requested")
                }
                if (total > 0L && downloaded >= (total * 98L / 100L)) {
                    reachedNearCompletion = true
                }
                val currentTime = System.currentTimeMillis()
                val timeDeltaMs = currentTime - lastTime

                if (!hasBaseline) {
                    hasBaseline = true
                    lastTime = currentTime
                    lastDownloaded = downloaded
                    val data = workDataOf(
                        KEY_DOWNLOADED_BYTES to downloaded,
                        KEY_TOTAL_BYTES to total,
                        KEY_SPEED_MBPS to 0.0,
                        KEY_ESTIMATED_REMAINING_SEC to 0.0
                    )
                    setProgressAsync(data)
                    // 通知バーとアプリの進捗を揃える
                    setForegroundAsync(createForegroundInfo(modelName, downloaded, total))
                    lastProgressUpdateTime = currentTime
                    lastProgressDownloaded = downloaded
                    lastForegroundUpdateTime = currentTime
                    return@ensureDownloaded
                }

                // スピード計算（MB/s）
                val speedMbps = if (timeDeltaMs > 0) {
                    val bytesDelta = (downloaded - lastDownloaded).coerceAtLeast(0L)
                    (bytesDelta.toDouble() / (1024.0 * 1024.0)) / (timeDeltaMs.toDouble() / 1000.0)
                } else {
                    0.0
                }

                // 推定残り時間（秒）
                val remainingBytes = (total - downloaded).coerceAtLeast(0L)
                val estimatedSecRemaining = if (speedMbps > 0) {
                    (remainingBytes.toDouble() / (1024.0 * 1024.0)) / speedMbps
                } else {
                    0.0
                }

                val reachedEnd = total > 0L && downloaded >= total
                val elapsedSinceLastProgress = currentTime - lastProgressUpdateTime
                val progressedBytes = (downloaded - lastProgressDownloaded).coerceAtLeast(0L)
                val shouldPublishProgress = reachedEnd ||
                    elapsedSinceLastProgress >= PROGRESS_UPDATE_INTERVAL_MS ||
                    progressedBytes >= PROGRESS_UPDATE_MIN_BYTES

                if (shouldPublishProgress) {
                    val data = workDataOf(
                        KEY_DOWNLOADED_BYTES to downloaded,
                        KEY_TOTAL_BYTES to total,
                        KEY_SPEED_MBPS to speedMbps,
                        KEY_ESTIMATED_REMAINING_SEC to estimatedSecRemaining
                    )
                    setProgressAsync(data)
                    lastProgressUpdateTime = currentTime
                    lastProgressDownloaded = downloaded
                    // progress更新と同じ値で通知も更新してズレを防ぐ（通知更新は更に間引く）
                    if (currentTime - lastForegroundUpdateTime >= FOREGROUND_UPDATE_INTERVAL_MS || reachedEnd) {
                        setForegroundAsync(createForegroundInfo(modelName, downloaded, total))
                        lastForegroundUpdateTime = currentTime
                    }
                }

                if (timeDeltaMs > 500) { // 0.5秒ごとに速度を更新
                    lastTime = currentTime
                    lastDownloaded = downloaded
                }
            }

            result.fold(
                onSuccess = {
                    doSafetyModelWork() // 画像生成モデルのダウンロード完了時にセーフティモデルも確保
                    registerDownloadedModels() // ダウンロード完了 = 追加完了（素の状態プリセットを即時作成）
                    showDownloadCompletedNotification(model, it.length(), notificationId)
                    Result.success(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to it.length(),
                            KEY_TOTAL_BYTES to it.length(),
                            KEY_SPEED_MBPS to 0.0
                        )
                    )
                },
                onFailure = { e ->
                    if (!reachedNearCompletion && shouldRetry(e, startedAt)) {
                        Result.retry()
                    } else {
                        handleFailure(model, e)
                    }
                }
            )
        } catch (e: CancellationException) {
            // ネットワーク制約切れ (Wi-Fi ⇔ モバイル切替など) や「一時停止」で中断された場合、
            // 部分ファイルが残っていれば削除せず保持する (再開時に Range で続きから取得するため)。
            // 以前はここで無条件に temp を削除していたため、ネットワーク変更のたびに
            // ダウンロードが最初からやり直しになっていた。
            val hasPartial = runCatching {
                val f = ModelFileManager.modelFile(applicationContext, model)
                File("${f.absolutePath}.download").let { it.exists() && it.length() > 0L }
            }.getOrDefault(false)
            if (hasPartial) {
                return Result.failure(
                    workDataOf(KEY_ERROR_MESSAGE to "ダウンロードを中断しました。再開時は続きから取得します。")
                )
            }
            // 完全キャンセル (部分ファイルなし) の場合は temp を掃除して終了
            ModelFileManager.deleteTempDownload(applicationContext, model)
            throw e
        } catch (e: ModelFileManager.PauseRequestedException) {
            // 部分ファイルを残したまま終了 (次回 enqueue で続きから再開される)
            Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "paused")))
        } catch (e: Exception) {
            if (shouldRetry(e, startedAt)) {
                Result.retry()
            } else {
                handleFailure(model, e)
            }
        }
    }

    private suspend fun doCustomHfWork(startedAt: Long): Result {
        val modelId = inputData.getString(KEY_HF_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "hf model id is missing"))
        val filePath = inputData.getString(KEY_HF_FILE_PATH)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "hf file path is missing"))

        val displayName = "$modelId/$filePath"
        val notificationId = 3000 + (modelId + filePath).hashCode().absoluteValue % 500  // 既存の計算式と統一
        setForeground(createForegroundInfo(displayName, 0L, -1L, notificationId))

        return try {
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var hasBaseline = false
            var lastForegroundUpdateTime = 0L
            var lastProgressUpdateTime = 0L
            var lastProgressDownloaded = -1L
            var reachedNearCompletion = false

            val result = ModelFileManager.downloadHuggingFaceModelFile(
                    context = applicationContext,
                    modelId = modelId,
                    filePath = filePath
                ) { downloaded, total ->
                    if (isStopped) {
                        throw CancellationException("cancel requested")
                    }
                    if (total > 0L && downloaded >= (total * 98L / 100L)) {
                        reachedNearCompletion = true
                    }
                    val currentTime = System.currentTimeMillis()
                    val timeDeltaMs = currentTime - lastTime

                    if (!hasBaseline) {
                        hasBaseline = true
                        lastTime = currentTime
                        lastDownloaded = downloaded
                        val data = workDataOf(
                            KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_HF_CUSTOM,
                            KEY_HF_MODEL_ID to modelId,
                            KEY_HF_FILE_PATH to filePath,
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_SPEED_MBPS to 0.0,
                            KEY_ESTIMATED_REMAINING_SEC to 0.0
                        )
                        setProgressAsync(data)
                        setForegroundAsync(createForegroundInfo(displayName, downloaded, total, notificationId))
                        lastProgressUpdateTime = currentTime
                        lastProgressDownloaded = downloaded
                        lastForegroundUpdateTime = currentTime
                        return@downloadHuggingFaceModelFile
                    }

                    val speedMbps = if (timeDeltaMs > 0) {
                        val bytesDelta = (downloaded - lastDownloaded).coerceAtLeast(0L)
                        (bytesDelta.toDouble() / (1024.0 * 1024.0)) / (timeDeltaMs.toDouble() / 1000.0)
                    } else {
                        0.0
                    }
                    val remainingBytes = (total - downloaded).coerceAtLeast(0L)
                    val estimatedSecRemaining = if (speedMbps > 0) {
                        (remainingBytes.toDouble() / (1024.0 * 1024.0)) / speedMbps
                    } else {
                        0.0
                    }

                    val reachedEnd = total > 0L && downloaded >= total
                    val elapsedSinceLastProgress = currentTime - lastProgressUpdateTime
                    val progressedBytes = (downloaded - lastProgressDownloaded).coerceAtLeast(0L)
                    val shouldPublishProgress = reachedEnd ||
                        elapsedSinceLastProgress >= PROGRESS_UPDATE_INTERVAL_MS ||
                        progressedBytes >= PROGRESS_UPDATE_MIN_BYTES

                    if (shouldPublishProgress) {
                        val data = workDataOf(
                            KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_HF_CUSTOM,
                            KEY_HF_MODEL_ID to modelId,
                            KEY_HF_FILE_PATH to filePath,
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_SPEED_MBPS to speedMbps,
                            KEY_ESTIMATED_REMAINING_SEC to estimatedSecRemaining
                        )
                        setProgressAsync(data)
                        lastProgressUpdateTime = currentTime
                        lastProgressDownloaded = downloaded
                        if (currentTime - lastForegroundUpdateTime >= FOREGROUND_UPDATE_INTERVAL_MS || reachedEnd) {
                            setForegroundAsync(createForegroundInfo(displayName, downloaded, total, notificationId))
                            lastForegroundUpdateTime = currentTime
                        }
                    }

                    if (timeDeltaMs > 500) {
                        lastTime = currentTime
                        lastDownloaded = downloaded
                    }
                }

            result.fold(
                onSuccess = { file ->
                    val abs = file.absolutePath
                    val skipMmproj = ModelFileManager.isProbableStableDiffusionWeights(modelId, filePath)
                    if (abs.lowercase().endsWith(".gguf") && !skipMmproj) {
                        // チャット用マルチモーダル GGUF: mmproj を検索してダウンロード
                        val mmprojFile = withContext(Dispatchers.IO) {
                            val mmprojCandidates = ModelFileManager.findMmprojCandidates(applicationContext, modelId, filePath).getOrNull()
                            mmprojCandidates?.firstOrNull()?.let { candidate ->
                                ModelFileManager.downloadHuggingFaceModelFile(
                                    context = applicationContext,
                                    modelId = modelId,
                                    filePath = candidate.path,
                                    onProgress = null // mmproj の進捗は表示しない
                                ).getOrNull()
                            }
                        }

                        ImportedModelCapabilityStore.set(
                            applicationContext,
                            abs,
                            ImportedModelCapabilities(
                                imageEnabled = mmprojFile != null,
                                audioEnabled = false,
                                mmprojPath = mmprojFile?.absolutePath,
                                thinkingEnabled = false
                            )
                        )
                    }
                    registerDownloadedModels() // ダウンロード完了 = 追加完了（素の状態プリセットを即時作成）
                    showCustomDownloadCompletedNotification(modelId, filePath, file.length())
                    Result.success(
                        workDataOf(
                            KEY_HF_MODEL_ID to modelId,
                            KEY_HF_FILE_PATH to filePath,
                            KEY_HF_OUTPUT_ABS_PATH to abs,
                            KEY_DOWNLOADED_BYTES to file.length(),
                            KEY_TOTAL_BYTES to file.length(),
                            KEY_SPEED_MBPS to 0.0
                        )
                    )
                },
                onFailure = { e ->
                    when {
                        e is ModelFileManager.PauseRequestedException ->
                            Result.failure(workDataOf(
                                KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_HF_CUSTOM,
                                KEY_HF_MODEL_ID to modelId,
                                KEY_HF_FILE_PATH to filePath,
                                KEY_ERROR_MESSAGE to (e.message ?: "paused")
                            ))
                        !reachedNearCompletion && shouldRetry(e, startedAt) -> Result.retry()
                        else -> handleCustomFailure(modelId, filePath, e)
                    }
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (shouldRetry(e, startedAt)) {
                Result.retry()
            } else {
                handleCustomFailure(modelId, filePath, e)
            }
        }
    }

    private fun shouldRetry(error: Throwable, startedAt: Long): Boolean {
        if (runAttemptCount >= MAX_WORK_RETRY) return false
        val message = error.message.orEmpty().lowercase()
        if ("checksum mismatch" in message ||
            "整合性検証" in message ||
            "再取得が繰り返されています" in message ||
            "content-type" in message ||
            "http 401" in message ||
            "http 403" in message
        ) {
            return false
        }
        if (error is SocketTimeoutException || error is UnknownHostException || error is SocketException) {
            return true
        }
        return "timeout" in message || "connection reset" in message || "unexpected end of stream" in message
    }

    private fun createForegroundInfo(modelName: String, downloaded: Long, total: Long): ForegroundInfo {
        val model = modelFromName(modelName)
        val notificationId = if (model != null) getNotificationIdForModel(model) else NOTIFICATION_ID
        return createForegroundInfo(modelName, downloaded, total, notificationId)
    }

    private fun createForegroundInfo(modelName: String, downloaded: Long, total: Long, notificationId: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Model download",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }

        val contentText = if (total > 0L) {
            val percent = ((downloaded.toDouble() / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
            "$percent% ($downloaded / $total bytes)"
        } else {
            "Downloading..."
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading model: $modelName")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (total > 0L) ((downloaded * 100L) / total).toInt() else 0, total <= 0L)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"

        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOAD_KIND = "download_kind"
        const val KEY_HF_MODEL_ID = "hf_model_id"
        const val KEY_HF_FILE_PATH = "hf_file_path"
        /** カスタム HF ダウンロード成功時のローカル絶対パス */
        const val KEY_HF_OUTPUT_ABS_PATH = "hf_output_abs_path"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_SPEED_MBPS = "speed_mbps"
        const val KEY_ESTIMATED_REMAINING_SEC = "estimated_remaining_sec"
        const val DOWNLOAD_KIND_BUILTIN = "builtin"
        const val DOWNLOAD_KIND_HF_CUSTOM = "hf_custom"
        const val DOWNLOAD_KIND_IMAGE_MODEL = "image_model"
        const val KEY_IMAGE_MODEL_ID = "image_model_id"
        const val KEY_IMAGE_MODEL_URL = "image_model_url"
        const val KEY_IMAGE_MODEL_FILENAME = "image_model_filename"
        const val KEY_IMAGE_MODEL_NAME = "image_model_name"
        /** 画像モデル: zip 展開フェーズに入ったことを UI に伝えるフラグ */
        const val KEY_IMAGE_MODEL_IS_EXTRACTING = "image_model_is_extracting"
        const val DOWNLOAD_KIND_SAFETY_MODEL = "safety_model"

        // ── VOICEVOX 音声モデル ─────────────────────────────────
        // LLM モデルと同じ WorkManager + 進捗通知の仕組みに載せる。
        const val DOWNLOAD_KIND_VOICEVOX_MODEL = "voicevox_model"
        const val KEY_VOICEVOX_FILE_NAME = "voicevox_file_name"
        const val KEY_VOICEVOX_URL = "voicevox_url"
        const val KEY_VOICEVOX_DISPLAY_NAME = "voicevox_display_name"
        const val KEY_VOICEVOX_NEEDS_DICTIONARY = "voicevox_needs_dictionary"
        /** 進捗の対象フェーズ: "model" | "dictionary" */
        const val KEY_VOICEVOX_PHASE = "voicevox_phase"
        const val VOICEVOX_PHASE_MODEL = "model"
        const val VOICEVOX_PHASE_DICTIONARY = "dictionary"
        const val TAG_VOICEVOX_DOWNLOAD = "voicevox_model_download"

        /**
         * VOICEVOX モデル (と必要なら OpenJTalk 辞書) のダウンロード＋インストールが完了したときに
         * アプリ内向けに送られるローカルブロードキャストのアクション。
         * MyApplication がこれを受けて、共有 VoicevoxManager の自動初期化を走らせる。
         */
        const val ACTION_VOICEVOX_MODEL_READY = "com.nezumi_ai.voicevox.MODEL_READY"
        const val SAFETY_MODEL_WORK_NAME = "safety_model_download"
        const val SAFETY_MODEL_URL =
            "https://huggingface.co/AdamCodd/vit-base-nsfw-detector/resolve/main/onnx/model.onnx?download=true"
        const val SAFETY_MODEL_FILENAME = "safety.onnx"
        const val TAG_HF_CUSTOM_DOWNLOAD = "hf_custom_download"
        const val TAG_IMAGE_MODEL_DOWNLOAD = "image_model_download"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_WORK_RETRY = 2
        // setProgressAsync を投げすぎると UI/WorkManager 側が詰まって「検証中で止まって見える」ことがあるため、強めに間引く
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000L
        private const val PROGRESS_UPDATE_MIN_BYTES = 2L * 1024L * 1024L
        // 通知がアプリより遅れて見えないよう、progress更新と同程度に揃える
        private const val FOREGROUND_UPDATE_INTERVAL_MS = 1000L
        private const val NOTIFICATION_CHANNEL_NAME = "モデルダウンロード"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION = "モデルのバックグラウンドダウンロード完了通知"

        fun modelWorkName(model: ModelFileManager.LocalModel): String =
            "model_download_${model.name.lowercase()}"

        fun enqueue(context: Context, model: ModelFileManager.LocalModel): Boolean {
            val workManager = WorkManager.getInstance(context)
            val hasActive = runCatching {
                workManager.getWorkInfosForUniqueWork(modelWorkName(model))
                    .get(2, TimeUnit.SECONDS)
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }.getOrDefault(false)
            if (hasActive) return false

            // 新規開始時は、過去のキャンセル要求をクリアする
            ModelFileManager.markCancelRequested(context, model, false)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_BUILTIN,
                        KEY_MODEL_NAME to model.name
                    )
                )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()
            workManager.enqueueUniqueWork(
                modelWorkName(model),
                ExistingWorkPolicy.KEEP,
                request
            )
            return true
        }

        fun customWorkName(modelId: String, filePath: String): String {
            val key = "$modelId|$filePath".lowercase()
            val hash = key.hashCode().absoluteValue
            return "hf_custom_download_$hash"
        }

        fun enqueueCustomHf(context: Context, modelId: String, filePath: String): Boolean {
            val outFile = ModelFileManager.huggingFaceImportedFile(context, modelId, filePath)
            if (outFile.isFile && outFile.canRead() && outFile.length() > 0L) {
                return false
            }
            val workName = customWorkName(modelId, filePath)
            val workManager = WorkManager.getInstance(context)
            val hasActive = runCatching {
                workManager.getWorkInfosForUniqueWork(workName)
                    .get(2, TimeUnit.SECONDS)
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }.getOrDefault(false)
            if (hasActive) return false

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_HF_CUSTOM,
                        KEY_HF_MODEL_ID to modelId,
                        KEY_HF_FILE_PATH to filePath
                    )
                )
                .addTag(TAG_HF_CUSTOM_DOWNLOAD)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()
            workManager.enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request
            )
            return true
        }

        fun cancelCustomHf(context: Context, modelId: String, filePath: String) {
            WorkManager.getInstance(context).cancelUniqueWork(customWorkName(modelId, filePath))
            // 部分ファイル (.download) は削除しない。ユーザーが再開したい場合に続きから取得できるようにする。
        }

        /**
         * 一時停止: ダウンロードを中断するが部分ファイル (.download) は残す。
         * 次回 [enqueue] 時に Range リクエストで続きから再開できる。
         */
        fun pause(context: Context, model: ModelFileManager.LocalModel) {
            ModelFileManager.markCancelRequested(context, model, true)
            WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
            // cancel() と違い、部分ファイルは残す
        }

        fun pauseCustomHf(context: Context, modelId: String, filePath: String) {
            WorkManager.getInstance(context).cancelUniqueWork(customWorkName(modelId, filePath))
        }

        fun pauseImageModel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(imageModelWorkName(modelId))
        }

        fun imageModelWorkName(modelId: String): String = "image_model_download_$modelId"

        fun enqueueImageModel(context: Context, modelId: String, downloadUrl: String, fileName: String, modelName: String): Boolean {
            val workName = imageModelWorkName(modelId)
            android.util.Log.d("ModelDownloadWorker", "enqueueImageModel: modelId=$modelId, modelName=$modelName, fileName=$fileName")
            val workManager = WorkManager.getInstance(context)
            val hasActive = runCatching {
                workManager.getWorkInfosForUniqueWork(workName)
                    .get(2, TimeUnit.SECONDS)
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }.getOrDefault(false)
            if (hasActive) return false

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_IMAGE_MODEL,
                        KEY_IMAGE_MODEL_ID to modelId,
                        KEY_IMAGE_MODEL_URL to downloadUrl,
                        KEY_IMAGE_MODEL_FILENAME to fileName,
                        KEY_IMAGE_MODEL_NAME to modelName
                    )
                )
                .addTag(TAG_IMAGE_MODEL_DOWNLOAD)
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .build()
            workManager.enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.KEEP,
                request
            )
            return true
        }

        fun cancelImageModel(context: Context, modelId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(imageModelWorkName(modelId))
        }

        // ── VOICEVOX ────────────────────────────────────────────
        const val VOICEVOX_WORK_NAME = "voicevox_model_download"

        /**
         * VOICEVOX 音声モデル（.vvm）と、必要なら OpenJTalk 辞書をバックグラウンドで取得する。
         * LLM モデルと同じ進捗表示・通知・キャンセル導線に載る。
         */
        fun enqueueVoicevoxModel(
            context: Context,
            fileName: String,
            url: String,
            displayName: String,
            needsDictionary: Boolean
        ): Boolean {
            val workManager = WorkManager.getInstance(context)
            val hasActive = runCatching {
                workManager.getWorkInfosForUniqueWork(VOICEVOX_WORK_NAME)
                    .get(2, TimeUnit.SECONDS)
                    .any {
                        it.state == WorkInfo.State.ENQUEUED ||
                            it.state == WorkInfo.State.RUNNING ||
                            it.state == WorkInfo.State.BLOCKED
                    }
            }.getOrDefault(false)
            if (hasActive) return false

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_VOICEVOX_MODEL,
                        KEY_VOICEVOX_FILE_NAME to fileName,
                        KEY_VOICEVOX_URL to url,
                        KEY_VOICEVOX_DISPLAY_NAME to displayName,
                        KEY_VOICEVOX_NEEDS_DICTIONARY to needsDictionary
                    )
                )
                .addTag(TAG_VOICEVOX_DOWNLOAD)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(VOICEVOX_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        fun cancelVoicevoxModel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(VOICEVOX_WORK_NAME)
        }

        fun safetyModelFile(context: Context): File =
            File(context.filesDir, SAFETY_MODEL_FILENAME)

        fun isSafetyModelReady(context: Context): Boolean = true

        fun isSafetyModelUsable(context: Context): Boolean = true

        suspend fun downloadSafetyModelBlocking(
            context: Context,
            onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
            isCancelled: () -> Boolean = { false }
        ): Boolean = withContext(Dispatchers.IO) {
            if (isSafetyModelUsable(context)) return@withContext true

            val destFile = safetyModelFile(context)
            val tempFile = File(context.cacheDir, "$SAFETY_MODEL_FILENAME.tmp")
            tempFile.delete()
            try {
                val conn = (java.net.URL(SAFETY_MODEL_URL).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                try {
                    conn.connect()
                    if (conn.responseCode !in 200..299) {
                        Log.e(TAG, "Safety model download HTTP ${conn.responseCode}")
                        return@withContext false
                    }
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var downloaded = 0L
                            var read: Int
                            var lastProgressMs = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                if (isCancelled()) throw CancellationException("cancelled")
                                output.write(buf, 0, read)
                                downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastProgressMs >= 300L) {
                                    lastProgressMs = now
                                    onProgress?.invoke(downloaded, total)
                                }
                            }
                        }
                    }
                    if (tempFile.length() == 0L) {
                        Log.e(TAG, "Safety model download produced empty file")
                        return@withContext false
                    }
                    if (destFile.exists()) destFile.delete()
                    if (!tempFile.renameTo(destFile)) {
                        Log.e(TAG, "Safety model download failed to rename temp file")
                        return@withContext false
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: CancellationException) {
                tempFile.delete()
                throw e
            } catch (e: Exception) {
                tempFile.delete()
                Log.e(TAG, "Safety model download failed: ${e.message}", e)
                return@withContext false
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
            isSafetyModelUsable(context)
        }

        fun enqueueSafetyModel(context: Context): Boolean {
            if (isSafetyModelUsable(context)) return false
            val workManager = WorkManager.getInstance(context)
            val hasActive = runCatching {
                workManager.getWorkInfosForUniqueWork(SAFETY_MODEL_WORK_NAME)
                    .get(2, TimeUnit.SECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(false)
            if (hasActive) return false
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_SAFETY_MODEL))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(SAFETY_MODEL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        suspend fun awaitSafetyModelReady(
            context: Context,
            timeoutMs: Long = 5 * 60_000L,
            onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
        ): Boolean {
            if (isSafetyModelUsable(context)) return true
            Log.i(TAG, "Safety model missing or unloadable, starting direct download...")
            enqueueSafetyModel(context)
            return downloadSafetyModelBlocking(context, onProgress)
        }

        fun cancel(context: Context, model: ModelFileManager.LocalModel) {
            // WorkManagerのキャンセル反映より先に止められるよう、永続フラグを立てる
            ModelFileManager.markCancelRequested(context, model, true)
            WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
            ModelFileManager.deleteTempDownload(context, model)
        }

        private fun modelFromName(name: String): ModelFileManager.LocalModel? {
            return when (name.uppercase()) {
                ModelFileManager.LocalModel.GEMMA3N_2B.name -> ModelFileManager.LocalModel.GEMMA3N_2B
                ModelFileManager.LocalModel.GEMMA3N_4B.name -> ModelFileManager.LocalModel.GEMMA3N_4B
                ModelFileManager.LocalModel.GEMMA4_2B.name -> ModelFileManager.LocalModel.GEMMA4_2B
                ModelFileManager.LocalModel.GEMMA4_4B.name -> ModelFileManager.LocalModel.GEMMA4_4B
                else -> null
            }
        }

        private fun getNotificationIdForModel(model: ModelFileManager.LocalModel): Int {
            // 各モデルに一意のNotification IDを割り当てる
            // Base ID 2001 + ordinal (0-3)
            return NOTIFICATION_ID + model.ordinal
        }
    }

    private fun handleFailure(model: ModelFileManager.LocalModel, error: Throwable): Result {
        val message = error.message ?: "download failed"
        // 部分ファイルは残し、次回 enqueue 時のレジュームに備える
        showDownloadFailedNotification(model, message)
        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to message))
    }

    private fun handleCustomFailure(modelId: String, filePath: String, error: Throwable): Result {
        val message = error.message ?: "download failed"
        val outFile = ModelFileManager.huggingFaceImportedFile(applicationContext, modelId, filePath)
        // 部分ファイル (.download) は削除せず残す。次回再開時に続きから取得できるようにする。
        runCatching { outFile.delete() }
        runCatching { File("${outFile.absolutePath}.meta").delete() }
        showCustomDownloadFailedNotification(modelId, filePath, message)
        return Result.failure(
            workDataOf(
                KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_HF_CUSTOM,
                KEY_HF_MODEL_ID to modelId,
                KEY_HF_FILE_PATH to filePath,
                KEY_ERROR_MESSAGE to message
            )
        )
    }

    private fun showDownloadCompletedNotification(model: ModelFileManager.LocalModel, sizeBytes: Long) {
        val notificationId = getNotificationIdForModel(model)
        showDownloadCompletedNotification(model, sizeBytes, notificationId)
    }

    private fun showDownloadCompletedNotification(model: ModelFileManager.LocalModel, sizeBytes: Long, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }

        ensureNotificationChannel()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            model.ordinal,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modelLabel = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "Gemma 3N 2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "Gemma 3N 4B"
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
        }
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("モデルのダウンロードが完了しました")
            .setContentText("$modelLabel (${String.format("%.1f", sizeMb)} MB)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelLabel のダウンロードが完了しました。チャット画面から利用できます。")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(notificationId, notification)
    }

    private fun showDownloadFailedNotification(model: ModelFileManager.LocalModel, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }

        ensureNotificationChannel()

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            100 + model.ordinal,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modelLabel = when (model) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "Gemma 3N 2B"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "Gemma 3N 4B"
            ModelFileManager.LocalModel.GEMMA4_2B -> "Gemma 4 2B"
            ModelFileManager.LocalModel.GEMMA4_4B -> "Gemma 4 4B"
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("モデルのダウンロードに失敗しました")
            .setContentText("$modelLabel: $reason")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelLabel のダウンロードを中断しました。理由: $reason")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext)
            .notify(2000 + model.ordinal, notification)
    }

    private fun showCustomDownloadCompletedNotification(modelId: String, filePath: String, sizeBytes: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        ensureNotificationChannel()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            (modelId + filePath).hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        val shortName = filePath.substringAfterLast('/')
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("モデルのダウンロードが完了しました")
            .setContentText("$shortName (${String.format("%.1f", sizeMb)} MB)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelId の $filePath をダウンロードしました。")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(3000 + (modelId + filePath).hashCode().absoluteValue % 500, notification)
    }

    private fun showCustomDownloadFailedNotification(modelId: String, filePath: String, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        ensureNotificationChannel()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            10000 + (modelId + filePath).hashCode().absoluteValue % 500,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val shortName = filePath.substringAfterLast('/')
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("モデルのダウンロードに失敗しました")
            .setContentText("$shortName: $reason")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelId / $filePath のダウンロードに失敗しました。理由: $reason")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(4000 + (modelId + filePath).hashCode().absoluteValue % 500, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = NOTIFICATION_CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * VOICEVOX 音声モデルのダウンロード。
     *
     * これまで VoicevoxManager が UI スレッド起点のコルーチンで直接ダウンロードしており、
     * 進捗が一切見えず、画面遷移で中断していた。LLM モデルと同じ ModelDownloadWorker に
     * 載せ替えることで、進捗バー・通知・バックグラウンド継続を共通化する。
     */
    private suspend fun doVoicevoxModelWork(): Result {
        val fileName = inputData.getString(KEY_VOICEVOX_FILE_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "voicevox file name is missing"))
        val url = inputData.getString(KEY_VOICEVOX_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "voicevox url is missing"))
        val displayName = inputData.getString(KEY_VOICEVOX_DISPLAY_NAME) ?: fileName
        val needsDictionary = inputData.getBoolean(KEY_VOICEVOX_NEEDS_DICTIONARY, false)

        val notificationId = 6001
        setForeground(createForegroundInfo(displayName, 0L, -1L, notificationId))

        // アプリ全体で共有される VoicevoxManager を使う。
        //   Worker 内で new VoicevoxManager(...) してしまうと、install した後の
        //   _installedModelFileName / _isReady が UI 側の別インスタンスに反映されず、
        //   ダウンロード完了後の自動初期化フローが動かなくなる。
        val manager = (applicationContext as? com.nezumi_ai.MyApplication)?.getVoicevoxManager()
            ?: com.nezumi_ai.voicevox.VoicevoxManager(applicationContext)
        val entry = com.nezumi_ai.voicevox.VoicevoxManager.catalogEntryFor(fileName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown voicevox model: $fileName"))

        val tempFile = File(applicationContext.cacheDir, "$fileName.download")
        tempFile.delete()

        return try {
            // ── フェーズ 1: .vvm 本体 ───────────────────────────
            downloadWithProgress(
                url = url,
                destination = tempFile,
                phase = VOICEVOX_PHASE_MODEL,
                fileName = fileName,
                displayName = displayName,
                notificationId = notificationId
            )

            val installed = withContext(Dispatchers.IO) {
                manager.installDownloadedModel(entry, tempFile)
            }
            if (!installed) {
                return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "音声モデルの保存に失敗しました"))
            }

            // ── フェーズ 2: OpenJTalk 辞書（未取得のときだけ）──
            if (needsDictionary && !manager.isDictionaryReady()) {
                setProgressAsync(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_VOICEVOX_MODEL,
                        KEY_VOICEVOX_FILE_NAME to fileName,
                        KEY_VOICEVOX_PHASE to VOICEVOX_PHASE_DICTIONARY,
                        KEY_DOWNLOADED_BYTES to 0L,
                        KEY_TOTAL_BYTES to -1L
                    )
                )
                val ok = withContext(Dispatchers.IO) {
                    manager.ensureDictionary { downloaded, total ->
                        if (isStopped) throw CancellationException("cancel requested")
                        setProgressAsync(
                            workDataOf(
                                KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_VOICEVOX_MODEL,
                                KEY_VOICEVOX_FILE_NAME to fileName,
                                KEY_VOICEVOX_PHASE to VOICEVOX_PHASE_DICTIONARY,
                                KEY_DOWNLOADED_BYTES to downloaded,
                                KEY_TOTAL_BYTES to total
                            )
                        )
                        setForegroundAsync(
                            createForegroundInfo("OpenJTalk辞書", downloaded, total, notificationId)
                        )
                    }
                }
                if (!ok) {
                    Log.w(TAG, "OpenJTalk dictionary download failed; model itself is installed")
                }
            }

            // ダウンロード & インストールが完了したので、アプリ側で自動初期化を走らせるための
            // ローカルブロードキャストを送る。MyApplication が受け取り、共有 VoicevoxManager の
            // initialize() を非同期に呼び出す。
            runCatching {
                val intent = android.content.Intent(ACTION_VOICEVOX_MODEL_READY).apply {
                    setPackage(applicationContext.packageName)
                    putExtra(KEY_VOICEVOX_FILE_NAME, fileName)
                }
                applicationContext.sendBroadcast(intent)
            }.onFailure { Log.w(TAG, "Failed to broadcast VOICEVOX model ready", it) }

            Result.success(
                workDataOf(
                    KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_VOICEVOX_MODEL,
                    KEY_VOICEVOX_FILE_NAME to fileName
                )
            )
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "VOICEVOX model download failed: ${e.message}", e)
            if (runAttemptCount < MAX_WORK_RETRY) Result.retry()
            else Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "failed")))
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** VOICEVOX 用のシンプルな進捗付きダウンロード（LLM 側と同じ間引きポリシーを使う）。 */
    private suspend fun downloadWithProgress(
        url: String,
        destination: File,
        phase: String,
        fileName: String,
        displayName: String,
        notificationId: Int
    ) = withContext(Dispatchers.IO) {
        val connection = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${connection.responseCode} for $url")
            }
            val totalBytes = connection.contentLengthLong
            var lastProgressMs = 0L
            var lastForegroundMs = 0L
            var lastProgressBytes = -1L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    while (true) {
                        if (isStopped) throw CancellationException("cancel requested")
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        val reachedEnd = totalBytes > 0L && downloaded >= totalBytes
                        val progressed = (downloaded - lastProgressBytes).coerceAtLeast(0L)
                        if (reachedEnd ||
                            now - lastProgressMs >= PROGRESS_UPDATE_INTERVAL_MS ||
                            progressed >= PROGRESS_UPDATE_MIN_BYTES
                        ) {
                            setProgressAsync(
                                workDataOf(
                                    KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_VOICEVOX_MODEL,
                                    KEY_VOICEVOX_FILE_NAME to fileName,
                                    KEY_VOICEVOX_PHASE to phase,
                                    KEY_DOWNLOADED_BYTES to downloaded,
                                    KEY_TOTAL_BYTES to totalBytes
                                )
                            )
                            lastProgressMs = now
                            lastProgressBytes = downloaded
                            if (reachedEnd || now - lastForegroundMs >= FOREGROUND_UPDATE_INTERVAL_MS) {
                                setForegroundAsync(
                                    createForegroundInfo(displayName, downloaded, totalBytes, notificationId)
                                )
                                lastForegroundMs = now
                            }
                        }
                    }
                }
            }
            if (destination.length() == 0L) {
                throw java.io.IOException("Downloaded file is empty: $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun doImageModelWork(startedAt: Long): Result {
        val modelId = inputData.getString(KEY_IMAGE_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "image model id is missing"))
        val downloadUrl = inputData.getString(KEY_IMAGE_MODEL_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "download url is missing"))
        val fileName = inputData.getString(KEY_IMAGE_MODEL_FILENAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "filename is missing"))
        val modelName = inputData.getString(KEY_IMAGE_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model name is missing"))

        android.util.Log.d("ModelDownloadWorker", "doImageModelWork: modelId=$modelId, modelName=$modelName, url=$downloadUrl")

        val displayName = modelName
        val notificationId = 5000 + modelId.hashCode().absoluteValue % 500
        setForeground(createForegroundInfo(displayName, 0L, -1L, notificationId))

        // Use modelId (not modelName) as directory name to ensure consistency
        val destDir = File(applicationContext.filesDir, "sd_models/$modelId")
        android.util.Log.d("ModelDownloadWorker", "doImageModelWork: destDir=${destDir.absolutePath}")
        if (!destDir.exists()) destDir.mkdirs()

        val tempFile = File(applicationContext.cacheDir, "$fileName.tmp")

        return try {
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var hasBaseline = false
            var lastForegroundUpdateTime = 0L
            var lastProgressUpdateTime = 0L
            var lastProgressDownloaded = -1L
            var reachedNearCompletion = false

            withContext(Dispatchers.IO) {
                val url = java.net.URL(downloadUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "nezumi-ai/1.0")
                // HF 連携済みの場合は必ずトークンを付与（レート制限回避と
                // 非公開リポジトリ・規約同意後のファイルへのアクセスのため）
                if (isHuggingFaceUrl(downloadUrl)) {
                    val token = HfAuthManager.getToken(applicationContext)
                    if (token.isNotBlank()) {
                        connection.setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                connection.connect()

                val totalBytes = connection.contentLengthLong
                android.util.Log.d("ModelDownloadWorker", "doImageModelWork: totalBytes=$totalBytes")

                connection.getInputStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int

                        while (input.read(buffer).also { read = it } != -1) {
                            if (isStopped) {
                                throw CancellationException("cancel requested")
                            }
                            output.write(buffer, 0, read)
                            downloaded += read

                            if (totalBytes > 0L && downloaded >= (totalBytes * 98L / 100L)) {
                                reachedNearCompletion = true
                            }

                            val currentTime = System.currentTimeMillis()
                            val timeDeltaMs = currentTime - lastTime

                            if (!hasBaseline) {
                                hasBaseline = true
                                lastTime = currentTime
                                lastDownloaded = downloaded
                                val data = workDataOf(
                                    KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_IMAGE_MODEL,
                                    KEY_IMAGE_MODEL_ID to modelId,
                                    KEY_IMAGE_MODEL_NAME to modelName,
                                    KEY_DOWNLOADED_BYTES to downloaded,
                                    KEY_TOTAL_BYTES to totalBytes
                                )
                                setProgressAsync(data)
                                setForegroundAsync(createForegroundInfo(displayName, downloaded, totalBytes, notificationId))
                                lastProgressUpdateTime = currentTime
                                lastProgressDownloaded = downloaded
                                lastForegroundUpdateTime = currentTime
                                continue
                            }

                            val speedMbps = if (timeDeltaMs > 0) {
                                val bytesDelta = (downloaded - lastDownloaded).coerceAtLeast(0L)
                                (bytesDelta.toDouble() / (1024.0 * 1024.0)) / (timeDeltaMs.toDouble() / 1000.0)
                            } else {
                                0.0
                            }

                            val reachedEnd = totalBytes > 0L && downloaded >= totalBytes
                            val elapsedSinceLastProgress = currentTime - lastProgressUpdateTime
                            val progressedBytes = (downloaded - lastProgressDownloaded).coerceAtLeast(0L)
                            val shouldPublishProgress = reachedEnd ||
                                elapsedSinceLastProgress >= PROGRESS_UPDATE_INTERVAL_MS ||
                                progressedBytes >= PROGRESS_UPDATE_MIN_BYTES

                            if (shouldPublishProgress) {
                                val data = workDataOf(
                                    KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_IMAGE_MODEL,
                                    KEY_IMAGE_MODEL_ID to modelId,
                                    KEY_IMAGE_MODEL_NAME to modelName,
                                    KEY_DOWNLOADED_BYTES to downloaded,
                                    KEY_TOTAL_BYTES to totalBytes,
                                    KEY_SPEED_MBPS to speedMbps
                                )
                                setProgressAsync(data)
                                lastProgressUpdateTime = currentTime
                                lastProgressDownloaded = downloaded
                                if (currentTime - lastForegroundUpdateTime >= FOREGROUND_UPDATE_INTERVAL_MS || reachedEnd) {
                                    setForegroundAsync(createForegroundInfo(displayName, downloaded, totalBytes, notificationId))
                                    lastForegroundUpdateTime = currentTime
                                }
                            }

                            if (timeDeltaMs > 500) {
                                lastTime = currentTime
                                lastDownloaded = downloaded
                            }
                        }
                    }
                }

                android.util.Log.d("ModelDownloadWorker", "doImageModelWork: download complete, extracting zip")

                // DL 完了〜 zip 展開の間はバイト数が一切進まない (GB 級モデルでは数十秒)。
                // UI 側が「ダウンロード中 100%」のまま固まって見えるのを防ぐため、
                // 展開フェーズに入ったことを進捗データと通知に明示する。
                val zipBytes = tempFile.length()
                setProgressAsync(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_IMAGE_MODEL,
                        KEY_IMAGE_MODEL_ID to modelId,
                        KEY_IMAGE_MODEL_NAME to modelName,
                        KEY_DOWNLOADED_BYTES to zipBytes,
                        KEY_TOTAL_BYTES to zipBytes,
                        KEY_IMAGE_MODEL_IS_EXTRACTING to true
                    )
                )
                setForegroundAsync(createForegroundInfo(displayName, zipBytes, zipBytes, notificationId))

                // Unzip
                java.util.zip.ZipInputStream(tempFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    var fileCount = 0
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        android.util.Log.d("ModelDownloadWorker", "doImageModelWork: extracting ${entry.name} to ${outFile.absolutePath}")
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            outFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                            fileCount++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    android.util.Log.d("ModelDownloadWorker", "doImageModelWork: extracted $fileCount files")
                }

                tempFile.delete()

                // List extracted files
                destDir.listFiles()?.forEach { file ->
                    android.util.Log.d("ModelDownloadWorker", "doImageModelWork: extracted file: ${file.name}")
                }
            }

            doSafetyModelWork()
            android.util.Log.d("ModelDownloadWorker", "doImageModelWork: SUCCESS")
            showImageModelDownloadCompletedNotification(modelName, destDir.length())
            Result.success(
                workDataOf(
                    KEY_IMAGE_MODEL_ID to modelId,
                    KEY_IMAGE_MODEL_NAME to modelName
                )
            )
        } catch (e: CancellationException) {
            android.util.Log.d("ModelDownloadWorker", "doImageModelWork: CANCELLED", e)
            runCatching { tempFile.delete() }
            throw e
        } catch (e: Exception) {
            android.util.Log.e("ModelDownloadWorker", "doImageModelWork: FAILED", e)
            runCatching { tempFile.delete() }
            if (shouldRetry(e, startedAt)) {
                Result.retry()
            } else {
                showImageModelDownloadFailedNotification(modelName, e.message ?: "unknown error")
                Result.failure(
                    workDataOf(
                        KEY_DOWNLOAD_KIND to DOWNLOAD_KIND_IMAGE_MODEL,
                        KEY_IMAGE_MODEL_ID to modelId,
                        KEY_ERROR_MESSAGE to (e.message ?: "download failed")
                    )
                )
            }
        }
    }

    private fun showImageModelDownloadCompletedNotification(modelName: String, sizeBytes: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        ensureNotificationChannel()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            modelName.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val sizeMb = sizeBytes / (1024.0 * 1024.0)
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_add)
            .setContentTitle("画像生成モデルのダウンロードが完了しました")
            .setContentText("$modelName (${String.format("%.1f", sizeMb)} MB)")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelName のダウンロードが完了しました。画像生成画面から利用できます。")
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(6000 + modelName.hashCode().absoluteValue % 500, notification)
    }

    private fun showImageModelDownloadFailedNotification(modelName: String, reason: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) return
        }
        ensureNotificationChannel()
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            10000 + modelName.hashCode().absoluteValue % 500,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("画像生成モデルのダウンロードに失敗しました")
            .setContentText("$modelName: $reason")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$modelName のダウンロードに失敗しました。理由: $reason")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        NotificationManagerCompat.from(applicationContext)
            .notify(7000 + modelName.hashCode().absoluteValue % 500, notification)
    }
}