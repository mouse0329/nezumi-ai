package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Base64
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.google.common.util.concurrent.MoreExecutors
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemma推論エンジン実装（litertlm - マルチモーダル対応）
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
            val progressListener = ProgressListener<String> { partial, done ->
                if (!done && partial.isNotEmpty()) {
                    trySend(partial)
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

    /**
     * マルチモーダル推論（テキストベース）
     * 画像・音声メタデータをテキスト記述として Gemma に渡す
     */
    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
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
            Log.d(TAG, "Starting multimodal inference for session $sessionId (${images.size} images, ${audioClips.size} audio)")

            val augmentedPrompt = buildMultimodalPrompt(prompt, images, audioClips)
            Log.d(TAG, "Augmented prompt length: ${augmentedPrompt.length} chars")

            val progressListener = ProgressListener<String> { partialResult, done ->
                if (!done && partialResult.isNotEmpty()) {
                    try {
                        trySend(partialResult)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending token: ${e.message}")
                    }
                }
            }

            val future = engine.generateResponseAsync(augmentedPrompt, progressListener)

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
            Log.e(TAG, "Multimodal inference failed for session $sessionId", e)
            close(e)
        }
    }

    private fun buildMultimodalPrompt(
        originalPrompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>
    ): String {
        val prompt = StringBuilder()
        
        if (images.isNotEmpty()) {
            prompt.append("【画像情報】\n")
            images.forEachIndexed { index, bitmap ->
                try {
                    val width = bitmap.width
                    val height = bitmap.height
                    val aspectRatio = String.format("%.2f", width.toFloat() / height.toFloat())
                    
                    prompt.append("画像${index + 1}:\n")
                    prompt.append("  寸法: ${width}x${height}px (アスペクト比: $aspectRatio)\n")
                    prompt.append("  形式: PNG/JPEG互換\n")
                    Log.d(TAG, "Added image ${index + 1} metadata: ${width}x${height}px")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to analyze image ${index + 1}", e)
                }
            }
            prompt.append("\n")
        }

        if (audioClips.isNotEmpty()) {
            prompt.append("【音声情報】\n")
            audioClips.forEachIndexed { index, audioBytes ->
                try {
                    if (audioBytes.isNotEmpty()) {
                        val fileSizeKB = audioBytes.size / 1024
                        val estimatedDurationSec = (audioBytes.size.toFloat() / 44100.0 / 2).toInt()
                        
                        prompt.append("音声${index + 1}:\n")
                        prompt.append("  サイズ: ${fileSizeKB}KB\n")
                        prompt.append("  推定再生時間: ${estimatedDurationSec}秒\n")
                        prompt.append("  形式: WAV/MP3互換\n")
                        Log.d(TAG, "Added audio ${index + 1} metadata: ${fileSizeKB}KB")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to analyze audio ${index + 1}", e)
                }
            }
            prompt.append("\n")
        }
        
        prompt.append("ユーザーからのリクエスト:\n")
        prompt.append(originalPrompt)

        return prompt.toString()
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
