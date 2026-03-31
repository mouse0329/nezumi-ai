package com.nezumi_ai.data.inference

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString(KEY_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model is missing"))
        val model = modelFromName(modelName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown model"))

        return try {
            val startTime = System.currentTimeMillis()
            var lastTime = startTime
            var lastDownloaded = 0L

            val result = ModelFileManager.ensureDownloaded(applicationContext, model) { downloaded, total ->
                val currentTime = System.currentTimeMillis()
                val timeDeltaMs = currentTime - lastTime
                
                // スピード計算（MB/s）
                val speedMbps = if (timeDeltaMs > 0) {
                    val bytesDelta = downloaded - lastDownloaded
                    (bytesDelta.toDouble() / (1024.0 * 1024.0)) / (timeDeltaMs.toDouble() / 1000.0)
                } else {
                    0.0
                }
                
                // 推定残り時間（秒）
                val remainingBytes = total - downloaded
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
                    Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "download failed")))
                }
            )
        } finally {
            // キャンセル時に一時ファイルをクリーンアップ
            cleanupTempFiles(modelName)
        }
    }

    private fun cleanupTempFiles(modelNameStr: String?) {
        val modelName = modelNameStr ?: return
        val model = modelFromName(modelName) ?: return
        
        val modelFile = ModelFileManager.modelFile(applicationContext, model)
        val tmpFile = File("${modelFile.absolutePath}.download")
        
        if (tmpFile.exists()) {
            val deleted = tmpFile.delete()
            android.util.Log.d("ModelDownloadWorker", "Cleanup temp file: $deleted (${tmpFile.absolutePath})")
        }
    }

    companion object {
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_MESSAGE = "error_message"
        const val KEY_SPEED_MBPS = "speed_mbps"
        const val KEY_ESTIMATED_REMAINING_SEC = "estimated_remaining_sec"

        fun modelWorkName(model: ModelFileManager.LocalModel): String =
            "model_download_${model.name.lowercase()}"

        fun enqueue(context: Context, model: ModelFileManager.LocalModel) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(workDataOf(KEY_MODEL_NAME to model.name))
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                modelWorkName(model),
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context, model: ModelFileManager.LocalModel) {
            WorkManager.getInstance(context).cancelUniqueWork(modelWorkName(model))
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
