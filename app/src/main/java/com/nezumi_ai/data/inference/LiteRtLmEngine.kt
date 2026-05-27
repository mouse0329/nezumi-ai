package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.SamplerConfig
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gemma / LiteRT-LM 推論（[com.google.ai.edge.litertlm]）。
 * AI Edge Gallery の [LlmChatModelHelper] と同様に Engine + Conversation で推論し、
 * thought チャンネルを [InferenceStreamProtocol.encodeThinkChunk] で送出する。
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmEngine(
    private val appContext: Context
) : AIInferenceEngine {

    companion object {
        private const val TAG = "LiteRtLmEngine"
        private const val MAX_VISION_IMAGES = 5
        private const val MAX_BITMAP_EDGE = 1024
        private const val THOUGHT_CHANNEL = "thought"

        /**
         * モデル初期化はネイティブ側が重い。メイン・Default 共有プールを避け、1 本の IO ワーカーに直列化する。
         */
        private val modelLoadDispatcher = Dispatchers.IO.limitedParallelism(1)

        private fun shouldEmitPartialText(partial: String): Boolean {
            if (partial.isEmpty()) return false
            val t = partial.trimStart()
            return !t.startsWith("<ctrl", ignoreCase = true)
        }
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: InferenceConfig? = null
    private var loadedBackend: String? = null  // Phase 11: GPU/CPU/NPU バックエンド追跡（キャッシュ無効化用）
    @Volatile private var loadedWithVisionAudio: Boolean = false
    private var disableXnnpackCacheForProcess: Boolean = false
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)
    
    // ─────────────────────────────────────────────────────────────────────────
    // Phase 14: 抽出推論の割り込み検出メカニズム
    // ─────────────────────────────────────────────────────────────────────────
    
    /** 通常推論がリクエストされていることを示すフラグ。抽出推論がこれを見て自分をキャンセル */
    @Volatile private var normalInferenceRequested = false
    
    private val alarmDao by lazy { NezumiAiDatabase.getInstance(appContext).alarmDao() }
    private val memoryRepository: com.nezumi_ai.data.repository.MemoryRepository by lazy { 
        val db = NezumiAiDatabase.getInstance(appContext)
        com.nezumi_ai.data.repository.MemoryRepository(db.memoryDao())
    }
    private val toolExecutor by lazy { 
        NezumiLiteRtToolExecutor(appContext, alarmDao, memoryRepository, MemoryTextEmbedder)
    }

    /** Engine は同時に 1 セッションのみ。コールバックスレッドと awaitClose の競合もここで直列化する */
    private val activeConversationLock = Mutex()
    /** activeConversationLock で保護。@Volatile 不要（全アクセスが synchronized 内） */
    private var activeLiteRtConversation: Conversation? = null
    
    private data class ConversationKey(
        val sessionId: Long,
        val enableThinking: Boolean
    )

    /** セッション遷移検出用 */
    @Volatile
    private var activeLiteRtConversationKey: ConversationKey? = null
    
    // ─────────────────────────────────────────────────────────
    // Phase 11: リソース管理の統合
    // ─────────────────────────────────────────────────────────
    
    /** メモリ監視 */
    private val memoryObserver = MemoryObserver
    
    /** Bitmap メモリプール */
    private val bitmapPool = BitmapMemoryPool()
    
    /** セッションリソース管理 */
    private val sessionManager = SessionResourceManager()
    
    /** Coroutine/Job 管理 */
    private val jobController = InferenceJobController()
    
    /** バックエンドリソース管理 */
    private val backendResourceManager = BackendResourceManager()

    // ─────────────────────────────────────────────────────────
    // Phase 13: メモリ抽出専用セッション（再ロードなし）
    // ─────────────────────────────────────────────────────────
    
    /** メモリ抽出用専用セッション（Thinking=OFF, JSON確定用） */
    @Volatile
    private var memoryExtractionConversation: Conversation? = null
    @Volatile
    private var memoryExtractionConversationConfig: InferenceConfig? = null

    /**
     * AI Edge Gallery 方式：推論キャンセル時は cancelProcess() だけ。
     * close() はセッション遷移時のみ。
     */
    private fun cancelConversation(conversation: Conversation?) {
        if (conversation == null) return
        runCatching {
            Log.d(TAG, "Cancelling conversation process")
            conversation.cancelProcess()
        }.onFailure { t ->
            Log.w(TAG, "Failed to cancel conversation process", t)
        }
    }

    private suspend fun cancelActiveConversation() {
        activeConversationLock.withLock {
            cancelConversation(activeLiteRtConversation)
        }
    }

    private suspend fun cancelMemoryExtractionConversation() {
        activeConversationLock.withLock {
            cancelConversation(memoryExtractionConversation)
        }
    }

    /**
     * awaitClose など suspend 不可のコンテキスト向け。
     * ロックなしで cancelProcess() のみ直接呼ぶ（runBlocking 禁止）。
     */
    private fun cancelActiveConversationNonSuspend() {
        runCatching { activeLiteRtConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelActiveConversationNonSuspend failed", it) }
    }

    private fun cancelMemoryExtractionConversationNonSuspend() {
        runCatching { memoryExtractionConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelMemoryExtractionConversationNonSuspend failed", it) }
    }

    /**
     * セッション遷移時のみ使用。古い Conversation を close() して新たに作成する。
     */
    private suspend fun closeAndResetActiveConversation(sessionId: Long? = null) {
        activeConversationLock.withLock {
            val c = activeLiteRtConversation ?: return
            activeLiteRtConversation = null
            activeLiteRtConversationKey = null
            
            runCatching {
                Log.d(TAG, "Closing active conversation")
                // 1. まずネイティブプロセスをキャンセル
                c.cancelProcess()
                
                // 2. セッションIDが指定されている場合、関連タスクのキャンセルを待機
                if (sessionId != null) {
                    jobController.cancelSessionTasks(sessionId)
                    // ネイティブ側がキャンセルを処理し、スレッドが安全に停止するまでわずかに待機
                    // (SIGSEGV 回避のための安全策)
                    Thread.sleep(50) 
                }
                
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close conversation", t)
            }
            Log.i(TAG, "Active conversation closed and reset")
        }
    }

    private suspend fun closeAndResetMemoryExtractionConversation() {
        activeConversationLock.withLock {
            val c = memoryExtractionConversation ?: return
            memoryExtractionConversation = null
            memoryExtractionConversationConfig = null
            runCatching {
                Log.d(TAG, "Closing memory extraction conversation")
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close memory extraction conversation", t)
            }
            Log.i(TAG, "Memory extraction conversation closed and reset")
        }
    }

    /**
     * セッションIDに基づいて Conversation を取得または新規作成する。
     * 同一セッション内では Conversation と KVキャッシュを再利用し、レスポンス速度を向上させる。
     *
     * @param sessionId 現在のセッションID
     * @param eng 初期化済みの Engine インスタンス
     * @param config 推論設定（サンプラーパラメータなど）
     * @return Conversation インスタンス
     */
    private suspend fun getOrCreateConversation(
        sessionId: Long,
        eng: Engine,
        config: InferenceConfig
    ): Conversation {
        val normalized = config.normalized()
        val requestKey = ConversationKey(sessionId, normalized.enableThinking)
        var convToAttach: Conversation? = null
        var created = false
        val maxAttempts = 6
        var attempt = 0
        while (!created && attempt < maxAttempts) {
            attempt++
            
            // ロックの外でリソースをクリーンアップ
            if (memoryExtractionConversation != null) {
                Log.i(TAG, "Closing existing memory extraction conversation before creating new one")
                closeAndResetMemoryExtractionConversation()
            }

            // 現在のセッション情報を取得してロック外でクローズを呼ぶ
            val currentSessionId = activeConversationLock.withLock { activeLiteRtConversationKey?.sessionId }
            if (currentSessionId != null) {
                closeAndResetActiveConversation(currentSessionId)
            }

            activeConversationLock.withLock {
                // セッションIDまたはThinking設定が変わった場合は新しいConversationを作成
                if (activeLiteRtConversation == null || activeLiteRtConversationKey != requestKey) {
                    val samplerConfig = if (normalized.backendType.uppercase() == "NPU") {
                        null
                    } else {
                        SamplerConfig(
                            topK = normalized.maxTopK,
                            topP = normalized.topP.toDouble(),
                            temperature = normalized.temperature.toDouble()
                        )
                    }

                    // TQ導入: GPU利用時のみ投機的デコーディングを許可（NPUとの競合を防ぐ）
                    val canUseSpeculative = normalized.enableSpeculativeDecoding &&
                        normalized.backendType.uppercase() == "GPU"
                    ExperimentalFlags.enableSpeculativeDecoding = canUseSpeculative

                    try {
                        val conv = eng.createConversation(
                            ConversationConfig(
                                tools = buildEnabledToolProviders(appContext, alarmDao),
                                samplerConfig = samplerConfig,
                                automaticToolCalling = false
                            )
                        )
                        activeLiteRtConversation = conv
                        activeLiteRtConversationKey = requestKey
                        convToAttach = conv
                        Log.d(TAG, "New conversation created for key=$requestKey, KVCache initialized")
                        created = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "createConversation failed on attempt $attempt: ${t.message}")
                        // もしネイティブ側で既存セッションが残っている旨のエラーなら、短い遅延ののち再試行
                        if (attempt < maxAttempts && t.message?.contains("session already exists", ignoreCase = true) == true) {
                            // leave synchronized block briefly to allow native cleanup
                        } else {
                            throw t
                        }
                    }
                } else {
                    Log.d(TAG, "Conversation reused for key=$requestKey, KVCache preserved")
                    created = true
                }
            }

            if (!created) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                }
            }
        }

        // synchronized 範囲外で SessionResourceManager に関連付けを行う
        convToAttach?.let { convCreated ->
            try {
                sessionManager.attachConversation(sessionId, convCreated)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach conversation to session manager: $sessionId", e)
            }
        }

        return activeLiteRtConversation!!
    }

    private suspend fun getOrCreateMemoryExtractionConversation(
        eng: Engine,
        config: InferenceConfig
    ): Conversation {
        // Note: At this point, inferenceMutex is already held by the calling inference() function.
        // No need to wait for it.
        
        var convToAttach: Conversation? = null
        var created = false
        val maxAttempts = 6
        var attempt = 0
        val normalized = config.normalized()

        while (!created && attempt < maxAttempts) {
            attempt++

            // まずロック内で作成が必要か確認し、フラグだけを立てる
            var needCreate = false
            activeConversationLock.withLock {
                if (memoryExtractionConversation == null || memoryExtractionConversationConfig != normalized) {
                    Log.i(TAG, "Creating new memory extraction conversation (attempt=$attempt)")
                    needCreate = true
                } else {
                    Log.d(TAG, "Memory extraction conversation reused")
                    created = true
                }
            }

            if (needCreate) {
                // 既存のメモリ抽出 Conversation を安全にクローズ（suspend 関数、ロック外で呼ぶ）
                if (memoryExtractionConversation != null) {
                    closeAndResetMemoryExtractionConversation()
                }

                ExperimentalFlags.enableConversationConstrainedDecoding = false
                val samplerConfig = if (normalized.backendType.uppercase() == "NPU") {
                    null
                } else {
                    SamplerConfig(
                        topK = normalized.maxTopK,
                        topP = normalized.topP.toDouble(),
                        temperature = normalized.temperature.toDouble()
                    )
                }

                val canUseSpeculative = normalized.enableSpeculativeDecoding &&
                    normalized.backendType.uppercase() == "GPU"
                ExperimentalFlags.enableSpeculativeDecoding = canUseSpeculative

                try {
                    val conv = eng.createConversation(
                        ConversationConfig(
                            tools = emptyList(),  // メモリ抽出はツール不要
                            samplerConfig = samplerConfig,
                            automaticToolCalling = false
                        )
                    )

                    // 作成した Conversation をロック内で設定
                    activeConversationLock.withLock {
                        memoryExtractionConversation = conv
                        memoryExtractionConversationConfig = normalized
                    }

                    convToAttach = conv
                    Log.d(TAG, "Memory extraction conversation created")
                    created = true
                } catch (t: Throwable) {
                    Log.w(TAG, "createConversation for memory extraction failed on attempt $attempt: ${t.message}")
                    if (attempt < maxAttempts && t.message?.contains("session already exists", ignoreCase = true) == true) {
                        // will retry after brief sleep
                    } else {
                        throw t
                    }
                }
            }

            if (!created) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                }
            }
        }

        // Dedicated memory extraction conversations are managed internally
        // and do not correspond to a regular session ID in SessionResourceManager.
        // No session attachment is required.
        return memoryExtractionConversation!!
    }

    private suspend fun acquireInferenceMutex() {
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)
    }

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex called but mutex was not held (double-release guard)")
        }
    }

    private fun resolveNativeLibraryDirForLitert(): String {
        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        Log.d(TAG, "NPU native library dir: $nativeLibDir")
        return nativeLibDir ?: ""
    }

    private fun getOptimalBackendType(requestedBackendType: String): String {
        val normalizedRequested = requestedBackendType.uppercase()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase().ifBlank { Build.HARDWARE.lowercase() }
        } else {
            Build.HARDWARE.lowercase()
        }
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        Log.d(TAG, "Hardware check: socModel=$socModel model=$model device=$device manufacturer=$manufacturer requestedBackend=$normalizedRequested")

        val isGoogleTensor = socModel.contains("tensor") || socModel.contains("gs") ||
            model.contains("pixel 8a") || model.contains("pixel 8") || device.contains("gs")
        val isSupportedQualcommNpu = listOf("sm8550", "sm8650", "sm8750").any { socModel.contains(it) }

        return when {
            normalizedRequested != "NPU" -> normalizedRequested
            isGoogleTensor -> {
                Log.i(TAG, "Google Tensor detected. TQ演算の相性によりGPUへフォールバック.")
                "GPU"
            }
            isSupportedQualcommNpu -> {
                Log.i(TAG, "Supported Qualcomm NPU SoC detected. Attempting NPU.")
                "NPU"
            }
            else -> {
                Log.i(TAG, "Unconfirmed NPU support for SoC. Falling back to CPU/XNNPACK.")
                "CPU"
            }
        }
    }

    /**
     * XNNPack キャッシュ向けに、mmap/remap の失敗を避けるため
     * 内部ストレージ（/data 配下）のみを候補にする。
     */
    private fun resolveWritableXnnpackCacheDir(): File? {
        if (disableXnnpackCacheForProcess) {
            Log.w(TAG, "XNNPack cache is disabled for this process due to previous mmap/remap failure.")
            return null
        }

        val candidates = listOfNotNull(
            appContext.codeCacheDir?.let { File(it, "litertlm_xnnpack") },
            File(appContext.cacheDir, "litertlm_xnnpack")
        )

        for (dir in candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "Failed to create XNNPack cache dir: ${dir.absolutePath}")
                    continue
                }
                if (!dir.isDirectory) {
                    Log.w(TAG, "XNNPack cache candidate is not a directory: ${dir.absolutePath}")
                    continue
                }
                val probe = File(dir, ".rw_probe")
                probe.writeText("ok")
                if (!probe.delete()) {
                    probe.deleteOnExit()
                }
                Log.d(TAG, "Using XNNPack cache dir: ${dir.absolutePath}")
                return dir
            } catch (e: Exception) {
                Log.w(TAG, "XNNPack cache dir is not writable: ${dir.absolutePath}", e)
            }
        }

        Log.w(TAG, "No writable XNNPack cache directory found. Continuing without cacheDir.")
        return null
    }

    /**
     * XNNPack mmap/re-map 失敗の典型メッセージを判定。
     * 例: "mmap_handle.cc:173: remap failed: Bad address"
     */
    private fun isXnnpackMmapFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("mmap_handle.cc", ignoreCase = true) ||
                message.contains("remap failed", ignoreCase = true) ||
                message.contains("bad address", ignoreCase = true) ||
                message.contains("xnnpack", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return try {
            withContext(modelLoadDispatcher) {
                modelMutex.withLock {
                    loadModelLocked(modelName, config)
                }
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }

    /**
     * [loadModel] の本体。[withContext] / [Mutex.withLock] の crossinline 内では return が使えないため分離。
     */
    private suspend fun loadModelLocked(modelName: String, config: InferenceConfig): Result<Unit> {
        val normalizedConfig = config.normalized()
        Log.d(TAG, "loadModel START: modelName=$modelName backend=${normalizedConfig.backendType}")
        val modelStartTimeMs = System.currentTimeMillis()

        val modelFile = resolveLocalModelFile(modelName)
        val resolveTimeMs = System.currentTimeMillis()
        Log.d(TAG, "loadModel RESOLVE: file=$modelFile duration=${resolveTimeMs - modelStartTimeMs}ms")

        if (modelFile == null || !modelFile.exists()) {
            return Result.failure(IllegalStateException("Model file is not available"))
        }
        val modelPath = modelFile.absolutePath
        val needsReload = loadedModelPath != modelPath ||
            loadedConfig?.forModelLoad() != normalizedConfig.forModelLoad() ||
            loadedBackend != normalizedConfig.backendType ||
            (normalizedConfig.requireMultimodal && !loadedWithVisionAudio) ||
            engine == null

        if (!needsReload) {
            Log.d(TAG, "Model already loaded: $modelPath backend=${normalizedConfig.backendType}")
            return Result.success(Unit)
        }

        val effectiveBackendType = getOptimalBackendType(normalizedConfig.backendType)
        val preferredBackend = backendForConfig(effectiveBackendType)
        val cacheDir = resolveWritableXnnpackCacheDir()
        val cacheDirPath = cacheDir?.absolutePath
        val backendChanged = loadedBackend != null && loadedBackend != effectiveBackendType
        if (backendChanged) {
            Log.i(TAG, "Backend changed from $loadedBackend to $effectiveBackendType. Clearing cache...")
        }

        runCatching { engine?.close() }
        engine = null
        loadedModelPath = null
        loadedConfig = null
        loadedBackend = null

        if (backendChanged) {
            clearBackendSpecificCache(cacheDirPath)
        }

        Log.d(TAG, "loadModel CACHE_VALIDATE: path=$cacheDirPath")
        CacheManager.validateAndRepairCacheIfNeeded(cacheDirPath)
        CacheManager.cleanupCacheIfNeeded(appContext, modelFile.name.lowercase(), cacheDir)
        val validateTimeMs = System.currentTimeMillis()
        Log.d(TAG, "loadModel CACHE_VALIDATE: duration=${validateTimeMs - resolveTimeMs}ms")

        fun newEngine(withVisionAudio: Boolean, backend: Backend, attemptCacheDir: String?): Engine {
            val ec = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = if (withVisionAudio) Backend.GPU() else null,
                audioBackend = if (withVisionAudio) Backend.CPU() else null,
                maxNumTokens = normalizedConfig.contextWindow.coerceAtLeast(2048),
                cacheDir = attemptCacheDir
            )
            return Engine(ec)
        }

        suspend fun tryCreate(withVisionAudio: Boolean, backend: Backend): Engine {
            Log.d(
                TAG,
                "loadModel ENGINE_INIT: START - backend=${backend.javaClass.simpleName} maxNumTokens=${normalizedConfig.contextWindow} cacheDir=$cacheDirPath"
            )

            var eng = newEngine(withVisionAudio, backend, cacheDirPath)
            val initStartMs = System.currentTimeMillis()
            try {
                eng.initialize()
                val initEndMs = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "loadModel ENGINE_INIT: END - duration=${initEndMs - initStartMs}ms backend=${backend.javaClass.simpleName} cacheEnabled=${cacheDirPath != null}"
                )
                return eng
            } catch (first: Throwable) {
                runCatching { eng.close() }

                // XNNPack cache mmap エラー時は cacheDir を無効化して再試行
                if (cacheDirPath != null && isXnnpackMmapFailure(first)) {
                    Log.w(
                        TAG,
                        "Engine init failed with XNNPack cache. Retrying without cacheDir. cacheDir=$cacheDirPath",
                        first
                    )
                    disableXnnpackCacheForProcess = true
                    clearBackendSpecificCache(cacheDirPath)
                    eng = newEngine(withVisionAudio, backend, null)
                    val retryStartMs = System.currentTimeMillis()
                    eng.initialize()
                    val retryEndMs = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "Engine init recovered by disabling XNNPack cache in ${retryEndMs - retryStartMs}ms backend=${backend.javaClass.simpleName}"
                    )
                    return eng
                }

                throw first
            }
        }

        suspend fun loadWithBackend(backend: Backend): Pair<Engine, Boolean> {
            val isGpuBackend = backend is Backend.GPU
            val tryWithVisionAudio = normalizedConfig.requireMultimodal || !isGpuBackend

            if (isGpuBackend && normalizedConfig.requireMultimodal) {
                Log.i(TAG, "GPU backend detected with multimodal request: enabling vision/audio initialization")
            } else if (isGpuBackend) {
                Log.d(TAG, "GPU backend detected: skipping vision/audio initialization to save VRAM")
            }
            
            return runCatching { tryCreate(withVisionAudio = tryWithVisionAudio, backend) to tryWithVisionAudio }
                .getOrElse { first ->
                    Log.w(TAG, "Engine init with vision/audio=${tryWithVisionAudio} failed, retrying text-only", first)
                    tryCreate(withVisionAudio = false, backend) to false
                }
        }

        suspend fun getBackendFallbackChain(preferred: Backend): List<Backend> {
            val npuLibDir = resolveNativeLibraryDirForLitert()
            return when (preferred) {
                is Backend.NPU -> listOf(
                    Backend.NPU(nativeLibraryDir = npuLibDir),
                    Backend.GPU(),
                    Backend.CPU()
                )
                is Backend.GPU -> listOf(Backend.GPU(), Backend.CPU())
                else -> listOf(Backend.CPU())
            }
        }

        suspend fun tryBackendChain(backends: List<Backend>): Triple<Engine, Boolean, Backend> {
            var lastError: Throwable? = null

            for ((index, backend) in backends.withIndex()) {
                try {
                    Log.i(TAG, "Attempting to load with ${backend.javaClass.simpleName} (${index + 1}/${backends.size})")
                    val start = System.currentTimeMillis()
                    val (eng, withVA) = loadWithBackend(backend)
                    val duration = System.currentTimeMillis() - start
                    Log.i(TAG, "Successfully loaded with ${backend.javaClass.simpleName} in ${duration}ms (visionAudio=$withVA)")
                    return Triple(eng, withVA, backend)
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Backend ${backend.javaClass.simpleName} (${index + 1}/${backends.size}) initialization failed: ${e.message}",
                        e
                    )
                    if (index < backends.size - 1) {
                        Log.i(TAG, "Trying next backend in fallback chain...")
                    }
                }
            }

            throw lastError ?: RuntimeException("All backends failed to initialize")
        }

        val fallbackChain = getBackendFallbackChain(preferredBackend)
        try {
            backendResourceManager.prepareBackendSwitch(preferredBackend)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare backend switch", e)
        }
        Log.d(TAG, "Backend fallback chain: ${fallbackChain.map { it.javaClass.simpleName }}")

        val (eng, withVA, usedBackend) = tryBackendChain(fallbackChain)

        try {
            backendResourceManager.registerBackendEngine(eng, usedBackend)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register backend engine", e)
        }

        engine = eng
        loadedModelPath = modelPath
        loadedConfig = normalizedConfig
        loadedBackend = normalizedConfig.backendType
        loadedWithVisionAudio = withVA
        val totalTimeMs = System.currentTimeMillis() - modelStartTimeMs
        Log.d(TAG, "loadModel SUCCESS: $modelPath backend=${normalizedConfig.backendType} totalDuration=${totalTimeMs}ms")
        return Result.success(Unit)
    }

    private fun backendForConfig(backendType: String): Backend {
        return when (backendType.uppercase()) {
            "GPU" -> Backend.GPU()
            "NPU" -> Backend.NPU(nativeLibraryDir = resolveNativeLibraryDirForLitert())
            else -> Backend.CPU()
        }
    }

    /**
     * Phase 11: バックエンド変更時のキャッシュクリア
     * GPU → CPU または CPU → GPU など、バックエンド切り替え時にキャッシュを削除する。
     * 異なるバックエンド間ではキャッシュ形式が互換でない可能性があるため。
     */
    private fun clearBackendSpecificCache(cacheDirPath: String?) {
        if (cacheDirPath.isNullOrBlank()) return
        
        try {
            val cacheDir = File(cacheDirPath)
            if (!cacheDir.exists() || !cacheDir.isDirectory) return
            
            // XNNPack / GPU キャッシュファイルを削除
            val cacheFiles = cacheDir.listFiles { file ->
                file.isFile && (
                    file.name.endsWith(".bin") ||
                    file.name.endsWith(".ckpt") ||
                    file.name.contains("gpu", ignoreCase = true) ||
                    file.name.contains("xnnpack", ignoreCase = true)
                )
            } ?: emptyArray()
            
            cacheFiles.forEach { file ->
                val deleted = file.delete()
                Log.d(TAG, "Cleared backend-specific cache: ${file.name} (deleted=$deleted)")
            }
            
            if (cacheFiles.isNotEmpty()) {
                Log.i(TAG, "Cleared ${cacheFiles.size} backend-specific cache files due to backend change")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing backend-specific cache", e)
        }
    }

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        val normalized = config.normalized()
        val useExtractionConversation = !normalized.enableThinking && sessionId == 0L

        // 通常推論の場合、フラグをセット（抽出推論がこれを見てキャンセルする）
        if (!useExtractionConversation) {
            normalInferenceRequested = true
        }

        // 全ての推論（通常＆抽出）が同じmutexで直列化
        acquireInferenceMutex()

        // 抽出推論がmutexを取った直後に通常推論がリクエストされたら、抽出をキャンセルして即座に終了
        if (useExtractionConversation && normalInferenceRequested) {
            Log.d(TAG, "Memory extraction cancelled due to normal inference request")
            releaseInferenceMutex()
            close()
            return@callbackFlow
        }

        val eng = modelMutex.withLock { engine }
        if (eng == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val visionEnabled = loadedWithVisionAudio
        val hasMultimodalInput = images.isNotEmpty() || audioClips.isNotEmpty()

        // マルチモーダル入力があるのにvisionが無効な場合、requireMultimodal=trueで再ロードを1回だけ試みる
        if (hasMultimodalInput && !visionEnabled) {
            releaseInferenceMutex()
            Log.i(TAG, "Multimodal input detected but engine loaded without vision/audio. Reloading with requireMultimodal=true...")

            val reloadConfig = normalized.copy(requireMultimodal = true)
            val reloadResult = modelMutex.withLock {
                val currentPath = loadedModelPath
                if (currentPath != null) {
                    loadModelLocked(File(currentPath).nameWithoutExtension, reloadConfig)
                } else {
                    Result.failure(IllegalStateException("Cannot reload: model path unknown"))
                }
            }

            if (reloadResult.isFailure) {
                close(reloadResult.exceptionOrNull() ?: RuntimeException("Model reload failed"))
                return@callbackFlow
            }

            // リロード後もvisionが有効にならなかった場合（vision encoder の3 signatures非対応等）
            // 無限ループを防ぐため画像なしのテキスト推論にフォールバックする
            val reloadedWithVision = modelMutex.withLock { loadedWithVisionAudio }
            if (!reloadedWithVision) {
                Log.w(TAG, "Vision encoder unavailable after reload (3-signature encoder not supported by this LiteRT-LM version). Falling back to text-only inference.")
                inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config).collect { chunk ->
                    trySend(chunk).isSuccess
                }
                close()
                return@callbackFlow
            }

            Log.i(TAG, "Model reloaded with vision/audio support. Retrying inference...")
            inferenceWithMedia(sessionId, prompt, images, audioClips, config).collect { chunk ->
                trySend(chunk).isSuccess
            }
            close()
            return@callbackFlow
        }
        try {
            Log.d(TAG, "LiteRT inference session=$sessionId images=${images.size} audio=${audioClips.size} enableThinking=${normalized.enableThinking} visionEnabled=$visionEnabled")

            val conv = if (useExtractionConversation) {
                Log.d(TAG, "Using dedicated memory extraction conversation for session=$sessionId")
                getOrCreateMemoryExtractionConversation(eng, normalized)
            } else {
                Log.d(TAG, "Using regular chat conversation for session=$sessionId")
                getOrCreateConversation(sessionId, eng, normalized)
            }

            val contents = mutableListOf<Content>()
            for (bitmap in images.take(MAX_VISION_IMAGES)) {
                val scaled = scaleBitmapForVision(bitmap)
                try {
                    val imageBytes = scaled.toPngByteArray()
                    if (imageBytes.isNotEmpty()) {
                        contents.add(Content.ImageBytes(imageBytes))
                    } else {
                        Log.w(TAG, "Skipping empty image payload for session=$sessionId")
                    }
                } finally {
                    // 参照が異なる場合のみ recycle する
                    // (scaleBitmapForVision がサイズ内に収まる場合、元の bitmap を返す)
                    if (scaled !== bitmap) {
                        scaled.recycle()
                        Log.d(TAG, "Scaled bitmap recycled (original ${bitmap.width}x${bitmap.height} -> ${scaled.width}x${scaled.height})")
                    }
                }
            }
            for (clip in audioClips) {
                if (clip.isNotEmpty()) {
                    val normalizedAudio =
                        LlmMultimodalAudioHelper.toMono16Bit16kHzWav(appContext, clip)
                    if (normalizedAudio != null && normalizedAudio.isNotEmpty()) {
                        contents.add(Content.AudioBytes(normalizedAudio))
                    } else {
                        // 生バイトをそのまま送るとネイティブ側で落ちる可能性があるためスキップする。
                        Log.w(TAG, "Audio normalization failed; skipping invalid audio payload")
                    }
                }
            }
            if (prompt.trim().isNotEmpty()) {
                contents.add(Content.Text(prompt))
            }

            val extraContext =
                if (normalized.enableThinking) mapOf("enable_thinking" to "true") else emptyMap()

            val answerAccum = StringBuilder()
            val toolResultCards = mutableListOf<ToolResultCard>()
            val generationJob = launch(Dispatchers.IO) {
                try {
                    var inferenceStartMs = System.currentTimeMillis()
                    var firstTokenMs = -1L
                    var tokenCount = 0f
                    var firstRequest = true
                    var pendingToolResponseMessage: Message? = null
                    val maxToolRounds = 5
                    var toolRound = 0
                    while (isActive && toolRound < maxToolRounds) {
                        toolRound++
                        var toolCallsInTurn: List<ToolCall> = emptyList()
                        val messageFlow = if (firstRequest) {
                            firstRequest = false
                            conv.sendMessageAsync(Contents.of(contents), extraContext)
                        } else {
                            conv.sendMessageAsync(
                                pendingToolResponseMessage
                                    ?: throw IllegalStateException("Tool response message missing"),
                                extraContext
                            )
                        }
                        messageFlow.collect { message ->
                            val calls = message.toolCalls
                            if (calls.isNotEmpty()) {
                                toolCallsInTurn = calls
                                trySend(
                                    InferenceStreamProtocol.encodeToolCallChunk(
                                        calls.map { it.name }
                                    )
                                ).isSuccess
                            }
                            val thought = message.channels[THOUGHT_CHANNEL]
                            if (!thought.isNullOrEmpty()) {
                                trySend(InferenceStreamProtocol.encodeThinkChunk(thought)).isSuccess
                            }
                            if (calls.isNotEmpty()) return@collect
                            val text = message.toString()
                            if (shouldEmitPartialText(text)) {
                                // TTFT計測
                                if (firstTokenMs < 0) {
                                    firstTokenMs = System.currentTimeMillis()
                                    val ttft = firstTokenMs - inferenceStartMs
                                    Log.d(TAG, "TTFT: ${ttft}ms session=$sessionId")
                                }
                                
                                // トークン数カウント
                                tokenCount += TextTokenEstimator.estimateOutputTokens(text)
                                
                                // TPS ログ
                                if (tokenCount.toInt() % 10 == 0) {
                                    val elapsedMs = System.currentTimeMillis() - inferenceStartMs
                                    val tps = if (elapsedMs > 0) tokenCount * 1000.0 / elapsedMs else 0.0
                                    Log.d(TAG, "TPS: %.1f tok/s (tokens=%.1f, elapsed=${elapsedMs}ms) session=$sessionId".format(tps, tokenCount))
                                }
                                
                                answerAccum.append(text)
                                trySend(text).isSuccess
                            }
                        }

                        if (toolCallsInTurn.isEmpty()) {
                            break
                        }
                        if (toolRound >= maxToolRounds) {
                            Log.w(TAG, "Tool call loop exceeded max rounds, breaking session=$sessionId")
                            break
                        }
                        // ツール呼び出し時は計測をリセット
                        inferenceStartMs = System.currentTimeMillis()
                        firstTokenMs = -1L
                        tokenCount = 0f
                        val toolResponses = mutableListOf<Content>()
                        // 複数ツール実行を並列化（非ブロッキング）
                        val toolJobs = toolCallsInTurn.map { toolCall ->
                            launch(Dispatchers.IO) {
                                try {
                                    val result = toolExecutor.execute(toolCall)
                                    val status = if (result.success) "success" else "error"
                                    trySend(
                                        InferenceStreamProtocol.encodeToolResultChunk(
                                            toolCall.name,
                                            status
                                        )
                                    ).isSuccess
                                    synchronized(toolResponses) {
                                        toolResponses.add(Content.ToolResponse(toolCall.name, result.payload))
                                    }
                                    // ToolResultCard を蓄積（UI表示用）
                                    synchronized(toolResultCards) {
                                        val jsonPayload = result.payload.mapValues { (_, v) ->
                                            anyToJsonElement(v)
                                        }
                                        toolResultCards.add(
                                            ToolResultCard(
                                                toolName = toolCall.name.lowercase(),
                                                success = result.success,
                                                payload = jsonPayload
                                            )
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Tool execution error: ${toolCall.name}", e)
                                    trySend(
                                        InferenceStreamProtocol.encodeToolResultChunk(
                                            toolCall.name,
                                            "error"
                                        )
                                    ).isSuccess
                                }
                            }
                        }
                        // すべてのツール実行が完了するまで待機
                        toolJobs.forEach { it.join() }
                        pendingToolResponseMessage = Message.Companion.tool(Contents.of(toolResponses))
                    }

                    // 最終サマリーログ
                    val totalElapsed = System.currentTimeMillis() - inferenceStartMs
                    val finalTps = if (totalElapsed > 0) tokenCount * 1000.0 / totalElapsed else 0.0
                    Log.i(TAG, "Inference complete: %.1f tok/s total (tokens=%.1f, ${totalElapsed}ms) session=$sessionId".format(finalTps, tokenCount))

                    // toolResultCards をJSON化して toolResultsJson として送出
                    val toolResultsJson = if (toolResultCards.isNotEmpty()) {
                        ToolResultCard.listToJsonArray(toolResultCards)
                    } else {
                        null
                    }
                    trySend(
                        InferenceStreamProtocol.encodeToolResults(toolResultsJson)
                    ).isSuccess

                    // 実行されたツール一覧を送出（UI表示用）
                    val executedToolNames = toolResultCards.map { it.toolName }.distinct()
                    if (executedToolNames.isNotEmpty()) {
                        trySend(
                            InferenceStreamProtocol.encodeExecutedToolsList(executedToolNames)
                        ).isSuccess
                    }

                    val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                    trySend(finalResult).isSuccess
                    close()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                        trySend(finalResult).isSuccess
                        close()
                    } else {
                        Log.e(TAG, "Inference error session=$sessionId", t)
                        close(if (t is Exception) t else RuntimeException(t))
                    }
                } finally {
                    releaseInferenceMutex()
                    // 通常推論が終了したらフラグをリセット（抽出が再開できるように）
                    if (!useExtractionConversation) {
                        normalInferenceRequested = false
                    }
                }
            }

            awaitClose {
                Log.d(TAG, "awaitClose: cancelling session=$sessionId")
                generationJob.cancel()
                // awaitClose は suspend 不可。runBlocking 禁止のため NonSuspend 版を使用
                if (useExtractionConversation) {
                    cancelMemoryExtractionConversationNonSuspend()
                } else {
                    cancelActiveConversationNonSuspend()
                }
            }
        } catch (t: Throwable) {
            // 例外時は cancel のみ（close は呼ばず）
            if (useExtractionConversation) {
                cancelMemoryExtractionConversationNonSuspend()
            } else {
                cancelActiveConversationNonSuspend()
            }
            // ★ 外側で例外が発生した場合も必ず release する（generationJob.finally が実行されない可能性あり）
            releaseInferenceMutex()
            if (!useExtractionConversation) {
                normalInferenceRequested = false
            }
            if (t is CancellationException) {
                close(t)
                return@callbackFlow
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Inference failed session=$sessionId", e)
            close(e)
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return try {
            modelMutex.withLock {
                Log.d(TAG, "Unloading LiteRT-LM engine with resource cleanup")
                
                // 1. 推論をキャンセル
                cancelActiveConversation()
                cancelMemoryExtractionConversation()
                closeAndResetActiveConversation()
                closeAndResetMemoryExtractionConversation()
                
                // 2. 現在のモデル名を保持してから Engine をクローズ
                val unloadingModelName = loadedModelPath?.let { File(it).name.lowercase() } ?: ""
                runCatching { engine?.close() }
                engine = null
                loadedModelPath = null
                loadedConfig = null
                loadedBackend = null  // Phase 11: バックエンド状態をリセット
                loadedWithVisionAudio = false
                
                // 3. Bitmap メモリプールをクリア
                bitmapPool.clear()
                
                // 4. バックエンドリソースをクリーンアップ
                backendResourceManager.cleanupAll()
                
                // 5. キャッシュをクリーンアップ（XNNPack が使うディレクトリを優先）
                // アンロードするモデルのキャッシュは保護
                CacheManager.cleanupCacheIfNeeded(
                    context = appContext,
                    currentModelBaseName = unloadingModelName,
                    cacheDir = resolveWritableXnnpackCacheDir(),
                    forceScan = false
                )
                
                Log.d(TAG, "LiteRT-LM engine unloaded with full resource cleanup")
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to unload model", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelInference() {
        Log.d(TAG, "Cancelling active inference (cancelProcess only, KV cache preserved)")
        
        // メモリ状態をチェック（キャンセル前に状態ログ出力）
        val memStatus = memoryObserver.getMemoryStatus(appContext)
        Log.d(TAG, "Memory status at cancel: ${memStatus.usedPercent}% (${memStatus.usedMB}MB/${memStatus.maxMB}MB)")
        
        cancelActiveConversation()
        cancelMemoryExtractionConversation()
    }

    suspend fun forceReset() {
        modelMutex.withLock {
            Log.w(TAG, "forceReset: Forcibly invalidating LiteRT-LM engine state without Engine.close()")
            cancelActiveConversation()
            cancelMemoryExtractionConversation()
            closeAndResetActiveConversation()
            closeAndResetMemoryExtractionConversation()

            engine = null
            loadedModelPath = null
            loadedConfig = null
            loadedBackend = null
            loadedWithVisionAudio = false
            bitmapPool.clear()
            // backendResourceManager.cleanupAll() は close 系呼び出しと重複する可能性があるため避ける
        }
    }

    override suspend fun isAvailable(): Boolean = true

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> {
                val obj = value.entries.associate { (k, v) ->
                    (k?.toString() ?: "null") to anyToJsonElement(v)
                }
                JsonObject(obj)
            }
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    /**
     * ビジョン推論用に Bitmap をスケーリングする（メモリ管理強化版）。
     *
     * 実装上の特徴：
     * - MAX_BITMAP_EDGE (1024px) 以下の場合、元の bitmap をそのまま返す（recycle 不要）
     * - MAX_BITMAP_EDGE を超える場合、新しい Bitmap インスタンスを作成してスケーリング
     * - OutOfMemoryError 発生時は、段階的に品質を下げて再試行
     * - BitmapRecycleHelper による安全なスケーリング
     *
     * 呼び出し側で `if (scaled !== bitmap)` チェックを行い、新しいインスタンスの場合のみ recycle すること。
     *
     * @param bitmap スケーリング対象の Bitmap
     * @return スケーリング済みの Bitmap（元の bitmap または新規作成された Bitmap）
     */
    private suspend fun scaleBitmapForVision(bitmap: Bitmap): Bitmap {
        // メモリ監視：スケーリング前のメモリ状態をチェック
        val memoryOk = memoryObserver.requestMemoryCorrectionIfNeeded(appContext)
        if (!memoryOk) {
            Log.w(TAG, "Memory insufficient for bitmap scaling, returning original")
            return bitmap
        }
        
        // BitmapRecycleHelper を使用した安全なスケーリング
        return BitmapRecycleHelper.safeScaleBitmap(
            bitmap,
            maxWidth = MAX_BITMAP_EDGE,
            maxHeight = MAX_BITMAP_EDGE,
            initialQuality = 100
        )
    }

    private fun resolveModelPath(modelName: String): String {
        val lowered = modelName.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(modelName).isAbsolute) {
            return modelName
        }
        return when (ModelFileManager.resolveModelName(modelName)) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "gemma-3n-2b.task"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "gemma-3n-4b.task"
            ModelFileManager.LocalModel.GEMMA4_2B -> "gemma-4-2b.litertlm"
            ModelFileManager.LocalModel.GEMMA4_4B -> "gemma-4-4b.litertlm"
        }
    }

    private fun resolveLocalModelFile(modelName: String): File? {
        val resolved = resolveModelPath(modelName)
        val lowered = resolved.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(resolved).isAbsolute) {
            val file = File(resolved)
            val validated = ModelFileManager.validateImportedTaskFile(file)
            if (validated.isFailure) {
                val cause = validated.exceptionOrNull()
                Log.w(TAG, "Imported model validation failed: ${cause?.message}")
                if (cause?.message?.contains("Web用モデル") == true) {
                    throw cause
                }
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
