package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.cloud.AndroidCloudEngineAdapter
import com.nezumi_ai.data.inference.cloud.CloudChatMessage
import com.nezumi_ai.data.inference.cloud.CloudEngineFactory
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.remote.RemoteGgufInferenceEngine
import com.nezumi_ai.data.inference.remote.RemoteLiteRtInferenceEngine
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.utils.InferenceTelemetryRecorder
import com.nezumi_ai.utils.TelemetryGate
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
    //
    // プロセス分離 (dual-engine-process-isolation-plan):
    //   llama_backend_init/free がプロセス寿命で1回ずつという API 契約、および
    //   LiteRT-LM の Engine.close() が SIGABRT し得る問題のため、両エンジンとも
    //   別プロセス (:litert / :gguf) に隔離した。ここで保持するのは AIDL 越しの
    //   リモートアダプタであり、推論ロジック本体は各プロセス内の既存エンジンが担う。
    private val liteRtEngine: AIInferenceEngine = RemoteLiteRtInferenceEngine(context)

    /**
     * Bug fix(#5): LiteRT エンジンへ「このセッションは media を含む」と伝えるための
     * アクセサ。 RemoteLiteRtInferenceEngine 型を露出させ、可変フラグの設定をしてもらう。
     * GGUF モードやエンジン未初期化時は null。
     */
    fun liteRtEngineForMultiTurnMedia(): RemoteLiteRtInferenceEngine? =
        liteRtEngine as? RemoteLiteRtInferenceEngine
    /** GGUF は初回利用時まで遅延初期化し、:gguf プロセスを起動直後に立ち上げない。 */
    private var ggufEngine: RemoteGgufInferenceEngine? = null

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

    private fun getOrCreateGgufEngine(): RemoteGgufInferenceEngine? {
        ggufEngine?.let { return it }
        synchronized(this) {
            ggufEngine?.let { return it }
            return try {
                // llama_bridge ライブラリのロード可否は :gguf プロセス側で判定される。
                // メインプロセスでは llama_bridge を一切ロードしない（ロードすると
                // llama_backend_init によるグローバル状態が main に残ってしまう）。
                RemoteGgufInferenceEngine(context.applicationContext).also { ggufEngine = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to construct RemoteGgufInferenceEngine", e)
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
        // クラウド推論エンジンは既存の GGUF/LiteRT 判定の手前で分岐する。
        // modelName が `cloud:{provider}:{model}` 形式、またはレガシーの
        // `gemini_api` / `claude_api` の場合は CloudEngineFactory がプロバイダ別の
        // AIInferenceEngine 実装 (ClaudeInferenceEngine / GeminiInferenceEngine / ...) を返す。
        if (CloudModelId.isCloud(modelName)) {
            CloudEngineFactory.get(context, modelName)?.let { return it }
            // 未知の cloud id は LiteRT にフォールバック (もともと存在しないモデルだと loadModel が失敗する)
        }
        return if (shouldUseGgufEngine(modelName)) {
            getOrCreateGgufEngine()
                ?: throw IllegalStateException("GGUF engine unavailable: native library could not be loaded")
        } else {
            liteRtEngine
        }
    }

    /**
     * `modelName` がクラウド形式の場合は、プロバイダを剪いだ「生のモデル名」に変換して
     * エンジンに渡す。例: `cloud:gemini:gemini-2.5-flash` → `gemini-2.5-flash`.
     * 非クラウドモデルはそのまま。
     */
    private fun engineModelName(modelName: String): String {
        if (!CloudModelId.isCloud(modelName)) return modelName
        val parsed = CloudModelId.parse(modelName) ?: return modelName
        return parsed.modelName
    }

    /**
     * エンジンへのモデルロード。クラウド系エンジンには modelId (`cloud:...`) も
     * 一緒に渡して、個別設定 (API キー / Base URL のモデル単位オーバーライド) を
     * 解決できるようにする。
     */
    private suspend fun loadModelOnEngine(
        engine: AIInferenceEngine,
        modelName: String,
        config: InferenceConfig
    ): Result<Unit> {
        val engineModel = engineModelName(modelName)
        val cloudEngine = engine as? AndroidCloudEngineAdapter
        return if (cloudEngine != null && CloudModelId.isCloud(modelName)) {
            cloudEngine.loadModelWithId(modelName, engineModel, config)
        } else {
            engine.loadModel(engineModel, config)
        }
    }

    /**
     * クラウド推論エンジンかどうかの判定を一本化する。
     * オンデバイス専用のローカルテレメトリ記録はクラウドエンジンでは
     * 行わない（クラウド側のエラーはクラウド経路で扱う）等の分類ロジックで
     * 共通利用する。エンジン種別が増えた場合はここだけを直せばよい。
     */
    private fun AIInferenceEngine.isCloudEngine(): Boolean =
        this is AndroidCloudEngineAdapter

    private fun currentEngineLabel(engine: AIInferenceEngine): String {
        return when {
            engine is RemoteGgufInferenceEngine -> "GGUF"
            engine === liteRtEngine -> "LiteRtLm"
            else -> "Cloud(${engine.javaClass.simpleName})"
        }
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

        if (isGpuBacked && engine is RemoteLiteRtInferenceEngine) {
            Log.w(TAG, "GPU/NPU backend: using forceReset() instead of unloadModel() to avoid SIGABRT")
            engine.forceReset()
        } else {
            runCatching { engine.unloadModel() }
                .onFailure { Log.w(TAG, "Engine unload during recovery failed", it) }
        }

        // クラウドモデルは `cloud:...` プレフィックスを剥いでエンジンに渡す。
        val reloaded = loadModelOnEngine(engine, modelName, normalized)
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
        if (!llamaCppGpuBackend.equals(request.llamaCppGpuBackend, ignoreCase = true)) return false
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
                    val memError = RuntimeException(errorMsg)
                    if (!targetEngine.isCloudEngine()) {
                        runCatching { TelemetryGate.onLocalInferenceUsed() }
                        InferenceTelemetryRecorder.recordLoadFailure(
                            context, currentEngineLabel(targetEngine), modelName, memError
                        )
                    }
                    return Result.failure(memError)
                }
                
                // 前のモデルをアンロード（エンジン切替時も明示）
                // エンジン切り替え時はマルチモーダルプロジェクターとLLMを強制停止してから切り替える。
                // 生成中のネイティブループや mmproj が残っていると、次エンジンのロードで
                // メモリ競合・クラッシュ・不正な KV/vision 状態が起きるため、
                // cancel → (interrupt) → unload を確実に実行する。
                if (currentModelName != null || activeEngine !== targetEngine) {
                    val previousEngine = activeEngine
                    val switchingEngine = previousEngine !== targetEngine
                    Log.d(TAG, "Unloading previous model before loading new one (backend change: ${currentConfig?.backendType} -> ${normalizedConfig.backendType})")

                    // 強制停止: 推論中のLLM / マルチモーダルプロジェクターを即座に止める
                    runCatching { previousEngine.cancelInference() }
                        .onFailure { Log.w(TAG, "cancelInference failed", it) }
                    // GGUF の場合は nativeInterrupt が cancelInference 内で呼ばれる。
                    // エンジン切替時は待機を少し長くして完了を待つ。
                    delay(if (switchingEngine) 100 else 50)
                    runCatching { previousEngine.unloadModel() }
                        .onFailure { Log.w(TAG, "unloadModel failed", it) }

                    // エンジン切り替え時は、切替元エンジンが動いている別プロセス
                    // (:gguf / :litert) ごと終了させる (dual-engine-process-isolation-plan 5章)。
                    //
                    // GGUF 側は llama_backend_free() が API 契約上「プロセス終了時に1回」の
                    // ため Kotlin/JNI 層では Vulkan/OpenCL のグローバル状態を解放できず、
                    // LiteRT-LM 側は Engine.close() が SIGABRT し得るため正規の解放パスが
                    // 使えない。いずれも OS のプロセス回収に委ねるのが確実。
                    // クラウドエンジンはプロセスを持たないため対象外。
                    if (switchingEngine) {
                        when (previousEngine) {
                            is RemoteGgufInferenceEngine -> {
                                Log.i(TAG, "Switching away from GGUF: shutting down :gguf process")
                                runCatching { previousEngine.shutdownProcess() }
                                    .onFailure { Log.w(TAG, "GGUF shutdownProcess failed", it) }
                            }
                            is RemoteLiteRtInferenceEngine -> {
                                Log.i(TAG, "Switching away from LiteRT-LM: shutting down :litert process")
                                runCatching { previousEngine.shutdownProcess() }
                                    .onFailure { Log.w(TAG, "LiteRT shutdownProcess failed", it) }
                            }
                            else -> {
                                // クラウドエンジン等: プロセスを持たないため従来通り unload のみ
                                runCatching {
                                    previousEngine.cancelInference()
                                    delay(100)
                                    previousEngine.unloadModel()
                                }.onFailure { Log.w(TAG, "Failed to unload previous engine", it) }
                            }
                        }

                        // バックエンド/エンジン切り替え時のメモリ解放
                        Log.i(TAG, "Engine/backend change detected. Forcing memory cleanup after engine process shutdown...")
                        System.gc()
                        delay(400)
                    }

                    // GPU / ネイティブリソース解放待機
                    delay(if (switchingEngine) 300 else 200)
                }
                
                // 新しいモデルをロード
                // クラウドの場合は `cloud:...` プレフィックスを剥いでエンジンに渡す。
                val engineModel = engineModelName(modelName)
                val engineLabel = currentEngineLabel(targetEngine)
                if (!targetEngine.isCloudEngine()) {
                    // オンデバイスモデルのロード・推論テレメトリは、クラウド利用と同じ
                    // 同意条件で Sentry へ送信され得る（TelemetryGate 側の判定に従う）。
                    runCatching { TelemetryGate.onLocalInferenceUsed() }
                }
                Log.d(TAG, "Loading model: $modelName (engineArg=$engineModel) with backend: ${normalizedConfig.backendType} engine=$engineLabel")
                val loadStartMs = System.currentTimeMillis()
                val result = loadModelOnEngine(targetEngine, modelName, normalizedConfig)
                val loadDurationMs = System.currentTimeMillis() - loadStartMs
                
                if (result.isSuccess) {
                    activeEngine = targetEngine
                    currentModelName = modelName
                    currentConfig = normalizedConfig
                    Log.d(TAG, "Model loaded successfully: $modelName with backend: ${normalizedConfig.backendType}")
                    InferenceTelemetryRecorder.recordLoadSuccess(
                        context, engineLabel, modelName, loadDurationMs, normalizedConfig
                    )
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "Failed to load model: $modelName. Reason: ${error?.message}", error)
                    if (!targetEngine.isCloudEngine() && error != null) {
                        InferenceTelemetryRecorder.recordLoadFailure(
                            context, engineLabel, modelName, error
                        )
                    }
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
    suspend fun formatGgufChatTemplate(
        messagesJson: String,
        enableThinking: Boolean = false
    ): String? {
        val engine = activeEngine as? RemoteGgufInferenceEngine ?: return null
        return engine.formatWithGgufChatTemplate(messagesJson, enableThinking)
            .takeIf { it.isNotBlank() }
    }

    /**
     * ユーザー選択の明示 Jinja テンプレートでレンダリングする。
     * 失敗時 (空/非 GGUF) は null で呼び出し側が内蔵 GGUF テンプレートへフォールバックする。
     */
    suspend fun formatGgufChatTemplateWithJinja(
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean = false
    ): String? {
        val engine = activeEngine as? RemoteGgufInferenceEngine ?: return null
        return engine.formatWithJinjaChatTemplate(messagesJson, chatTemplate, enableThinking)
            .takeIf { it.isNotBlank() }
    }

    /** 現在の GGUF コンテキストにチャットテンプレートが適用済みかどうか。 */
    fun hasGgufChatTemplate(): Boolean =
        (activeEngine as? RemoteGgufInferenceEngine)?.hasGgufChatTemplate() ?: false

    /**
     * 直近の initializeModel() で要求したGPUバックエンド (OpenCL / Vulkan) が
     * この端末では実行時に利用できず、CPUへ静かにフォールバックしたかどうか。
     *
     * true の場合、呼び出し側 (ChatViewModel) は必ずユーザーにその旨をダイアログで
     * 提示し、CPUで続行するか / キャンセルするかを選ばせること。
     * ユーザーが選んでいないバックエンドで黙って動かし続けてはならない。
     */
    fun didFallBackFromRequestedGpuBackend(): Boolean =
        (activeEngine as? RemoteGgufInferenceEngine)?.gpuBackendFallbackOccurred ?: false

    /**
     * Mini App API (ai.unloadModel) 用。
     *
     * llama.cpp (GGUF) 側でモデルがロードされている場合、共有されている GPU /
     * メモリ資源を即時回収するため :litert プロセスを終了させる。
     *
     * プロセス分離前は LiteRtLmEngine.forceReset() (Engine.close() を経ない
     * 強制無効化) を呼んでいたが、分離後はプロセスごと終了させる方が確実かつ
     * 完全にリソースを回収できる (dual-engine-process-isolation-plan 5.3 / 5.5)。
     * GGUF モデルがロードされていなければ何もしない。
     *
     * @return true: GGUF ロード中で :litert プロセスを終了した / false: 対象外で何もしなかった
     */
    suspend fun forceReleaseLiteRtIfGgufLoaded(): Boolean {
        return loadMutex.withLock {
            val ggufActive = activeEngine is RemoteGgufInferenceEngine && currentModelName != null
            if (!ggufActive) {
                Log.d(TAG, "forceReleaseLiteRtIfGgufLoaded: no GGUF model loaded, nothing to do")
                return@withLock false
            }
            Log.i(TAG, "forceReleaseLiteRtIfGgufLoaded: GGUF model loaded — killing :litert process")
            val liteRt = liteRtEngine as? RemoteLiteRtInferenceEngine
            if (liteRt != null) {
                runCatching { liteRt.shutdownProcess() }
                    .onFailure { Log.w(TAG, "LiteRT shutdownProcess failed", it) }
            }
            true
        }
    }

    /** 直近ロードで実際に使われたバックエンド ("CPU" / "OPENCL" / "VULKAN")。 */
    fun currentActualGpuBackend(): String =
        (activeEngine as? RemoteGgufInferenceEngine)?.actualGpuBackend ?: LlamaCppGpuBackend.CPU

    fun parseGgufChatOutput(
        output: String,
        isPartial: Boolean
    ): GgufInferenceEngine.GgufChatParseResult? =
        (activeEngine as? RemoteGgufInferenceEngine)?.parseWithGgufChatTemplate(output, isPartial)

    suspend fun runInference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = flow {
        val engine = activeEngine
        val engineLabel = currentEngineLabel(engine)
        val modelNameForTelemetry = currentModelName ?: "unknown"
        if (!engine.isCloudEngine()) {
            runCatching { TelemetryGate.onLocalInferenceUsed() }
        }
        var emitted = false
        try {
            measureAndRecord(engineLabel, modelNameForTelemetry, config) { onChunk ->
                val result = jobController.launchInference(sessionId) {
                    engine.inference(sessionId, prompt, config).collect { chunk ->
                        emitted = true
                        onChunk(chunk)
                        emit(chunk)
                    }
                }
                result.getOrThrow()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                Log.i(TAG, "Retrying inference once after recovery")
                measureAndRecord(engineLabel, modelNameForTelemetry, config) { onChunk ->
                    activeEngine.inference(sessionId, prompt, config).collect { chunk ->
                        onChunk(chunk)
                        emit(chunk)
                    }
                }
            } else {
                throw t
            }
        }
    }

    /**
     * Phase 5/6 のクラウド正式経路: role 付きメッセージ配列を受け取り、
     * AndroidCloudEngineAdapter 経由で構造化されたままクラウド API に届ける。
     * クラウドエンジン以外に対して呼ばれた場合は平文連結にフォールバックする
     * (本来は ChatViewModel 側でクラウドと判定済みの場合のみ呼ばれる想定)。
     */
    suspend fun runCloudInferenceWithMessages(
        sessionId: Long,
        messages: List<CloudChatMessage>,
        images: List<Bitmap> = emptyList(),
        config: InferenceConfig
    ): Flow<String> = flow {
        val engine = activeEngine as? AndroidCloudEngineAdapter
        if (engine == null) {
            // フォールバック: クラウド以外では平文に潰して従来経路へ。
            val flat = messages.joinToString("\n") { it.text }
            runInference(sessionId, flat, config).collect { emit(it) }
            return@flow
        }
        val engineLabel = currentEngineLabel(engine)
        val modelNameForTelemetry = currentModelName ?: "unknown"
        try {
            measureAndRecord(engineLabel, modelNameForTelemetry, config) { onChunk ->
                val flow = if (images.isEmpty()) {
                    engine.inferenceWithMessages(sessionId, messages, config)
                } else {
                    engine.inferenceWithMessagesAndMedia(sessionId, messages, images, config)
                }
                flow.collect { chunk ->
                    onChunk(chunk)
                    emit(chunk)
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            InferenceTelemetryRecorder.recordInferenceFailure(context, engineLabel, modelNameForTelemetry, t)
            throw t
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
        val engineLabel = currentEngineLabel(engine)
        val modelNameForTelemetry = currentModelName ?: "unknown"
        if (!engine.isCloudEngine()) {
            runCatching { TelemetryGate.onLocalInferenceUsed() }
        }
        var emitted = false
        try {
            measureAndRecord(engineLabel, modelNameForTelemetry, config) { onChunk ->
                val result = jobController.launchInference(sessionId) {
                    engine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                        .collect { chunk ->
                            emitted = true
                            onChunk(chunk)
                            emit(chunk)
                        }
                }
                result.getOrThrow()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (!emitted && isCompiledModelInvokeFailure(t) && recoverFromInvokeFailure(config)) {
                Log.i(TAG, "Retrying multimodal inference once after recovery")
                measureAndRecord(engineLabel, modelNameForTelemetry, config) { onChunk ->
                    activeEngine.inferenceWithMedia(sessionId, prompt, images, audioClips, config)
                        .collect { chunk ->
                            onChunk(chunk)
                            emit(chunk)
                        }
                }
            } else {
                throw t
            }
        }
    }

    /**
     * 推論実行に共通のテレメトリ計測をまとめる高階関数。
     *
     * 通常パス・リトライパスのどちらから呼ばれても「開始時刻の計測 → 実行 →
     * 成功時は速度記録 / 失敗時は失敗記録して再送出」の流れが一本化されるため、
     * 計測実装を変更する際に直す場所はここだけになる。
     */
    private suspend fun measureAndRecord(
        engineLabel: String,
        modelName: String,
        config: InferenceConfig,
        block: suspend (onChunk: suspend (String) -> Unit) -> Unit
    ) {
        val startMs = System.currentTimeMillis()
        var chunkCount = 0
        try {
            block { chunkCount++ }
            InferenceTelemetryRecorder.recordInferenceSpeed(
                context, engineLabel, modelName,
                System.currentTimeMillis() - startMs, chunkCount.takeIf { it > 0 }, config.normalized()
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            InferenceTelemetryRecorder.recordInferenceFailure(context, engineLabel, modelName, t)
            throw t
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

    // ─── コンテキストメーター / TPS 正確化 ──────────────────────────────

    /**
     * 現在のコンテキスト (KV キャッシュ) 使用量をトークン数で返す。
     * 画像・音声を含む実測値。未取得時は null。
     *   - GGUF: llama.cpp の n_past (評価済みトークン数)
     *   - LiteRT-LM: Conversation.getTokenCount() (prefill + decode)
     */
    fun getCurrentContextTokenCountSync(): Int? = when (val engine = activeEngine) {
        is RemoteGgufInferenceEngine -> engine.getPastTokenCountSync()
        is RemoteLiteRtInferenceEngine -> engine.getConversationTokenCountSync()
        else -> null
    }

    /**
     * 直近リクエストのプロンプトトークン (合計, うちメディア)。未取得時は null。
     *   - GGUF: mtmd_tokenize の実測値 (画像・音声トークンを含む)
     *   - LiteRT-LM: getBenchmarkInfo().lastPrefillTokenCount (画像・音声を含む)
     */
    fun getLastPromptTokenInfoSync(): Pair<Int, Int>? = when (val engine = activeEngine) {
        is RemoteGgufInferenceEngine -> engine.getLastPromptTokenInfoSync()
        is RemoteLiteRtInferenceEngine ->
            engine.getLastBenchmarkSync()?.let { it.prefillTokens to 0 }
        else -> null
    }

    /** テキストを実トークナイザでトークナイズしてトークン数だけを返す (GGUF のみ)。未取得時は null。 */
    fun countPromptTokensSync(text: String): Int? =
        (activeEngine as? RemoteGgufInferenceEngine)?.countPromptTokensSync(text)

    /** 直近推論の実測デコード TPS (LiteRT-LM のみ)。未取得時は null。 */
    fun getLastDecodeTpsSync(): Float? =
        (activeEngine as? RemoteLiteRtInferenceEngine)?.getLastBenchmarkSync()
            ?.decodeTokensPerSecond?.takeIf { it > 0.0 }?.toFloat()

    /** 直近推論の実測デコードトークン数 (LiteRT-LM のみ)。未取得時は null。 */
    fun getLastDecodeTokenCountSync(): Int? =
        (activeEngine as? RemoteLiteRtInferenceEngine)?.getLastBenchmarkSync()
            ?.decodeTokens?.takeIf { it >= 0 }

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
