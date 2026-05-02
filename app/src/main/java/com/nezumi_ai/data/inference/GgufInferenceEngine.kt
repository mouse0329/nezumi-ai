package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.nezumi_ai.data.inference.rnllama.RnLlamaContext
import com.nezumi_ai.utils.ImportedModelCapabilityStore
import java.io.File
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue

/**
 * llama.cpp (GGUF) 推論エンジン。llamacpp-kotlin 0.4.0 の LlamaHelper を使用。
 *
 * [AIInferenceEngine] を実装し、LiteRtLmEngine と差し替え可能。
 * 出力は [InferenceStreamProtocol] 準拠なので ViewModel / Repository の変更不要。
 *
 * ## 制約
 * - GPU オフロードは nGpuLayers=0 固定（Tensor G3 は OpenCL 非対応）
 * - マルチモーダル: JNI が mmproj 未指定時はベース GGUF から clip/mtmd 初期化（統合型）。別ファイルが必要なら設定で mmproj を渡す
 * - KVキャッシュは LlamaHelper 内部の contextId で管理
 * - セッション変更時も同一コンテキストを使いまわす（完全リセットは unload→load）
 */
class GgufInferenceEngine(private val context: Context) : AIInferenceEngine {

    companion object {
        private const val TAG = "GgufInferenceEngine"
        // "User:" 以降に次ターンを自己生成してしまうのを防ぐ
        /** シンキングON時はストリーム先頭に `<think>` が無いことがあり、終了タグまで本文を送らない */
        private const val REDACTED_THINK_CLOSE = "</think>"
        private const val REDACTED_THINK_OPEN = "<think>"

        private val STOP_SEQUENCES = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<end_of_turn>",
            "<start_of_turn>",
            "User:",
            "User：",
            "\nUser:",
            "\r\nUser:",
            " User:",
            "Assistant:",
            "Assistant：",
            "\nAssistant:",
            "\r\nAssistant:",
            " Assistant:",
            "ユーザー:",
            "ユーザー：",
            "\nユーザー:",
            "\r\nユーザー:",
            " ユーザー:",
            "アシスタント:",
            "アシスタント：",
            "\nアシスタント:",
            "\r\nアシスタント:",
            " アシスタント:"
        )
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)

    @Volatile private var llamaContext: RnLlamaContext? = null
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedConfig: InferenceConfig? = null
    @Volatile private var isModelLoaded = false
    @Volatile private var lastSessionId: Long? = null
    @Volatile private var currentInferenceJob: Job? = null
    private val tokenHandlerRef = java.util.concurrent.atomic.AtomicReference<((String) -> Unit)?>(null)

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return modelMutex.withLock {
            try {
                // ★ 新しいモデルをロードする前に、実行中の推論が完全に終了したことを確認
                currentInferenceJob?.let { job ->
                    Log.d(TAG, "loadModel: Waiting for ongoing inference to complete before loading new model")
                    try {
                        withTimeoutOrNull(10000L) {
                            job.join()
                            Log.d(TAG, "loadModel: Previous inference job completed")
                        } ?: run {
                            Log.w(TAG, "loadModel: Previous inference job still running after 10s - forcing cancel")
                            job.cancel()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "loadModel: Error waiting for previous job", e)
                    }
                }

                val normalized = config.normalized()
                val modelFile = resolveModelFile(modelName)
                    ?: return@withLock Result.failure(
                        IllegalStateException("GGUF model file not found: $modelName")
                    )
                val modelPath = modelFile.absolutePath

                if (isModelLoaded &&
                    loadedModelPath == modelPath &&
                    loadedConfig == normalized
                ) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return@withLock Result.success(Unit)
                }

                releaseHelper()

                Log.i(TAG, "Loading GGUF: $modelPath nCtx=${normalized.contextWindow}")

                // ★ rnllama (llama.rn rc.9) をソースビルドした JNI ブリッジで初期化
                try {
                    // ★ llama.cpp パラメータ最適化（安定性とパフォーマンスのバランス）
                    val requestedThreads = normalized.llamaCppThreads
                    val requestedBatchSize = normalized.llamaCppBatchSize
                    val requestedGpuLayers = normalized.llamaCppGpuLayers
                    val requestedNKeep = normalized.llamaCppNKeep
                    val numCores = Runtime.getRuntime().availableProcessors()
                    val appliedThreads = (numCores / 2).coerceIn(1, 4)  // スレッド数削減（オーバーヘッド低減）
                    val appliedBatchSize = 32.coerceAtMost(requestedBatchSize)  // バッチサイズ削減（メモリ効率）
                    val appliedGpuLayers = 0  // GPU 無効化（Tensor G3 は OpenCL 非対応）
                    val appliedNKeep = 0  // KV キャッシュ無効化

                    Log.d(TAG, "Creating rnllama context with model_path=$modelPath")
                    if (requestedGpuLayers != appliedGpuLayers || requestedNKeep != appliedNKeep) {
                        Log.w(
                            TAG,
                            "GGUF requested settings are overridden for compatibility: " +
                                "requested(n_gpu_layers=$requestedGpuLayers, n_keep=$requestedNKeep) -> " +
                                "applied(n_gpu_layers=$appliedGpuLayers, n_keep=$appliedNKeep)"
                        )
                    }
                    Log.d(
                        TAG,
                        "Params(applied): n_ctx=${normalized.contextWindow}, n_threads=$appliedThreads, " +
                            "n_batch=$appliedBatchSize, n_gpu_layers=$appliedGpuLayers, n_keep=$appliedNKeep, " +
                            "rope_freq_base=${normalized.llamaCppRopeFreqBase}, rope_freq_scale=${normalized.llamaCppRopeFreqScale}"
                    )

                    // ★ マルチモーダル初期化: 明示的なmmproj パスがない場合でも、
                    // 本体 GGUF ファイルから ビジョンテンソルを自動検出するため、JNI 側で
                    // mmproj_effective = modelPath を使用。Java 側では null を渡す方針を確認。
                    val mmprojPathFromSettings = ImportedModelCapabilityStore.get(context, modelPath).mmprojPath
                        ?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
                    
                    Log.d(TAG, "Creating RnLlamaContext: modelPath=$modelPath, mmprojPath=${mmprojPathFromSettings ?: "(null -> auto-detect from model)"}")
                    
                    val lc = RnLlamaContext(
                        modelPath = modelPath,
                        nCtx = normalized.contextWindow,
                        nBatch = appliedBatchSize,
                        nThreads = appliedThreads,
                        nGpuLayers = appliedGpuLayers,
                        mmprojPath = mmprojPathFromSettings
                    )
                    if (!lc.isValid) {
                        throw IllegalStateException("Failed to initialize rnllama context")
                    }

                    lc.setTokenCallback { token ->
                        tokenHandlerRef.get()?.invoke(token)
                    }

                    llamaContext = lc
                    loadedModelPath = modelPath
                    loadedConfig = normalized
                    isModelLoaded = true
                    lastSessionId = null

                    Log.i(TAG, "GGUF model loaded OK (rnllama): $modelPath")
                    Result.success(Unit)
                } catch (t: Exception) {
                    Log.e(TAG, "LlamaContext initialization failed", t)
                    Result.failure(t)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    // ─── アンロード ──────────────────────────────────────────────

    override suspend fun unloadModel(): Result<Unit> {
        return modelMutex.withLock {
            try {
                // ★ アンロード前に、実行中の推論ジョブの完全終了を待つ
                currentInferenceJob?.let { job ->
                    Log.d(TAG, "unloadModel: Waiting for ongoing inference to complete before release")
                    try {
                        // 最大15秒（ネイティブ層の確実な終了を待つ）
                        withTimeoutOrNull(15000L) {
                            job.join()
                            Log.d(TAG, "unloadModel: Inference job completed successfully")
                        } ?: run {
                            Log.w(TAG, "unloadModel: Inference job still running after 15s - attempting soft cancel")
                            // ★ Soft cancel（強制ではなく、丁寧にキャンセル）
                            job.cancel()
                            // さらに2秒待ってみる
                            try {
                                withTimeoutOrNull(2000L) {
                                    job.join()
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "unloadModel: Final join attempt failed", e)
                            }
                        }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "unloadModel: Job cancelled during join")
                    } catch (e: Exception) {
                        Log.w(TAG, "unloadModel: Unexpected error waiting for job", e)
                    }
                }

                releaseHelper()
                Log.i(TAG, "GGUF model unloaded")
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to unload model", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    private fun releaseHelper() {
        llamaContext?.release()
        llamaContext = null

        loadedModelPath = null
        loadedConfig = null
        isModelLoaded = false
        lastSessionId = null
        Log.d(TAG, "Model and LlamaContext released")
    }

    // ─── キャンセル ───────────────────────────────────────────────

    override suspend fun cancelInference() {
        Log.d(TAG, "cancelInference called")
        currentInferenceJob?.let { job ->
            Log.d(TAG, "Cancelling ongoing inference job")
            // Signal native side to stop the completion loop immediately
            modelMutex.withLock { llamaContext }?.interrupt()
            job.cancel(CancellationException("User cancelled inference"))
            try {
                withTimeoutOrNull(10000L) {
                    job.join()
                    Log.d(TAG, "Inference job cancelled and joined successfully")
                } ?: run {
                    Log.w(TAG, "Inference job cancellation timeout after 10s - job may still be running")
                }
            } catch (e: Exception) {
                Log.d(TAG, "Inference job cancellation completed with exception: ${e.message}")
            }
        } ?: run {
            Log.d(TAG, "No ongoing inference to cancel")
        }
    }

    // ─── 推論 ─────────────────────────────────────────────────────

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    /**
     * GGUF 推論。
     *
     * LlamaContext.completion() を直接呼び出し、
     * token callback で trySend してフロー化する。
     */
    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)

        val ctx = modelMutex.withLock { llamaContext }
        if (ctx == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        if (lastSessionId != sessionId) {
            Log.d(TAG, "Session: $lastSessionId → $sessionId")
            lastSessionId = sessionId
        }

        val answerAccum = StringBuilder()
        val tokenBuffer = StringBuilder()
        var shouldStop = AtomicBoolean(false)
        val BUFFER_FLUSH_CHARS = 8
        val jobDeferred = kotlinx.coroutines.CompletableDeferred<Job>()
        /** LiteRT-LM と同じく思考は encodeThinkChunk、本文はプレーン chunks（FINAL は本文のみ） */
        var emittedThinkingPrefix = ""
        var emittedAnswerPrefix = ""

        fun flushAnswerBufferOnly() {
            val remaining = tokenBuffer.toString()
            tokenBuffer.clear()
            if (remaining.isNotEmpty()) runCatching { trySend(remaining) }
        }

        fun stripIncompleteCloseTagSuffix(raw: String, closeTag: String): String {
            if (raw.endsWith(closeTag)) return raw
            for (len in closeTag.length - 1 downTo 1) {
                val pref = closeTag.take(len)
                if (raw.endsWith(pref)) return raw.dropLast(len)
            }
            return raw
        }

        fun emitThinkingDeltaChunk(curThinking: String) {
            val thinkDelta = when {
                curThinking.startsWith(emittedThinkingPrefix) ->
                    curThinking.substring(emittedThinkingPrefix.length)
                else -> {
                    if (curThinking.length < emittedThinkingPrefix.length) {
                        Log.w(TAG, "Thinking stream prefix shrank; resetting bridge state")
                    }
                    emittedThinkingPrefix = ""
                    curThinking
                }
            }
            emittedThinkingPrefix = curThinking
            if (thinkDelta.isNotEmpty()) {
                runCatching { trySend(InferenceStreamProtocol.encodeThinkChunk(thinkDelta)) }
            }
        }

        fun emitAnswerDeltaChunk(curAnswer: String) {
            val answerDelta = when {
                curAnswer.startsWith(emittedAnswerPrefix) ->
                    curAnswer.substring(emittedAnswerPrefix.length)
                else -> {
                    if (curAnswer.length < emittedAnswerPrefix.length) {
                        Log.w(TAG, "Answer stream prefix shrank; resetting bridge state")
                    }
                    emittedAnswerPrefix = ""
                    curAnswer
                }
            }
            emittedAnswerPrefix = curAnswer
            if (answerDelta.isEmpty()) return
            tokenBuffer.append(answerDelta)
            if (tokenBuffer.length >= BUFFER_FLUSH_CHARS) {
                val chunk = tokenBuffer.toString()
                tokenBuffer.clear()
                runCatching { trySend(chunk) }
            }
        }

        /** シンキングOFF: 従来どおり parseStreaming で分割 */
        fun emitParseStreamingSplit() {
            val parsed = Gemma4ThinkingParser.parseStreaming(answerAccum.toString())
            val curThinking = parsed.thinking ?: ""
            val curAnswer = parsed.answer
            emitThinkingDeltaChunk(curThinking)
            emitAnswerDeltaChunk(curAnswer)
        }

        /**
         * シンキングON: `</think>` が現れるまで本文を送らず、思考のみストリームする。
         * 先頭に `<think>` が無い出力でも、閉じタグで思考ブロックを確定する。
         */
        fun emitWaitForCloseTagSplit() {
            val full = answerAccum.toString()
            val closeIdx = full.indexOf(REDACTED_THINK_CLOSE)
            if (closeIdx < 0) {
                val safeThink = stripIncompleteCloseTagSuffix(full, REDACTED_THINK_CLOSE)
                emitThinkingDeltaChunk(safeThink)
                return
            }
            var thinkBody = full.substring(0, closeIdx)
            if (thinkBody.startsWith(REDACTED_THINK_OPEN)) {
                thinkBody = thinkBody.removePrefix(REDACTED_THINK_OPEN).trimStart()
            }
            val afterClose = full.substring(closeIdx + REDACTED_THINK_CLOSE.length)
            emitThinkingDeltaChunk(thinkBody)
            emitAnswerDeltaChunk(afterClose)
        }

        fun emitStreamDeltas(enableThinkingMode: Boolean) {
            if (enableThinkingMode) emitWaitForCloseTagSplit() else emitParseStreamingSplit()
        }

        fun finalAnswerForProtocol(enableThinkingMode: Boolean): String {
            val raw = answerAccum.toString()
            if (!enableThinkingMode) {
                return Gemma4ThinkingParser.parse(raw).answer
            }
            val idx = raw.indexOf(REDACTED_THINK_CLOSE)
            return if (idx >= 0) {
                Gemma4ThinkingParser.sanitizeVisibleText(raw.substring(idx + REDACTED_THINK_CLOSE.length))
            } else {
                ""
            }
        }

        try {
            val completionJob = launch(Dispatchers.IO) {
                var enableThinkingMode = false
                try {
                    Log.d(TAG, "GGUF started session=$sessionId")

                    // ★ 推論実行（maxTokens に基づいてタイムアウトを計算）
                    val normalized = modelMutex.withLock { loadedConfig?.normalized() } ?: config.normalized()
                    enableThinkingMode = normalized.enableThinking
                    val timeoutSeconds = (normalized.maxTokens / 10).coerceIn(5, 300)  // 最小5秒、最大300秒
                    val effectiveStopSequences = if (normalized.customStopTokens.isEmpty()) {
                        STOP_SEQUENCES
                    } else {
                        (normalized.customStopTokens + STOP_SEQUENCES).distinct()
                    }
                    val sortedStops = effectiveStopSequences.sortedByDescending { it.length }

                    // ★ トークンハンドラを設定（loadModel時に一度だけ設定された setTokenCallback が使用する）
                    tokenHandlerRef.set(handler@{ token ->
                        if (!isActive || shouldStop.get()) {
                            Log.d(TAG, "Token callback: ignoring (stopped or not active)")
                            return@handler
                        }

                        // ★ トークンを answerAccum に追加してから stop sequence をチェック
                        answerAccum.append(token)

                        // ★ Stop sequences を手動で検出（llamacpp-kotlin が実装していない可能性対策）
                        var foundStop = false
                        var stopSeq = ""
                        for (seq in sortedStops) {
                            if (answerAccum.endsWith(seq)) {
                                foundStop = true
                                stopSeq = seq
                                // Stop sequence を answerAccum から除去
                                answerAccum.setLength(answerAccum.length - seq.length)
                                break
                            }
                        }

                        if (foundStop) {
                            Log.d(TAG, "Stop sequence detected: '$stopSeq'. Stopping generation. accum_len=${answerAccum.length}")
                            shouldStop.set(true)
                            emitStreamDeltas(enableThinkingMode)
                            flushAnswerBufferOnly()
                            // ★ token callback を無効化（以降のコールバックを無視）
                            tokenHandlerRef.set(null)
                            // ★ ctx.completion() はブロッキング呼び出しなので、コルーチンをキャンセルして unblock
                            currentInferenceJob?.cancel()
                            return@handler
                        }

                        emitStreamDeltas(enableThinkingMode)
                    })

                    Log.d(TAG, "Completion starting with timeout=${timeoutSeconds}s")
                    val mediaFiles = if (images.isNotEmpty()) {
                        persistImagesForGguf(images)
                    } else {
                        emptyList()
                    }
                    try {
                        val result = withTimeoutOrNull(timeoutSeconds * 1000L) {
                            if (mediaFiles.isEmpty()) {
                                ctx.complete(
                                    prompt = prompt,
                                    nPredict = normalized.maxTokens,
                                    temperature = normalized.temperature,
                                    topP = normalized.topP,
                                    topK = normalized.maxTopK,
                                    stopWords = effectiveStopSequences.toTypedArray()
                                )
                            } else {
                                ctx.completeWithMedia(
                                    prompt = prompt,
                                    nPredict = normalized.maxTokens,
                                    temperature = normalized.temperature,
                                    topP = normalized.topP,
                                    topK = normalized.maxTopK,
                                    stopWords = effectiveStopSequences.toTypedArray(),
                                    mediaPaths = mediaFiles.map { it.absolutePath }.toTypedArray()
                                )
                            }
                        }

                        if (result == null) {
                            Log.w(TAG, "GGUF timeout session=$sessionId")
                        } else {
                            Log.d(TAG, "GGUF done session=$sessionId answer_length=${answerAccum.length}")
                        }
                    } catch (e: CancellationException) {
                        // ★ Stop sequence 検出時に completionJob.cancel() が呼ばれた場合
                        Log.d(TAG, "GGUF cancelled by stop sequence detection session=$sessionId answer_length=${answerAccum.length}")
                        // answerAccum は既に stop sequence が除去されている
                    } finally {
                        mediaFiles.forEach { f ->
                            runCatching {
                                if (f.exists()) f.delete()
                            }.onFailure {
                                Log.w(TAG, "Failed to delete temporary GGUF media file: ${f.absolutePath}", it)
                            }
                        }
                    }

                    flushAnswerBufferOnly()

                    trySend(InferenceStreamProtocol.encodeFinal(finalAnswerForProtocol(enableThinkingMode)))
                    close()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        Log.d(TAG, "GGUF cancelled session=$sessionId answer_length=${answerAccum.length}")
                        flushAnswerBufferOnly()
                        runCatching {
                            trySend(InferenceStreamProtocol.encodeFinal(finalAnswerForProtocol(enableThinkingMode)))
                        }
                        close()
                    } else {
                        Log.e(TAG, "completion error", t)
                        close(if (t is Exception) t else RuntimeException(t))
                    }
                } finally {
                    Log.d(TAG, "GGUF finally - cleaning up session=$sessionId")
                    tokenHandlerRef.set(null)
                    shouldStop.set(true)
                    currentInferenceJob = null
                    releaseInferenceMutex()
                }
            }
            // ★ launch 直前に currentInferenceJob を設定（completionJob の finally よりも先に代入確定）
            currentInferenceJob = completionJob
            jobDeferred.complete(completionJob)
        } catch (t: Throwable) {
            releaseInferenceMutex()
            close(if (t is Exception) t else RuntimeException(t))
        }

        // ★ キャンセル時のクリーンアップ
        // mutex はここで触らない。completionJob.finally() が必ず呼ばれるので所有権を一本化
        awaitClose {
            Log.d(TAG, "Flow cancelled - stopping inference (isActive=$isActive)")
            // ★ shouldStop をセットしてトークンハンドラを無効化（tokenHandlerRef のクリアは finally に任せる）
            shouldStop.set(true)
            // jobDeferred が complete 済みなら直接キャンセル、未完了なら currentInferenceJob にフォールバック
            (if (jobDeferred.isCompleted) jobDeferred.getCompleted() else currentInferenceJob)?.cancel()
        }
    }

    // ─── ユーティリティ ──────────────────────────────────────────

    override suspend fun isAvailable(): Boolean = isModelLoaded

    fun getLastGenerationTokenCount(): Float? = llamaContext?.getLastTimings()?.decodeTokens

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex: double-release guard")
        }
    }

    /**
     * modelName から File を解決する。
     * 絶対パス + .gguf 拡張子のみ対応。
     * 将来的には ModelFileManager に GGUF エントリを追加して統合する。
     */
    private fun resolveModelFile(modelName: String): File? {
        if (modelName.endsWith(".gguf", ignoreCase = true) && modelName.startsWith("/")) {
            val f = File(modelName)
            return if (f.exists() && f.canRead()) f else null
        }
        Log.w(TAG, "resolveModelFile: unsupported format: $modelName")
        return null
    }

    private fun persistImagesForGguf(images: List<Bitmap>): List<File> {
        if (images.isEmpty()) return emptyList()
        val dir = File(context.filesDir, "message_media/gguf_temp").apply { mkdirs() }
        val files = mutableListOf<File>()
        images.forEachIndexed { index, bitmap ->
            val out = File(dir, "gguf_img_${System.currentTimeMillis()}_${index}.jpg")
            runCatching {
                out.outputStream().use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
                }
                files += out
            }.onFailure {
                Log.e(TAG, "Failed to persist bitmap for GGUF media", it)
                runCatching { if (out.exists()) out.delete() }
            }
        }
        return files
    }
}

