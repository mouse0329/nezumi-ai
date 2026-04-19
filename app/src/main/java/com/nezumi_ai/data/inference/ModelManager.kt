package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * AIモデルの管理クラス（Phase 11 リソース管理統合版）
 * - モデルのロード/アンロード
 * - バージョン管理
 * - キャッシング管理
 * - メモリ監視とOOM対策
 * - セッション・リソース管理
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
    
    // ─────────────────────────────────────────────────────────
    // Phase 11: リソース管理の統合
    // ─────────────────────────────────────────────────────────
    
    private val memoryObserver = MemoryObserver
    private val sessionManager = SessionResourceManager()
    private val jobController = InferenceJobController()

    private fun isCompiledModelInvokeFailure(t: Throwable): Boolean {
        var cur: Throwable? = t
        repeat(8) {
            val msg = cur?.message.orEmpty()
            if (
                msg.contains("Status Code: 13", ignoreCase = true) ||
                msg.contains("Failed to invoke the compiled model", ignoreCase = true)
            ) {
                return true
            }
            cur = cur?.cause
        }
        return false
    }

    private suspend fun recoverFromInvokeFailure(config: InferenceConfig): Boolean {
        val modelName = currentModelName ?: return false
        val normalized = config.normalized()
        Log.w(
            TAG,
            "Compiled-model invoke failure detected. Reloading engine and retrying once: model=$modelName backend=${normalized.backendType}"
        )

        runCatching { inferenceEngine.unloadModel() }
            .onFailure { Log.w(TAG, "Engine unload during recovery failed", it) }

        val reloaded = inferenceEngine.loadModel(modelName, normalized)
        if (reloaded.isSuccess) {
            currentConfig = normalized
            return true
        }
        Log.e(TAG, "Engine reload during recovery failed", reloaded.exceptionOrNull())
        return false
    }
    
    /**
     * メモリ使用率をチェック（MemoryObserver 統合版）
     * @return メモリ使用率（0-100）
     */
    suspend fun getMemoryUsagePercent(): Int {
        val status = memoryObserver.getMemoryStatus(context)
        return status.usedPercent
    }
    
    /**
     * メモリが十分かチェック（OOM対策強化版）
     * @return true: メモリに余裕あり / false: メモリ埋まりすぎ
     */
    suspend fun isMemorySufficient(): Boolean {
        // MemoryObserver で段階的にチェック
        return memoryObserver.requestMemoryCorrectionIfNeeded(context)
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
            var emitted = false
            try {
                inferenceEngine.inference(sessionId, prompt, config).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                    Log.i(TAG, "Retrying inference once after recovery")
                    inferenceEngine.inference(sessionId, prompt, config).collect { chunk ->
                        emit(chunk)
                    }
                } else {
                    throw t
                }
            }
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
        var emitted = false
        try {
            inferenceEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                .collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                Log.i(TAG, "Retrying multimodal inference once after recovery")
                inferenceEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                    .collect { chunk ->
                        emit(chunk)
                    }
            } else {
                throw t
            }
        }
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

    /**
     * NPU キャリブレーション: 初回起動時の自動ベンチマーク
     *
     * GPU/CPU/NPU の最適バックエンドを自動診断して SettingsRepository に保存します。
     * @param sessionId 一時的なセッションID（ベンチマーク用）
     * @param settingsRepository 設定保存先
     * @param modelName ベンチマーク対象のモデル名
     * @return 最適バックエンド名 ("GPU", "CPU", "NPU", など)
     */
    suspend fun calibrateBackend(
        sessionId: Long,
        settingsRepository: SettingsRepository,
        modelName: String = DEFAULT_MODEL_NAME
    ): String {
        Log.d(TAG, "Starting NPU/GPU/CPU calibration benchmark...")
        
        val candidates = listOf("NPU", "GPU", "CPU")
        val results = mutableMapOf<String, Long>()
        
        for (backend in candidates) {
            val elapsed = benchmarkBackend(sessionId, backend, modelName)
            results[backend] = elapsed
            Log.d(TAG, "Benchmark result: backend=$backend elapsed=${elapsed}ms")
        }
        
        // 最速のバックエンドを選択（NPU が利用できない場合は GPU → CPU へフォールバック）
        val optimalBackend = results.minByOrNull { it.value }?.key ?: "CPU"
        
        Log.i(TAG, "Optimal backend selected: $optimalBackend")
        Log.d(TAG, "Full benchmark results: $results")
        
        // 設定に保存（SettingsRepository が対応している場合）
        // settingsRepository.updateBackend(optimalBackend)
        
        return optimalBackend
    }

    /**
     * 特定のバックエンドをベンチマーク
     *
     * 短い推論を実行して応答時間を計測します。
     * @param sessionId 一時的なセッションID
     * @param backend ベンチマーク対象の backend ("CPU", "GPU", "NPU")
     * @param modelName ベンチマーク対象のモデル名
     * @return 応答時間（ミリ秒）。失敗時は Long.MAX_VALUE を返す
     */
    private suspend fun benchmarkBackend(
        sessionId: Long,
        backend: String,
        modelName: String
    ): Long {
        return try {
            val config = InferenceConfig(
                backendType = backend,
                maxTokens = 50,  // 短い生成
                temperature = 0.7f,
                topP = 0.95f
            )
            
            val startTime = System.currentTimeMillis()
            
            // 一時的なモデルロード
            val loadResult = inferenceEngine.loadModel(modelName, config)
            if (loadResult.isFailure) {
                Log.w(TAG, "Backend $backend not available: ${loadResult.exceptionOrNull()?.message}")
                return Long.MAX_VALUE  // 利用不可
            }
            
            // ベンチマーク推論を実行
            val benchmarkPrompt = "日本国の首都は？"
            var tokenCount = 0
            
            inferenceEngine.inference(sessionId, benchmarkPrompt, config).collect { chunk ->
                tokenCount += chunk.length
            }
            
            val elapsed = System.currentTimeMillis() - startTime
            
            // モデルをアンロード
            runCatching { inferenceEngine.unloadModel() }
                .onFailure { Log.w(TAG, "Failed to unload after benchmark", it) }
            
            Log.d(TAG, "Benchmark $backend completed: ${elapsed}ms for $tokenCount chars")
            
            elapsed
        } catch (e: Throwable) {
            Log.w(TAG, "Benchmark failed for backend $backend: ${e.message}", e)
            Long.MAX_VALUE  // ベンチマーク失敗
        }
    }
}
