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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val startedAt = System.currentTimeMillis()
        val modelName = inputData.getString(KEY_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model is missing"))
        val model = modelFromName(modelName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown model"))

        // キャンセル要求が先に出ている場合、開始しない（WorkManagerのキャンセル反映レース対策）
        if (ModelFileManager.isCancelRequested(applicationContext, model)) {
            return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "cancelled"))
        }
        setForeground(createForegroundInfo(modelName, 0L, -1L))

        return try {
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var hasBaseline = false
            var lastForegroundUpdateTime = 0L
            var lastProgressUpdateTime = 0L
            var lastProgressDownloaded = -1L
            var reachedNearCompletion = false

            val result = withTimeout(MAX_SINGLE_WORK_DURATION_MS) {
                ModelFileManager.ensureDownloaded(applicationContext, model) { downloaded, total ->
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
            }

            result.fold(
                onSuccess = {
                    showDownloadCompletedNotification(model, it.length())
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
        } catch (e: TimeoutCancellationException) {
            handleFailure(model, IllegalStateException("ダウンロードが一定時間内に完了しなかったため中断しました"))
        } catch (e: CancellationException) {
            // cancellation marker が立っている場合は temp を掃除して終了
            ModelFileManager.deleteTempDownload(applicationContext, model)
            throw e
        } catch (e: Exception) {
            if (shouldRetry(e, startedAt)) {
                Result.retry()
            } else {
                handleFailure(model, e)
            }
        }
    }

    private fun shouldRetry(error: Throwable, startedAt: Long): Boolean {
        if (runAttemptCount >= MAX_WORK_RETRY) return false
        if (System.currentTimeMillis() - startedAt >= MAX_SINGLE_WORK_DURATION_MS) return false
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
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_SPEED_MBPS = "speed_mbps"
        const val KEY_ESTIMATED_REMAINING_SEC = "estimated_remaining_sec"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_WORK_RETRY = 2
        private const val MAX_SINGLE_WORK_DURATION_MS = 25L * 60L * 1000L
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
                .setInputData(workDataOf(KEY_MODEL_NAME to model.name))
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

        fun cancel(context: Context, model: ModelFileManager.LocalModel) {
            // WorkManagerのキャンセル反映より先に止められるよう、永続フラグを立てる
            ModelFileManager.markCancelRequested(context, model, true)
            WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
            ModelFileManager.deleteTempDownload(context, model)
        }

        private fun modelFromName(name: String): ModelFileManager.LocalModel? {
            return when (name.uppercase()) {
                ModelFileManager.LocalModel.GEMMA4_2B.name -> ModelFileManager.LocalModel.GEMMA4_2B
                ModelFileManager.LocalModel.GEMMA4_4B.name -> ModelFileManager.LocalModel.GEMMA4_4B
                else -> null
            }
        }
    }

    private fun handleFailure(model: ModelFileManager.LocalModel, error: Throwable): Result {
        val message = error.message ?: "download failed"
        // 失敗時は一時ファイルを掃除し、ユーザーへ通知する
        ModelFileManager.deleteTempDownload(applicationContext, model)
        showDownloadFailedNotification(model, message)
        return Result.failure(workDataOf(KEY_ERROR_MESSAGE to message))
    }

    private fun showDownloadCompletedNotification(model: ModelFileManager.LocalModel, sizeBytes: Long) {
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
            .notify(1000 + model.ordinal, notification)
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
}
