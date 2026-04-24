package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.collect
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
    private var disableXnnpackCacheForProcess: Boolean = false
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)
    private val alarmDao by lazy { NezumiAiDatabase.getInstance(appContext).alarmDao() }
    private val toolExecutor by lazy { NezumiLiteRtToolExecutor(appContext, alarmDao) }

    /** Engine は同時に 1 セッションのみ。コールバックスレッドと awaitClose の競合もここで直列化する */
    private val activeConversationLock = Any()
    /** activeConversationLock で保護。@Volatile 不要（全アクセスが synchronized 内） */
    private var activeLiteRtConversation: Conversation? = null
    
    /** セッション遷移検出用 */
    @Volatile
    private var lastSessionId: Long? = null
    
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

    /**
     * AI Edge Gallery 方式：推論キャンセル時は cancelProcess() だけ。
     * close() はセッション遷移時のみ。
     */
    private fun cancelActiveConversation() {
        synchronized(activeConversationLock) {
            val c = activeLiteRtConversation ?: return
            runCatching {
                Log.d(TAG, "Cancelling active conversation process")
                c.cancelProcess()
            }.onFailure { t ->
                Log.w(TAG, "Failed to cancel conversation process", t)
            }
        }
    }

    /**
     * セッション遷移時のみ使用。古い Conversation を close() して新たに作成する。
     */
    private fun closeAndResetActiveConversation() {
        synchronized(activeConversationLock) {
            val c = activeLiteRtConversation ?: return
            activeLiteRtConversation = null
            runCatching {
                Log.d(TAG, "Closing active conversation")
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close conversation", t)
            }
            Log.i(TAG, "Active conversation closed and reset")
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
        synchronized(activeConversationLock) {
            // セッションが変わった場合、または Conversation が未作成の場合はリセット
            if (lastSessionId != sessionId || activeLiteRtConversation == null) {
                Log.i(TAG, "Session change or no conversation. Creating new: lastSessionId=$lastSessionId, newSessionId=$sessionId")
                closeAndResetActiveConversation()

                val normalized = config.normalized()
                ExperimentalFlags.enableConversationConstrainedDecoding = false
                val samplerConfig = if (normalized.backendType == "NPU") {
                    null
                } else {
                    // Note: LiteRT-LM の SamplerConfig では maxOutputTokens が無いため、
                    // EngineConfig.maxNumTokens（コンテキストウィンドウ）で全体制限を行う
                    // 生成トークン数は InferenceConfig.maxTokens で管理
                    SamplerConfig(
                        topK = normalized.maxTopK,
                        topP = normalized.topP.toDouble(),
                        temperature = normalized.temperature.toDouble()
                    )
                }

                val conv = eng.createConversation(
                    ConversationConfig(
                        tools = buildEnabledToolProviders(appContext, alarmDao),
                        samplerConfig = samplerConfig,
                        automaticToolCalling = false
                    )
                )
                // 投機的デコーディング設定を適用
                ExperimentalFlags.enableSpeculativeDecoding = normalized.enableSpeculativeDecoding
                ExperimentalFlags.enableConversationConstrainedDecoding = false
                activeLiteRtConversation = conv
                lastSessionId = sessionId
                Log.d(TAG, "New conversation created for session=$sessionId, KVCache initialized")
            } else {
                Log.d(TAG, "Conversation reused for session=$sessionId, KVCache preserved")
            }
            return activeLiteRtConversation!!
        }
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
            loadedConfig != normalizedConfig ||
            loadedBackend != normalizedConfig.backendType ||
            engine == null

        if (!needsReload) {
            Log.d(TAG, "Model already loaded: $modelPath backend=${normalizedConfig.backendType}")
            return Result.success(Unit)
        }

        val preferredBackend = backendForConfig(normalizedConfig.backendType)
        val cacheDir = resolveWritableXnnpackCacheDir()
        val cacheDirPath = cacheDir?.absolutePath
        val backendChanged = loadedBackend != null && loadedBackend != normalizedConfig.backendType
        if (backendChanged) {
            Log.i(TAG, "Backend changed from $loadedBackend to ${normalizedConfig.backendType}. Clearing cache...")
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
                // ★ バグ修正: maxNumTokens はコンテキストウィンドウ全体のサイズ（KVキャッシュ）
                // maxTokens は「生成トークン数」なので、EngineConfig には contextWindow を指定
                // 最低でも2000以上必要（システムプロンプト + ツール定義 + コンテキスト + 生成トークン）
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

        suspend fun loadWithBackend(backend: Backend): Engine {
            // Phase 16: GPU時の Vision/Audio バックエンド条件付き無効化
            // GPU は VRAM 限定的（2-4GB）のため、マルチモーダル対応を制限
            // - GPU: vision/audio 初期化をスキップ（テキストのみモード）
            // - CPU/NPU: 通常通り vision/audio も初期化を試行
            val isGpuBackend = backend is Backend.GPU
            val tryWithVisionAudio = !isGpuBackend
            
            if (isGpuBackend) {
                Log.d(TAG, "GPU backend detected: skipping vision/audio initialization to save VRAM")
            }
            
            return runCatching { tryCreate(withVisionAudio = tryWithVisionAudio, backend) }
                .getOrElse { first ->
                    Log.w(TAG, "Engine init with vision/audio=${tryWithVisionAudio} failed, retrying text-only", first)
                    tryCreate(withVisionAudio = false, backend)
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

        suspend fun tryBackendChain(backends: List<Backend>): Engine {
            var lastError: Throwable? = null

            for ((index, backend) in backends.withIndex()) {
                try {
                    Log.i(TAG, "Attempting to load with ${backend.javaClass.simpleName} (${index + 1}/${backends.size})")
                    val start = System.currentTimeMillis()
                    val eng = loadWithBackend(backend)
                    val duration = System.currentTimeMillis() - start
                    Log.i(TAG, "Successfully loaded with ${backend.javaClass.simpleName} in ${duration}ms")
                    return eng
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
        Log.d(TAG, "Backend fallback chain: ${fallbackChain.map { it.javaClass.simpleName }}")

        val eng = tryBackendChain(fallbackChain)

        engine = eng
        loadedModelPath = modelPath
        loadedConfig = normalizedConfig
        loadedBackend = normalizedConfig.backendType
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
        acquireInferenceMutex()

        val eng = modelMutex.withLock { engine }
        if (eng == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val normalized = modelMutex.withLock { loadedConfig?.normalized() } ?: config.normalized()

        try {
            Log.d(TAG, "LiteRT inference session=$sessionId images=${images.size} audio=${audioClips.size} enableThinking=${normalized.enableThinking}")

            // getOrCreateConversation() で自動的にセッション管理と KVCache 再利用を処理
            val conv = getOrCreateConversation(sessionId, eng, normalized)

            val contents = mutableListOf<Content>()
            for (bitmap in images.take(MAX_VISION_IMAGES)) {
                val scaled = scaleBitmapForVision(bitmap)
                try {
                    contents.add(Content.ImageBytes(scaled.toPngByteArray()))
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
                    val normalized =
                        LlmMultimodalAudioHelper.toMono16Bit16kHzWav(appContext, clip)
                    if (normalized != null && normalized.isNotEmpty()) {
                        contents.add(Content.AudioBytes(normalized))
                    } else {
                        // 変換失敗時は従来どおり生バイトをフォールバックとして送る
                        Log.w(TAG, "Audio normalization failed; sending raw audio bytes")
                        contents.add(Content.AudioBytes(clip))
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
                    var firstRequest = true
                    var pendingToolResponseMessage: Message? = null
                    while (true) {
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
                                answerAccum.append(text)
                                trySend(text).isSuccess
                            }
                        }

                        if (toolCallsInTurn.isEmpty()) {
                            break
                        }
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
                }
            }

            awaitClose {
                Log.d(TAG, "awaitClose: cancelling session=$sessionId")
                generationJob.cancel()
                cancelActiveConversation()
                closeAndResetActiveConversation()
            }
        } catch (t: Throwable) {
            // 例外時は cancel のみ（close は呼ばず）
            cancelActiveConversation()
            releaseInferenceMutex()
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
                closeAndResetActiveConversation()
                
                // 2. Engine をクローズ
                runCatching { engine?.close() }
                engine = null
                loadedModelPath = null
                loadedConfig = null
                loadedBackend = null  // Phase 11: バックエンド状態をリセット
                
                // 3. Bitmap メモリプールをクリア
                bitmapPool.clear()
                
                // 4. バックエンドリソースをクリーンアップ
                backendResourceManager.cleanupAll()
                
                // 5. キャッシュをクリーンアップ（XNNPack が使うディレクトリを優先）
                CacheManager.cleanupCacheIfNeeded(
                    context = appContext,
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
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && modelName.startsWith("/")) {
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
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && resolved.startsWith("/")) {
            val file = File(resolved)
            val validated = ModelFileManager.validateImportedTaskFile(file)
            if (validated.isFailure) {
                Log.w(TAG, "Imported model validation failed: ${validated.exceptionOrNull()?.message}")
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
