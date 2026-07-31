package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.rnllama.RnLlamaNative
import com.nezumi_ai.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    
    // Phase 15: LiteRtLm と GGUF エンジンの両方を搭載（モデルごとに切替）
    private val liteRtEngine: AIInferenceEngine = LiteRtLmEngine(context)

    /**
     * Bug fix(#5): LiteRT エンジンへ「このセッションは media を含む」と伝えるための
     * アクセサ。 LiteRtLmEngine 型を露出させ、可変フラグの設定をしてもらう。
     * GGUF モードやエンジン未初期化時は null。
     */
    fun liteRtEngineForMultiTurnMedia(): LiteRtLmEngine? =
        liteRtEngine as? LiteRtLmEngine
    /** GGUF は初回利用時まで遅延初期化し、nezumi_rnllama_jni を起動直後にロードしない（SD ggml と共存しやすくする） */
    private var ggufEngine: GgufInferenceEngine? = null

    @Volatile
    private var activeEngine: AIInferenceEngine = liteRtEngine
    
    private var currentModelName: String? = null
    private var currentConfig: InferenceConfig? = null
    private val loadMutex = Mutex()
    
    // ─────────────────────────────────────────────────────────
    // Phase 11: リソース管理の統合
    // ─────────────────────────────────────────────────────────
    
    private val memoryObserver = MemoryObserver
    internal val sessionManager = SessionResourceManager()
    private val jobController = InferenceJobController()

    private fun getOrCreateGgufEngine(): GgufInferenceEngine? {
        ggufEngine?.let { return it }
        synchronized(this) {
            ggufEngine?.let { return it }
            if (!RnLlamaNative.loadLibraryIfNeeded()) {
                Log.w(TAG, "GGUF native library unavailable")
                return null
            }
            if (!LlamaBridge.isLibraryLoaded()) {
                Log.w(TAG, "GGUF native bridge unavailable: llama_bridge not loaded")
                return null
            }
            return try {
                GgufInferenceEngine(context.applicationContext).also { ggufEngine = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to construct GgufInferenceEngine", e)
                null
            }
        }
    }

    private fun shouldUseGgufEngine(modelName: String): Boolean {
        val trimmed = modelName.trim()
        val lowered = trimmed.lowercase()
        return lowered.endsWith(".gguf") && java.io.File(trimmed).isAbsolute
    }

    private fun engineForModel(modelName: String): AIInferenceEngine {
        return if (shouldUseGgufEngine(modelName)) {
            getOrCreateGgufEngine()
                ?: throw IllegalStateException("GGUF engine unavailable: native library could not be loaded")
        } else {
            liteRtEngine
        }
    }

    private fun currentEngineLabel(engine: AIInferenceEngine): String {
        return if (engine is GgufInferenceEngine) "GGUF" else "LiteRtLm"
    }

    /**
 * もしロード済みの GGUF エンジンがあれば KV キャッシュをクリアする。
     * Thinking トグルやセッション切り替え時に前コンテキストを消して
     * Qwen 等の `/think` `/no_think` directive が正しく作用するようにする。
     */
    fun clearKvCache() {
        runCatching { ggufEngine?.clearKvCacheIfLoaded() }
            .onFailure { Log.w(TAG, "clearKvCache failed on GGUF engine", it) }
    }

    /**
 * 「次回推論開始前に KV を強制クリア」フラグをセットする。
     *
     * ユーザー停止直後は、GGUF ネイティブの KV キャッシュに途中までの assistant トークンが
     * 終端トークンなしで残ることがあり、それが次回推論で prompt prefix として
     * 混ざると "2.0.0 ..." のような壊れた出力になることがある。
     *
     * そこで、stopGeneration / revoke 時にこの API を呼んでおくと、次回の
     * GgufInferenceEngine.inferenceWithMedia 開始直後に一度だけ KV と lastSessionId を
     * リセットしてから生成を始める。LiteRT や未ロード時は何もしない。
     */
    fun requestForceClearBeforeNextInference() {
        runCatching { ggufEngine?.requestForceClearBeforeNextInference() }
            .onFailure { Log.w(TAG, "requestForceClearBeforeNextInference failed on GGUF engine", it) }
    }

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
            "Compiled-model invoke failure detected. Recovering model=$modelName backend=${normalized.backendType}"
        )

        val engine = activeEngine
        val isGpuBacked = normalized.backendType.equals("GPU", ignoreCase = true) ||
            normalized.backendType.equals("NPU", ignoreCase = true)

        if (isGpuBacked && engine is LiteRtLmEngine) {
            Log.w(TAG, "GPU/NPU backend: using forceReset() instead of unloadModel() to avoid SIGABRT")
            engine.forceReset()
        } else {
            runCatching { engine.unloadModel() }
                .onFailure { Log.w(TAG, "Engine unload during recovery failed", it) }
        }

        val reloaded = engine.loadModel(modelName, normalized)
        if (reloaded.isSuccess) {
            activeEngine = engine
            currentConfig = normalized
            Log.i(TAG, "Recovery reload succeeded")
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
 * バグ修正: 指定モデル・設定が既にロード済みかチェック
     * ChatViewModel.generateAIResponse で毎回ロード処理を呼ぶが、
     * 既にロード済みなら不要なメモリ警告を避ける目的
     *
     * @return true: 既にロード済み / false: 再ロード必要
     */
    private fun InferenceConfig.isCompatibleWithRequest(request: InferenceConfig): Boolean {
        if (!backendType.equals(request.backendType, ignoreCase = true)) return false
        if (llamaCppThreads != request.llamaCppThreads) return false
        if (llamaCppGpuLayers != request.llamaCppGpuLayers) return false
        if (llamaCppBatchSize != request.llamaCppBatchSize) return false
        if (llamaCppUBatchSize != request.llamaCppUBatchSize) return false
        if (llamaCppKvUnified != request.llamaCppKvUnified) return false
        if (llamaCppNKeep != request.llamaCppNKeep) return false
        if (llamaCppRopeFreqBase != request.llamaCppRopeFreqBase) return false
        if (llamaCppRopeFreqScale != request.llamaCppRopeFreqScale) return false
        if (contextWindow < request.contextWindow) return false
        if (request.requireMultimodal && !requireMultimodal) return false
        return true
    }

    fun isModelLoaded(modelName: String, config: InferenceConfig): Boolean {
        val normalizedConfig = config.normalized()
        val isSameModel = currentModelName == modelName
        val isCompatible = currentConfig?.isCompatibleWithRequest(normalizedConfig) == true
        val isLoaded = isSameModel && isCompatible
        
        Log.d(
            TAG,
            "isModelLoaded: model=$modelName | same=$isSameModel compatible=$isCompatible → result=$isLoaded"
        )
        return isLoaded
    }

    /**
     * 同じモデルが現在ロード済みかどうかを判定する。
     * 設定が異なっていて再ロードが必要でも、モデル自体はメモリ上にあるため
     * 再表示されるメモリ警告を抑制したいケースで利用する。
     */
    fun isSameModelLoaded(modelName: String): Boolean {
        val isSameModel = currentModelName == modelName
        val isLoaded = isSameModel
        Log.d(TAG, "isSameModelLoaded: model=$modelName | same=${isSameModel} → result=$isLoaded")
        return isLoaded
    }

    /**
     * モデルを初期化（ロード）
     * Phase 14: モデルロード前にメモリを詳細確認
     * Phase 15: LiteRtLm / GGUF エンジン自動選択（構築時）
     */
    suspend fun initializeModel(
        modelName: String = DEFAULT_MODEL_NAME,
        config: InferenceConfig = InferenceConfig()
    ): Result<Unit> {
        return loadMutex.withLock {
            try {
                val normalizedConfig = config.normalized()
                
                // 既に同じモデルがロードされていて、要求されたロード設定に対して互換性がある場合はスキップ
                val shouldSkip = currentModelName == modelName &&
                    currentConfig?.isCompatibleWithRequest(normalizedConfig) == true &&
                    activeEngine === engineForModel(modelName)

                if (shouldSkip) {
                    Log.d(TAG, "Model $modelName is already loaded and compatible with requested load config: ${normalizedConfig.backendType}")
                    return Result.success(Unit)
                }
                val targetEngine = engineForModel(modelName)
                
                // Phase 14: モデルロード前にメモリ状態を詳細ログ出力
                Log.d(TAG, "INIT_MODEL_PRE_CHECK: modelName=$modelName backend=${normalizedConfig.backendType} engine=${currentEngineLabel(targetEngine)}")
                val detailedMemInfo = memoryObserver.getDetailedMemoryInfo(context)
                Log.d(TAG, "INIT_MODEL_PRE_CHECK_MEMORY:\n$detailedMemInfo")
                
                // メモリ使用率をチェック
                val memStatus = memoryObserver.getMemoryStatus(context)
                Log.d(TAG, "INIT_MODEL_MEMORY_STATUS: level=${memStatus.level} used=${memStatus.usedMB}MB max=${memStatus.maxMB}MB percent=${memStatus.usedPercent}% device_low=${memStatus.isLowMemory}")
                
                if (!isMemorySufficient()) {
                    val errorMsg = "Cannot load model - memory usage is too high (${getMemoryUsagePercent()}% - ${memStatus.usedMB}/${memStatus.maxMB}MB)"
                    Log.e(TAG, "INIT_MODEL_MEMORY_INSUFFICIENT: $errorMsg")
                    return Result.failure(RuntimeException(errorMsg))
                }
                
                // 前のモデルをアンロード（エンジン切替時も明示）
                if (currentModelName != null || activeEngine !== targetEngine) {
                    val previousEngine = activeEngine
                    val switchingEngine = previousEngine !== targetEngine
                    Log.d(TAG, "Unloading previous model before loading new one (backend change: ${currentConfig?.backendType} -> ${normalizedConfig.backendType})")

                    runCatching { previousEngine.cancelInference() }
                        .onFailure { Log.w(TAG, "cancelInference failed", it) }
                    delay(50)
                    runCatching { previousEngine.unloadModel() }
                        .onFailure { Log.w(TAG, "unloadModel failed", it) }

                    // エンジン切り替え時は非アクティブ側も unload
                    if (switchingEngine) {
                        val inactiveEngine: AIInferenceEngine? =
                            if (previousEngine is GgufInferenceEngine) liteRtEngine else ggufEngine
                        inactiveEngine?.let { eng ->
                            Log.i(TAG, "Unloading inactive engine (${currentEngineLabel(eng)}) to prevent resource conflicts")
                            runCatching {
                                eng.cancelInference()
                                delay(50)
                                eng.unloadModel()
                            }.onFailure { Log.w(TAG, "Failed to unload inactive engine", it) }
                        }

                        // バックエンド切り替え時のメモリ解放
                        Log.i(TAG, "Backend change detected. Clearing memory pools...")
                        System.gc()
                        delay(300)
                    }

                    // GPU リソース解放待機
                    delay(200)
                }
                
                // 新しいモデルをロード
                Log.d(TAG, "Loading model: $modelName with backend: ${normalizedConfig.backendType} engine=${currentEngineLabel(targetEngine)}")
                val result = targetEngine.loadModel(modelName, normalizedConfig)
                
                if (result.isSuccess) {
                    activeEngine = targetEngine
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
        val engine = activeEngine
        var emitted = false
        try {
            val result = jobController.launchInference(sessionId) {
                engine.inference(sessionId, prompt, config).collect { chunk ->
                    emitted = true
                    emit(chunk)
                }
            }
            result.getOrThrow()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                Log.i(TAG, "Retrying inference once after recovery")
                activeEngine.inference(sessionId, prompt, config).collect { chunk ->
                    emit(chunk)
                }
            } else {
                throw t
            }
        }
    }

    suspend fun runInferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList(),
        config: InferenceConfig
    ): Flow<String> = flow {
        val engine = activeEngine
        var emitted = false
        try {
            val result = jobController.launchInference(sessionId) {
                engine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                    .collect { chunk ->
                        emitted = true
                        emit(chunk)
                    }
            }
            result.getOrThrow()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                Log.i(TAG, "Retrying multimodal inference once after recovery")
                activeEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                    .collect { chunk ->
                        emit(chunk)
                    }
            } else {
                throw t
            }
        }
    }
    
    /**
     * モデルが利用可能かチェック
     */
    suspend fun isModelAvailable(): Boolean {
        return activeEngine.isAvailable()
    }
    
    /**
     * モデルをアンロード
     */
    suspend fun unloadModel(skipCancelInference: Boolean = false): Result<Unit> {
        Log.d(TAG, "ModelManager.unloadModel: start skipCancelInference=$skipCancelInference")
        return loadMutex.withLock {
            try {
                if (!skipCancelInference) {
                    runCatching { activeEngine.cancelInference() }
                        .onFailure { Log.w(TAG, "cancelInference before unload failed", it) }
                    // 確実にキャンセルがネイティブ層に伝播するよう少し長めに待機
                    delay(200)
                } else {
                    Log.d(TAG, "unloadModel: skipping cancelInference as requested")
                }
                val result = activeEngine.unloadModel()
                currentModelName = null
                currentConfig = null
                Log.d(TAG, "ModelManager.unloadModel: completed success=${result.isSuccess}")
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
            activeEngine.cancelInference()
        } catch (e: Exception) {
            Log.e(TAG, "Error during inference cancellation", e)
        }
    }

    suspend fun cancelInferenceForSession(sessionId: Long) {
        cancelInference()
        try {
            jobController.cancelSessionTasks(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Error cancelling session inference tasks", e)
        }
    }

    suspend fun getLastGenerationTokenCount(): Float? {
        // GGUFエンジンでは内部トークン数を直接取得できないため、
        // PerformanceMonitorの直近完了セッションから補完する。
        return PerformanceMonitor.getLastCompletedTokenCount()
    }

    /* Phase 15 TODO: calibrateBackend を後で実装
     * 一時的にコメントアウト（SettingsRepository 統合が必要）
     
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
    */
}
