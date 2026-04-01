package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    
    enum class BackendType {
        CPU,
        GPU
    }
    
    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return try {
            modelMutex.withLock {
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
            val isImportedTask = modelPath.startsWith(appContext.filesDir.absolutePath + "/models/imported/")
            val preferredBackend = when (normalizedConfig.backendType.uppercase()) {
                "GPU" -> LlmInference.Backend.GPU
                "NPU" -> {
                    Log.w(TAG, "NPU backend is not supported by current LlmInference. Falling back to CPU.")
                    LlmInference.Backend.CPU
                }
                else -> LlmInference.Backend.CPU
            }
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(normalizedConfig.maxTokens)
                .setMaxTopK(normalizedConfig.maxTopK)
                .setPreferredBackend(preferredBackend)
                .build()

            llmInference = runCatching {
                LlmInference.createFromOptions(appContext, options)
            }.getOrElse { firstError ->
                if (isImportedTask && preferredBackend == LlmInference.Backend.GPU) {
                    Log.w(TAG, "Imported .task failed on GPU. Retrying with CPU backend.", firstError)
                    val fallback = LlmInference.LlmInferenceOptions.builder()
                        .setModelPath(modelPath)
                        .setMaxTokens(normalizedConfig.maxTokens)
                        .setMaxTopK(normalizedConfig.maxTopK)
                        .setPreferredBackend(LlmInference.Backend.CPU)
                        .build()
                    LlmInference.createFromOptions(appContext, fallback)
                } else {
                    throw firstError
                }
            }
            loadedModelPath = modelPath
            loadedConfig = normalizedConfig
            Log.d(TAG, "Model loaded successfully")
            Result.success(Unit)
            }
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
        inferenceMutex.lock()
        var mutexReleased = false
        fun releaseInferenceMutex() {
            if (!mutexReleased) {
                mutexReleased = true
                inferenceMutex.unlock()
            }
        }

        val engine = modelMutex.withLock { llmInference }
        if (engine == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }
        
        try {
            Log.d(TAG, "Starting inference for session $sessionId")
            val progressListener = ProgressListener<String> { partial, _ ->
                if (partial.isNotEmpty()) {
                    trySend(partial).isSuccess
                }
            }

            val future = engine.generateResponseAsync(prompt, progressListener)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { finalResult ->
                            trySend(InferenceStreamProtocol.encodeFinal(finalResult)).isSuccess
                            Log.d(TAG, "Inference completed for session $sessionId")
                            releaseInferenceMutex()
                            close()
                        }
                        .onFailure { t ->
                            val e = if (t is Exception) t else RuntimeException(t)
                            Log.e(TAG, "Inference failed for session $sessionId", e)
                            releaseInferenceMutex()
                            close(e)
                        }
                },
                MoreExecutors.directExecutor()
            )

            awaitClose {
                if (!future.isDone) {
                    future.cancel(true)
                }
                releaseInferenceMutex()
            }
        } catch (t: Throwable) {
            releaseInferenceMutex()
            if (t is CancellationException) {
                close(t)
                return@callbackFlow
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Inference failed for session $sessionId", e)
            close(e)
        }
    }
    
    override suspend fun unloadModel(): Result<Unit> {
        return try {
            modelMutex.withLock {
            Log.d(TAG, "Unloading model")
            llmInference?.close()
            llmInference = null
            loadedModelPath = null
            loadedConfig = null
            Result.success(Unit)
            }
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
        val lowered = modelName.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && modelName.startsWith("/")) {
            return modelName
        }
        return when (ModelFileManager.resolveModelName(modelName)) {
            ModelFileManager.LocalModel.E4B -> "gemma-3n-e4b.task"
            ModelFileManager.LocalModel.E2B -> "gemma-3n-e2b.task"
        }
    }

    private fun resolveLocalModelFile(modelName: String): File? {
        val resolved = resolveModelPath(modelName)
        val lowered = resolved.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && resolved.startsWith("/")) {
            val file = File(resolved)
            val validated = ModelFileManager.validateImportedTaskFile(file)
            if (validated.isFailure) {
                Log.w(TAG, "Imported task validation failed: ${validated.exceptionOrNull()?.message}")
                return null
            }
            return validated.getOrNull()
        }

        val localModel = ModelFileManager.resolveModelName(modelName)
        val verified = ModelFileManager.validatedModelFileForLoad(appContext, localModel)
        if (verified.isFailure) {
            Log.w(TAG, "Local model integrity check failed: ${verified.exceptionOrNull()?.message}")
            return null
        }
        return verified.getOrNull()
    }
}
