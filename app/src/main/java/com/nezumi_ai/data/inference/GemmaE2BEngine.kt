package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Gemma推論エンジン実装（MediaPipe LLM Inference）
 */
class GemmaE2BEngine(
    private val appContext: Context
) : AIInferenceEngine {
    
    companion object {
        private const val TAG = "GemmaE2BEngine"
    }
    
    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: InferenceConfig? = null
    
    enum class BackendType {
        CPU,
        GPU
    }
    
    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return try {
            val normalizedConfig = config.normalized()
            val modelFile = resolveLocalModelFile(modelName)
            if (modelFile == null || !modelFile.exists()) {
                return Result.failure(IllegalStateException("Model file is not available"))
            }

            val modelPath = modelFile.absolutePath
            if (loadedModelPath == modelPath && loadedConfig == normalizedConfig && llmInference != null) {
                Log.d(TAG, "Model already loaded: $modelPath")
                return Result.success(Unit)
            }

            llmInference?.close()
            llmInference = null

            Log.d(TAG, "Loading model from path: $modelPath")
            val preferredBackend = when (normalizedConfig.backendType.uppercase()) {
                "GPU" -> LlmInference.Backend.GPU
                else -> LlmInference.Backend.CPU
            }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(normalizedConfig.maxTokens)
                .setMaxTopK(normalizedConfig.maxTopK)
                .setPreferredBackend(preferredBackend)
                .build()

            llmInference = LlmInference.createFromOptions(appContext, options)
            loadedModelPath = modelPath
            loadedConfig = normalizedConfig
            Log.d(TAG, "Model loaded successfully")
            Result.success(Unit)
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }
    
    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        temperature: Float
    ): Flow<String> = callbackFlow {
        val engine = llmInference
        if (engine == null) {
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }
        
        try {
            Log.d(TAG, "Starting inference for session $sessionId")
            var lastPartial = ""
            val progressListener = ProgressListener<String> { partial, _ ->
                val delta = if (partial.startsWith(lastPartial)) {
                    partial.removePrefix(lastPartial)
                } else {
                    partial
                }
                lastPartial = partial
                if (delta.isNotEmpty()) {
                    trySend(delta).isSuccess
                }
            }

            val future = engine.generateResponseAsync(prompt, progressListener)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { finalResult ->
                            trySend(InferenceStreamProtocol.encodeFinal(finalResult)).isSuccess
                            Log.d(TAG, "Inference completed for session $sessionId")
                            close()
                        }
                        .onFailure { t ->
                            val e = if (t is Exception) t else RuntimeException(t)
                            Log.e(TAG, "Inference failed for session $sessionId", e)
                            close(e)
                        }
                },
                MoreExecutors.directExecutor()
            )

            awaitClose {
                if (!future.isDone) {
                    future.cancel(true)
                }
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Inference failed for session $sessionId", e)
            close(e)
        }
    }
    
    override suspend fun unloadModel(): Result<Unit> {
        return try {
            Log.d(TAG, "Unloading model")
            llmInference?.close()
            llmInference = null
            loadedModelPath = null
            loadedConfig = null
            Result.success(Unit)
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to unload model", e)
            Result.failure(e)
        }
    }
    
    override suspend fun isAvailable(): Boolean {
        return true
    }
    
    private fun resolveModelPath(modelName: String): String {
        if ((modelName.endsWith(".task") || modelName.endsWith(".litertlm")) && modelName.startsWith("/")) {
            return modelName
        }
        return when (ModelFileManager.resolveModelName(modelName)) {
            ModelFileManager.LocalModel.E4B -> "gemma-3n-e4b.task"
            ModelFileManager.LocalModel.E2B -> "gemma-3n-e2b.task"
        }
    }

    private fun resolveLocalModelFile(modelName: String): File? {
        val resolved = resolveModelPath(modelName)
        if ((resolved.endsWith(".task") || resolved.endsWith(".litertlm")) && resolved.startsWith("/")) {
            val file = File(resolved)
            return if (file.exists()) file else null
        }

        val localModel = ModelFileManager.resolveModelName(modelName)
        val localFile = ModelFileManager.modelFile(appContext, localModel)
        return if (localFile.exists()) localFile else null
    }
}
