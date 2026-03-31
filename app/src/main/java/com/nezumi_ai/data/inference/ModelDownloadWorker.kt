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

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val modelName = inputData.getString(KEY_MODEL_NAME)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "model is missing"))
        val model = modelFromName(modelName)
            ?: return Result.failure(workDataOf(KEY_ERROR_MESSAGE to "unknown model"))

        val result = ModelFileManager.ensureDownloaded(applicationContext, model) { downloaded, total ->
            setProgressAsync(
                workDataOf(
                    KEY_DOWNLOADED_BYTES to downloaded,
                    KEY_TOTAL_BYTES to total
                )
            )
        }

        return result.fold(
            onSuccess = {
                Result.success(
                    workDataOf(
                        KEY_DOWNLOADED_BYTES to it.length(),
                        KEY_TOTAL_BYTES to it.length()
                    )
                )
            },
            onFailure = { e ->
                Result.failure(workDataOf(KEY_ERROR_MESSAGE to (e.message ?: "download failed")))
            }
        )
    }

    companion object {
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_ERROR_MESSAGE = "error_message"

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
