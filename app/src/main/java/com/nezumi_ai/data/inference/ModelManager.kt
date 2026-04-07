package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AIモデルの管理クラス
 * - モデルのロード/アンロード
 * - バージョン管理
 * - キャッシング管理
 */
class ModelManager(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "ModelManager"
        private const val DEFAULT_MODEL_NAME = "gemma-3.2:e2b"
        private var instance: ModelManager? = null
        private val mutex = Mutex()
        
        suspend fun getInstance(context: Context): ModelManager {
            return instance ?: mutex.withLock {
                instance ?: ModelManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val inferenceEngine: AIInferenceEngine = LiteRtLmEngine(context)
    private var currentModelName: String? = null
    private var currentConfig: InferenceConfig? = null
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    
    /**
     * メモリ使用率をチェック（外部からアクセス可能）
     * @return メモリ使用率（0-100）
     */
    fun getMemoryUsagePercent(): Int {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        return if (maxMemory > 0) {
            ((usedMemory * 100) / maxMemory).toInt()
        } else {
            0
        }
    }
    
    /**
     * メモリが十分かチェック（外部からアクセス可能）
     * @return true: メモリに余裕あり / false: メモリ埋まりすぎ
     */
    fun isMemorySufficient(): Boolean {
        val usage = getMemoryUsagePercent()
        val isSufficient = usage < 85  // 85%以上の使用率でロード拒否
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMB = runtime.maxMemory() / (1024 * 1024)
        
        Log.d(TAG, "Memory check: ${usedMB}MB / ${maxMB}MB (${usage}%)")
        
        if (!isSufficient) {
            Log.w(TAG, "Memory usage is too high ($usage%) - refusing model load")
        }
        
        return isSufficient
    }
/**
     * モデルを初期化（ロード）
     */
    suspend fun initializeModel(
        modelName: String = DEFAULT_MODEL_NAME,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit> {
        return loadMutex.withLock {
            try {
                val normalizedConfig = config.normalized()
                // 既に同じモデルがロードされている場合はスキップ
                val shouldSkip = currentModelName == modelName && 
                    currentConfig == normalizedConfig &&
                    currentConfig?.backendType == normalizedConfig.backendType

                if (shouldSkip) {
                    Log.d(TAG, "Model $modelName is already loaded with same backend: ${normalizedConfig.backendType}")
                    return Result.success(Unit)
                }
                
                // メモリ使用率をチェック
                if (!isMemorySufficient()) {
                    val errorMsg = "Cannot load model - memory usage is too high (${getMemoryUsagePercent()}%)"
                    Log.e(TAG, errorMsg)
                    return Result.failure(RuntimeException(errorMsg))
                }
                
                // 前のモデルをアンロード
                if (currentModelName != null) {
                    Log.d(TAG, "Unloading previous model before loading new one (backend change: ${currentConfig?.backendType} -> ${normalizedConfig.backendType})")
                    inferenceEngine.unloadModel()
                }
                
                // 新しいモデルをロード
                Log.d(TAG, "Loading model: $modelName with backend: ${normalizedConfig.backendType}")
                val result = inferenceEngine.loadModel(modelName, normalizedConfig)
                
                if (result.isSuccess) {
                    currentModelName = modelName
                    currentConfig = normalizedConfig
                    Log.d(TAG, "Model loaded successfully: $modelName with backend: ${normalizedConfig.backendType}")
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "Failed to load model: $modelName. Reason: ${error?.message}", error)
                }
                
                result
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error during model initialization", e)
                Result.failure(e)
            }
        }
    }

    suspend fun initializeModelIfAvailable(
        modelName: String = DEFAULT_MODEL_NAME,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit> {
        if (!ModelFileManager.isModelAvailable(context, modelName)) {
            Log.d(TAG, "Skip model load (not downloaded): $modelName")
            return Result.success(Unit)
        }
        return initializeModel(modelName, config)
    }
    
    /**
     * 推論を実行
     */
    suspend fun runInference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            emitAll(inferenceEngine.inference(sessionId, prompt, config))
        }
    }
/**
 * マルチモーダル推論を実行（画像・音声対応）
 */
suspend fun runInferenceWithMedia(
    sessionId: Long,
    prompt: String,
    images: List<Bitmap> = emptyList(),
    audioClips: List<ByteArray> = emptyList(),
    config: InferenceConfig
): Flow<String> = flow {
    inferenceMutex.withLock {
        emitAll(inferenceEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, config))
    }
}
    
    /**
     * モデルが利用可能かチェック
     */
    suspend fun isModelAvailable(): Boolean {
        return inferenceEngine.isAvailable()
    }
    
    /**
     * モデルをアンロード
     */
    suspend fun unloadModel(): Result<Unit> {
        return loadMutex.withLock {
            try {
                val result = inferenceEngine.unloadModel()
                currentModelName = null
                currentConfig = null
                result
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error during model unload", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 現在のモデル名を取得
     */
    fun getCurrentModelName(): String? = currentModelName

    /**
     * 推論をキャンセル（Gallery方式：cancelProcess() のみ、KV cache は保持）
     */
    suspend fun cancelInference() {
        try {
            inferenceEngine.cancelInference()
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference cancellation", e)
        }
    }
}
