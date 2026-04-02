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
    
    private val inferenceEngine: AIInferenceEngine = GemmaE2BEngine(context)
    private var currentModelName: String? = null
    private var currentConfig: InferenceConfig? = null
    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()
    
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
                if (currentModelName == modelName && currentConfig == normalizedConfig) {
                    Log.d(TAG, "Model $modelName is already loaded")
                    return Result.success(Unit)
                }
                
                // 前のモデルをアンロード
                if (currentModelName != null) {
                    inferenceEngine.unloadModel()
                }
                
                // 新しいモデルをロード
                Log.d(TAG, "Loading model: $modelName")
                val result = inferenceEngine.loadModel(modelName, normalizedConfig)
                
                if (result.isSuccess) {
                    currentModelName = modelName
                    currentConfig = normalizedConfig
                    Log.d(TAG, "Model loaded successfully: $modelName")
                } else {
                    Log.e(TAG, "Failed to load model: $modelName")
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
        temperature: Float = 0.7f
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            emitAll(inferenceEngine.inference(sessionId, prompt, temperature))
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
        temperature: Float = 0.7f
    ): Flow<String> = flow {
        inferenceMutex.withLock {
            emitAll(inferenceEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, temperature))
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
}
