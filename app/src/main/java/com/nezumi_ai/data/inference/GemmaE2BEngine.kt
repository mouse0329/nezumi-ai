package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.AudioModelOptions
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
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
 * Gemma推論エンジン実装（litertlm - マルチモーダル対応）
 */
class GemmaE2BEngine(
    private val appContext: Context
) : AIInferenceEngine {
    
    companion object {
        private const val TAG = "GemmaE2BEngine"
        private const val MAX_VISION_IMAGES = 5
        private const val MAX_BITMAP_EDGE = 1024
    }
    
    private var llmInference: LlmInference? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: InferenceConfig? = null
    private var loadedBackendType: String? = null
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
val currentBackendType = normalizedConfig.backendType.uppercase()

val needsReload = loadedModelPath != modelPath || 
                  loadedBackendType != currentBackendType ||
                  loadedConfig != normalizedConfig ||
                  llmInference == null

if (!needsReload) {
    Log.d(TAG, "Model already loaded: $modelPath with backend $currentBackendType")
    return Result.success(Unit)
}

Log.d(TAG, "Unloading previous model (backend was: $loadedBackendType, new: $currentBackendType)")
llmInference?.close()
llmInference = null
loadedModelPath = null
loadedConfig = null
loadedBackendType = null

System.gc()

if (loadedBackendType == "GPU" || currentBackendType == "GPU") {
    Log.d(TAG, "Clearing cache for GPU transition")
    clearGPUCache()
} else {
    // GPU以外はTFLiteキャッシュをバリデート
    val modelFileBaseName = modelFile.nameWithoutExtension
    val cacheValid = CacheManager.validateAndRecoverCacheFiles(appContext, modelFileBaseName)
    if (!cacheValid) {
        Log.i(TAG, "Cache was incomplete and has been deleted. TFLite will regenerate on next load.")
    }
}

Log.d(TAG, "Loading model from path: $modelPath with backend: $currentBackendType")
val isImportedTask = modelPath.startsWith(appContext.filesDir.absolutePath + "/models/imported/")
val preferredBackend = when (currentBackendType) {
    "GPU" -> LlmInference.Backend.GPU
    "NPU" -> {
        Log.w(TAG, "NPU backend is not supported by current LlmInference. Falling back to CPU.")
        LlmInference.Backend.CPU
                }
                
                // GPU時は設定ファイルのキャッシュも全削除してメモリ不足を防ぐ
                val preferredBackend = when (normalizedConfig.backendType.uppercase()) {
                    "GPU" -> {
                        Log.i(TAG, "GPU backend: Deleting cached GPU compilation files to prevent OOM during subgraph compilation")
                        CacheManager.deleteAllModelCacheFiles(appContext, modelFileBaseName)
                        LlmInference.Backend.GPU
                    }
                    "NPU" -> {
                        Log.w(TAG, "NPU backend requested but not available in current MediaPipe version (0.10.27). Falling back to CPU.")
                        LlmInference.Backend.CPU
                    }
                    else -> LlmInference.Backend.CPU
                }
llmInference = runCatching {
    createLlmInferenceWithMultimodalFallbacks(
        modelPath = modelPath,
        normalizedConfig = normalizedConfig,
        preferredBackend = preferredBackend
    )
}.getOrElse { firstError ->
    if (isImportedTask && preferredBackend == LlmInference.Backend.GPU) {
        Log.w(TAG, "Imported .task failed on GPU. Retrying with CPU backend.", firstError)
        createLlmInferenceWithMultimodalFallbacks(
            modelPath = modelPath,
            normalizedConfig = normalizedConfig,
            preferredBackend = LlmInference.Backend.CPU
        )
    } else {
        throw firstError
    }
}
loadedModelPath = modelPath
loadedConfig = normalizedConfig
loadedBackendType = currentBackendType  // mainから追加

// GPU以外はキャッシュ管理
if (currentBackendType != "GPU") {
    CacheManager.updateRecentCacheFiles(appContext)
    CacheManager.cleanupCacheIfNeeded(appContext, modelFileBaseName, forceScan = false)
}

Log.d(TAG, "Model loaded successfully with backend: $currentBackendType")
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
     * マルチモーダル推論（LlmInferenceSession + MPImage / mono 16-bit WAV）
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

        val wavChunks = audioClips.mapNotNull { raw ->
            if (raw.isEmpty()) return@mapNotNull null
            LlmMultimodalAudioHelper.toMono16Bit16kHzWav(appContext, raw)
                ?: run {
                    Log.w(TAG, "Audio chunk skipped (decode failed or unsupported format)")
                    null
                }
        }

        val topK = modelMutex.withLock { loadedConfig?.maxTopK ?: 40 }
        
        // 音声チャンクが存在し、かつエンジンが正しく初期化されている場合のみ音声を有効化
        val enableAudio = wavChunks.isNotEmpty()
        
        val graph = GraphOptions.builder()
            .setEnableVisionModality(images.isNotEmpty())
            .setEnableAudioModality(enableAudio)
            .build()
        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(topK)
            .setTemperature(temperature)
            .setGraphOptions(graph)
            .build()

        val session = try {
            LlmInferenceSession.createFromOptions(engine, sessionOptions)
        } catch (t: Throwable) {
            releaseInferenceMutex()
            if (t is CancellationException) {
                close(t)
                return@callbackFlow
            }
            val e = if (t is Exception) t else RuntimeException(t)
            close(e)
            return@callbackFlow
        }

        try {
            Log.d(
                TAG,
                "Multimodal inference session $sessionId: images=${images.size}, wav=${wavChunks.size}/${audioClips.size}"
            )
            session.addQueryChunk(prompt)
            for (bitmap in images) {
                val scaled = scaleBitmapForVision(bitmap)
                try {
                    session.addImage(BitmapImageBuilder(scaled).build())
                } finally {
                    if (scaled !== bitmap) {
                        scaled.recycle()
                    }
                }
            }
            for (wav in wavChunks) {
                session.addAudio(wav)
            }

            val progressListener = ProgressListener<String> { partial, done ->
                if (!done && partial.isNotEmpty()) {
                    trySend(partial)
                }
            }

            val future = session.generateResponseAsync(progressListener)
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { finalResult ->
                            trySend(InferenceStreamProtocol.encodeFinal(finalResult)).isSuccess
                            Log.d(TAG, "Multimodal inference completed for session $sessionId")
                            releaseInferenceMutex()
                            close()
                        }
                        .onFailure { t ->
                            val e = if (t is Exception) t else RuntimeException(t)
                            Log.e(TAG, "Multimodal inference failed for session $sessionId", e)
                            releaseInferenceMutex()
                            close(e)
                        }
                },
                MoreExecutors.directExecutor()
            )

            awaitClose {
                if (!future.isDone) {
                    runCatching { session.cancelGenerateResponseAsync() }
                    runCatching { session.close() }  // キャンセル時のみclose
                }
                // future.isDone == true（推論完了）の場合はcloseをスキップ
                // MediaPipeが既にセッションを破棄しているため、再度closeするとSIGABRTが発生
                releaseInferenceMutex()
            }
        } catch (t: Throwable) {
            runCatching { session.close() }
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

    override suspend fun unloadModel(): Result<Unit> {
        return try {
            modelMutex.withLock {
            Log.d(TAG, "Unloading model")
            // GPU使用中だった場合、メモリをクリア
            if (loadedBackendType == "GPU") {
                Log.d(TAG, "Clearing GPU cache during unload")
                clearGPUCache()
            }
            llmInference?.close()
            llmInference = null
            loadedModelPath = null
            loadedConfig = null
            loadedBackendType = null
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
private fun createLlmInferenceWithMultimodalFallbacks(
    modelPath: String,
    normalizedConfig: InferenceConfig,
    preferredBackend: LlmInference.Backend
): LlmInference {
    fun baseBuilder() = LlmInference.LlmInferenceOptions.builder()
        .setModelPath(modelPath)
        .setMaxTokens(normalizedConfig.maxTokens)
        .setMaxTopK(normalizedConfig.maxTopK)
        .setPreferredBackend(preferredBackend)
    runCatching {
        return LlmInference.createFromOptions(
            appContext,
            baseBuilder()
                .setMaxNumImages(MAX_VISION_IMAGES)
                .build()
        )
    }.onFailure {
        Log.w(TAG, "LlmInference init (vision-only) failed, retrying with text defaults", it)
    }
    return LlmInference.createFromOptions(appContext, baseBuilder().build())
}

private fun scaleBitmapForVision(bitmap: Bitmap): Bitmap {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= MAX_BITMAP_EDGE && h <= MAX_BITMAP_EDGE) return bitmap
    val scale = minOf(MAX_BITMAP_EDGE.toFloat() / w, MAX_BITMAP_EDGE.toFloat() / h)
    val nw = (w * scale).toInt().coerceAtLeast(1)
    val nh = (h * scale).toInt().coerceAtLeast(1)
    return try {
        Bitmap.createScaledBitmap(bitmap, nw, nh, true)
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "OOM while scaling bitmap, trying smaller size", e)
        val smallerScale = scale * 0.75f
        Bitmap.createScaledBitmap(bitmap, (w * smallerScale).toInt().coerceAtLeast(1), (h * smallerScale).toInt().coerceAtLeast(1), true)
    }
}

private fun clearGPUCache() {
    try {
        Log.d(TAG, "Explicitly clearing GPU memory cache")
        System.gc()
        Runtime.getRuntime().gc()
        Log.d(TAG, "GPU cache cleared")
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to clear GPU cache", t)
    }
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
