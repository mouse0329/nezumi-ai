package com.nezumi_ai.data.inference

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString(KEY_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model is missing"))
        val model = modelFromName(modelName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown model"))
        setForeground(createForegroundInfo(modelName, 0L, -1L))

        return try {
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L
            var hasBaseline = false

            val result = ModelFileManager.ensureDownloaded(applicationContext, model) { downloaded, total ->
                val currentTime = System.currentTimeMillis()
                val timeDeltaMs = currentTime - lastTime

                if (!hasBaseline) {
                    hasBaseline = true
                    lastTime = currentTime
                    lastDownloaded = downloaded
                    setProgressAsync(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to downloaded,
                            KEY_TOTAL_BYTES to total,
                            KEY_SPEED_MBPS to 0.0,
                            KEY_ESTIMATED_REMAINING_SEC to 0.0
                        )
                    )
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

                setProgressAsync(
                    workDataOf(
                        KEY_DOWNLOADED_BYTES to downloaded,
                        KEY_TOTAL_BYTES to total,
                        KEY_SPEED_MBPS to speedMbps,
                        KEY_ESTIMATED_REMAINING_SEC to estimatedSecRemaining
                    )
                )
                setForegroundAsync(createForegroundInfo(modelName, downloaded, total))
                
                if (timeDeltaMs > 500) { // 0.5秒ごとに更新
                    lastTime = currentTime
                    lastDownloaded = downloaded
                }
            }

            result.fold(
                onSuccess = {
                    Result.success(
                        workDataOf(
                            KEY_DOWNLOADED_BYTES to it.length(),
                            KEY_TOTAL_BYTES to it.length(),
                            KEY_SPEED_MBPS to 0.0
                        )
                    )
                },
                onFailure = { e ->
                    if (shouldRetry(e)) {
                        Result.retry()
                    } else {
                        Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "download failed")))
                    }
                }
            )
        } catch (e: Exception) {
            if (shouldRetry(e)) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "download failed")))
            }
        }
    }

    private fun shouldRetry(error: Throwable): Boolean {
        if (runAttemptCount >= MAX_WORK_RETRY) return false
        val message = error.message.orEmpty().lowercase()
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

        return ForegroundInfo(NOTIFICATION_ID, notification)
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
        private const val MAX_WORK_RETRY = 5

        fun modelWorkName(model: ModelFileManager.LocalModel): String =
            "model_download_${model.name.lowercase()}"

        fun enqueue(context: Context, model: ModelFileManager.LocalModel) {
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
            WorkManager.getInstance(context).enqueueUniqueWork(
                modelWorkName(model),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, model: ModelFileManager.LocalModel) {
            WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
            ModelFileManager.deleteTempDownload(context, model)
        }

        private fun modelFromName(name: String): ModelFileManager.LocalModel? {
            return when (name.uppercase()) {
                ModelFileManager.LocalModel.E2B.name -> ModelFileManager.LocalModel.E2B
                ModelFileManager.LocalModel.E4B.name -> ModelFileManager.LocalModel.E4B
                else -> null
            }
        }
    }
}
