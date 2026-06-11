package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp (GGUF) 推論エンジン。
 *
 * [AIInferenceEngine] を実装し、LiteRtLmEngine と差し替え可能。
 * 出力フォーマットは [InferenceStreamProtocol] に準拠するため、
 * 上位レイヤー（ViewModel / Repository）はエンジン種別を意識しない。
 *
 * ## セッション管理
 * - 同一 sessionId の間は KV キャッシュを保持し、ターン間の速度を向上させる。
 * - sessionId が変わった場合は llamaClearKvCache() を呼んでリセットする。
 *
 * ## スレッドモデル
 * - [loadModel] / [unloadModel] は [modelMutex] で直列化
 * - [inference] は [inferenceMutex] で直列化（同時推論1本のみ）
 * - JNI 呼び出しは Dispatchers.IO で実行
 *
 * ## キャンセル
 * - [cancelInference] で [cancelFlag] を立てるだけ
 * - 推論ループが毎トークン後にフラグを確認して脱出する
 * - LiteRT 側の cancelProcess() 相当
 */
class GgufInferenceEngine(
    private val appContext: Context
) : AIInferenceEngine {

    companion object {
        private const val TAG = "GgufInferenceEngine"

        // llama.cpp デフォルト値
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val MAX_NEW_TOKENS = 4096
        private const val CHUNK_SIZE = 8  // トークンをチャンク単位で送信

        // ロールプレイループ・自己対話を防ぐ停止シーケンス
        internal val DEFAULT_STOP_SEQUENCES = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<end_of_turn>",
            "<start_of_turn>",
            "User:",
            "User：",
            "\nUser:",
            " User:",
            "Assistant:",
            "Assistant：",
            "\nAssistant:",
            " Assistant:",
            "ユーザー:",
            "ユーザー：",
            "\nユーザー:",
            " ユーザー:",
            "アシスタント:",
            "アシスタント：",
            "\nアシスタント:",
            " アシスタント:"
        )

        private fun effectiveStopSequences(config: InferenceConfig): List<String> {
            val custom = config.customStopTokens.map { it.trim() }.filter { it.isNotEmpty() }
            return DEFAULT_STOP_SEQUENCES + custom
        }

        /**
         * デバイスのCPUコア数に基づいて最適なスレッド数を計算
         */
        private fun getOptimalThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            // 物理コア数の推定（ハイパースレッディング考慮）
            val physicalCores = (cores / 2).coerceAtLeast(1)
            // 推論には物理コア数 - 1 を使用（UIスレッド用に1コア残す）
            return (physicalCores - 1).coerceAtLeast(2).coerceAtMost(8)
        }

        // チャットテンプレート（Gemma / ChatML 等に応じて変更）
        private const val ROLE_USER = "user"
        private const val ROLE_MODEL = "model"

        /**
         * InferenceConfig の backendType を GPU オフロード層数に変換する。
         * llama.cpp は層数ベースで GPU オフロードを制御する。
         * "GPU" → 全層オフロード (999)、それ以外 → 0 (CPU)
         */
        private fun nGpuLayersForBackend(backendType: String): Int = when (backendType.uppercase()) {
            "GPU" -> 999  // 全層 GPU オフロード
            "AUTO" -> -1  // 自動判定（llama.cppが最適値を選択）
            else -> 0
        }

        /**
         * メモリ使用量に基づいてGPU層数を動的に調整
         */
        private fun getAdaptiveGpuLayers(backendType: String): Int {
            if (backendType.uppercase() != "GPU") return 0
            
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val availableMemory = maxMemory - usedMemory
            
            // 利用可能メモリに応じてGPU層数を調整
            return when {
                availableMemory > 6L * 1024 * 1024 * 1024 -> 999  // 6GB以上: 全層
                availableMemory > 4L * 1024 * 1024 * 1024 -> 35   // 4-6GB: 大部分
                availableMemory > 2L * 1024 * 1024 * 1024 -> 20   // 2-4GB: 半分
                else -> 0  // 2GB未満: CPU
            }
        }
    }

    // ─── 状態 ────────────────────────────────────────────────────

    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)

    /** llama_context* をラップした Long ポインタ。0 = 未ロード */
    @Volatile private var nativeCtx: Long = 0L

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedConfig: InferenceConfig? = null

    /** セッション変更検出用 */
    @Volatile private var lastSessionId: Long? = null

    /** 推論ループへのキャンセルシグナル */
    private val cancelFlag = AtomicBoolean(false)

    private val alarmDao by lazy { NezumiAiDatabase.getInstance(appContext).alarmDao() }
    private val memoryRepository by lazy {
        MemoryRepository(NezumiAiDatabase.getInstance(appContext).memoryDao())
    }
    private val toolExecutor by lazy {
        NezumiLiteRtToolExecutor(appContext, alarmDao, memoryRepository, MemoryTextEmbedder)
    }

    // ─── ロード / アンロード ──────────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return modelMutex.withLock {
            try {
                val normalized = config.normalized()
                val modelFile = resolveModelFile(modelName)
                    ?: return@withLock Result.failure(
                        IllegalStateException("GGUF model file not found: $modelName")
                    )

                val modelPath = modelFile.absolutePath
                if (nativeCtx != 0L &&
                    loadedModelPath == modelPath &&
                    loadedConfig == normalized
                ) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return@withLock Result.success(Unit)
                }

                // 既存コンテキストを解放
                freeNativeCtx()

                val optimalThreads = getOptimalThreadCount()
                val gpuLayers = getAdaptiveGpuLayers(normalized.backendType)
                
                if (!LlamaBridge.isLibraryLoaded()) {
                    return@withLock Result.failure(
                        IllegalStateException("GGUF native bridge not loaded: libllama_bridge.so unavailable")
                    )
                }

                Log.i(TAG, "Loading GGUF model: $modelPath backend=${normalized.backendType} threads=$optimalThreads gpuLayers=$gpuLayers")
                val ctx = withContext(Dispatchers.IO) {
                    LlamaBridge.llamaInit(
                        modelPath = modelPath,
                        nCtx = normalized.contextWindow,
                        nThreads = optimalThreads,
                        nGpuLayers = gpuLayers,
                        seed = -1  // ランダムシード
                    )
                }

                if (ctx == 0L) {
                    return@withLock Result.failure(
                        IllegalStateException("llamaInit failed — check model path and memory")
                    )
                }

                nativeCtx = ctx
                loadedModelPath = modelPath
                loadedConfig = normalized
                lastSessionId = null  // セッションリセット
                Log.i(TAG, "GGUF model loaded: $modelPath llama.cpp ${LlamaBridge.llamaVersion()}")
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        cancelFlag.set(true)
        return try {
            inferenceMutex.withLock {
                modelMutex.withLock {
                    try {
                        freeNativeCtx()
                        lastSessionId = null
                        Log.i(TAG, "GGUF model unloaded")
                        Result.success(Unit)
                    } catch (t: Throwable) {
                        Result.failure(if (t is Exception) t else RuntimeException(t))
                    } finally {
                        cancelFlag.set(false)
                    }
                }
            }
        } catch (t: Throwable) {
            cancelFlag.set(false)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    private fun freeNativeCtx() {
        val ctx = nativeCtx
        if (ctx != 0L) {
            LlamaBridge.llamaFree(ctx)
            nativeCtx = 0L
            loadedModelPath = null
            loadedConfig = null
        }
    }

    // ─── キャンセル ───────────────────────────────────────────────

    override suspend fun cancelInference() {
        Log.d(TAG, "cancelInference: setting cancelFlag")
        cancelFlag.set(true)
    }

    // ─── 推論 ─────────────────────────────────────────────────────

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    /**
     * GGUF推論。images / audioClips は現状無視（テキストのみ対応）。
     *
     * 出力フォーマット:
     *   - テキストデルタ: そのまま trySend
     *   - 完了: [InferenceStreamProtocol.encodeFinal]
     *
     * LiteRT 側と同じ protocol を使うため、ViewModel 側の変更不要。
     */
    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow<String> {
        // 推論 mutex を取得（同時推論1本のみ）
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)
        cancelFlag.set(false)

        val ctx = nativeCtx
        if (ctx == 0L) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val normalized = config.normalized()

        try {
            // セッション変更時は KV キャッシュをクリア
            if (lastSessionId != sessionId) {
                Log.d(TAG, "Session changed: $lastSessionId → $sessionId, clearing KV cache")
                withContext(Dispatchers.IO) {
                    LlamaBridge.llamaClearKvCache(ctx)
                }
                lastSessionId = sessionId
            }

            Log.d(TAG, "GGUF inference start: session=$sessionId promptLen=${prompt.length}")

            val fullAnswer = StringBuilder()
            var currentPrompt = prompt
            val toolCallingEnabled = normalized.enableToolCalling
            val maxToolRounds = if (toolCallingEnabled) 5 else 1
            var toolRound = 0
            var isFirstGenerationRound = true

            val toolResultCards = mutableListOf<ToolResultCard>()
            while (isActive && toolRound < maxToolRounds) {
                toolRound++
                val roundText = generateRound(
                    ctx = ctx,
                    sessionId = sessionId,
                    prompt = currentPrompt,
                    config = normalized,
                    isFirstRound = isFirstGenerationRound,
                    emitChunk = { chunk -> trySend(chunk) }
                )
                isFirstGenerationRound = false

                val visibleRoundText = if (toolCallingEnabled) {
                    val parsed = GgufToolCallParser.parse(roundText)
                    if (parsed.toolCalls.isNotEmpty()) {
                        buildString {
                            append(parsed.textBeforeTools)
                            if (parsed.textAfterTools.isNotBlank()) append(parsed.textAfterTools)
                        }
                    } else {
                        roundText
                    }
                } else {
                    roundText
                }
                fullAnswer.append(visibleRoundText)

                if (!toolCallingEnabled) break

                val parsed = GgufToolCallParser.parse(roundText)
                if (parsed.toolCalls.isEmpty() || cancelFlag.get()) {
                    break
                }
                if (toolRound >= maxToolRounds) {
                    Log.w(TAG, "Tool call loop exceeded max rounds, breaking session=$sessionId")
                    break
                }

                trySend(
                    InferenceStreamProtocol.encodeToolCallChunk(parsed.toolCalls.map { it.name })
                )
                val toolResults = mutableListOf<Pair<ToolCall, ToolExecutionResult>>()
                for (toolCall in parsed.toolCalls) {
                    val result = toolExecutor.execute(toolCall)
                    toolResults.add(toolCall to result)
                    val status = if (result.success) "success" else "error"
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(toolCall.name, status))
                    synchronized(toolResultCards) {
                        toolResultCards.add(
                            ToolResultCard(
                                toolName = toolCall.name.lowercase(),
                                success = result.success,
                                payload = anyToJsonElementMap(result.payload)
                            )
                        )
                    }
                }

                if (toolResultCards.isNotEmpty()) {
                    val toolResultsJson = ToolResultCard.listToJsonArray(toolResultCards)
                    trySend(InferenceStreamProtocol.encodeToolResults(toolResultsJson))
                    trySend(
                        InferenceStreamProtocol.encodeExecutedToolsList(
                            toolResultCards.map { it.toolName }.distinct()
                        )
                    )
                }

                currentPrompt = buildString {
                    append(prompt)
                    append(roundText)
                    append(GgufToolCallParser.formatToolResults(toolResults))
                }
                withContext(Dispatchers.IO) {
                    LlamaBridge.llamaClearKvCache(ctx)
                }
                lastSessionId = null
            }

            if (toolResultCards.isNotEmpty()) {
                val toolResultsJson = ToolResultCard.listToJsonArray(toolResultCards)
                trySend(InferenceStreamProtocol.encodeToolResults(toolResultsJson))
                trySend(
                    InferenceStreamProtocol.encodeExecutedToolsList(
                        toolResultCards.map { it.toolName }.distinct()
                    )
                )
            }

            val metrics = PerformanceMonitor.endInference(sessionId)
            if (metrics != null) {
                Log.i(TAG, "Performance: ${metrics.toLogString()}")
            }

            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                // キャンセル時も final を送出（LiteRT 側と同じ挙動）
                trySend(InferenceStreamProtocol.encodeFinal(""))
                close()
            } else {
                Log.e(TAG, "GGUF inference error: session=$sessionId", t)
                close(if (t is Exception) t else RuntimeException(t))
            }
        } finally {
            releaseInferenceMutex()
        }

        awaitClose {
            Log.d(TAG, "awaitClose: session=$sessionId")
            cancelFlag.set(true)
        }
    }.flowOn(Dispatchers.IO)

    // ─── ユーティリティ ──────────────────────────────────────────

    override suspend fun isAvailable(): Boolean = nativeCtx != 0L

    private suspend fun generateRound(
        ctx: Long,
        sessionId: Long,
        prompt: String,
        config: InferenceConfig,
        isFirstRound: Boolean,
        emitChunk: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val promptTokenCount = LlamaBridge.llamaTokenize(ctx, prompt, addBos = true)?.size ?: 0
        if (isFirstRound) {
            PerformanceMonitor.startInference(sessionId, config.backendType, promptTokenCount)
        }

        val tokens = LlamaBridge.llamaTokenize(ctx, prompt, addBos = true)
            ?: throw IllegalStateException("Tokenization failed")
        decodePromptTokens(ctx, tokens)

        val answerAccum = StringBuilder()
        val chunkBuffer = StringBuilder()
        var lastSendTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        val eosToken = LlamaBridge.llamaEosToken(ctx)
        var generatedCount = 0
        var tokensSinceLastSend = 0
        val stopSequences = effectiveStopSequences(config)
        val maxTokens = config.maxTokens.coerceAtMost(MAX_NEW_TOKENS)

        while (isActive && !cancelFlag.get() && generatedCount < maxTokens) {
            val token = LlamaBridge.llamaSample(
                ctx = ctx,
                temperature = config.temperature,
                topP = config.topP,
                topK = config.maxTopK,
                repeatPenalty = DEFAULT_REPEAT_PENALTY
            )
            if (token == eosToken) break

            val piece = LlamaBridge.llamaTokenToPiece(ctx, token)
            if (piece.isNotEmpty()) {
                if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                answerAccum.append(piece)
                chunkBuffer.append(piece)
                tokensSinceLastSend++
                PerformanceMonitor.recordToken(sessionId)
            }

            val accumulated = answerAccum.toString()
            if (GgufToolCallParser.hasToolCalls(accumulated)) {
                if (chunkBuffer.isNotEmpty()) {
                    val parsed = GgufToolCallParser.parse(accumulated)
                    val safePrefix = parsed.textBeforeTools
                    val alreadyEmittedLength = accumulated.length - chunkBuffer.length
                    val safeRemaining = (safePrefix.length - alreadyEmittedLength)
                        .coerceAtLeast(0)
                        .coerceAtMost(chunkBuffer.length)
                    if (safeRemaining > 0) {
                        emitChunk(chunkBuffer.substring(0, safeRemaining))
                    }
                }
                break
            }

            val hitStop = stopSequences.any { stop -> accumulated.endsWith(stop) }
            if (hitStop) {
                val matchedStop = stopSequences.first { stop -> accumulated.endsWith(stop) }
                val trimmed = chunkBuffer.toString().removeSuffix(matchedStop)
                if (trimmed.isNotEmpty()) emitChunk(trimmed)
                answerAccum.setLength(answerAccum.length - matchedStop.length)
                break
            }

            val now = System.currentTimeMillis()
            if (tokensSinceLastSend >= CHUNK_SIZE || (now - lastSendTime) >= 100) {
                if (chunkBuffer.isNotEmpty()) {
                    emitChunk(chunkBuffer.toString())
                    chunkBuffer.clear()
                    tokensSinceLastSend = 0
                    lastSendTime = now
                }
                if (generatedCount > 0 && generatedCount % 10 == 0 && firstTokenTime != null) {
                    val elapsed = now - firstTokenTime
                    if (elapsed > 0) {
                        val tps = (generatedCount * 1000f) / elapsed
                        emitChunk(InferenceStreamProtocol.encodeTps(tps))
                    }
                }
            }

            LlamaBridge.llamaDecode(ctx, intArrayOf(token))
            generatedCount++
        }

        if (chunkBuffer.isNotEmpty()) {
            emitChunk(chunkBuffer.toString())
        }
        if (generatedCount > 0 && firstTokenTime != null) {
            val finalElapsed = System.currentTimeMillis() - firstTokenTime
            if (finalElapsed > 0) {
                val finalTps = (generatedCount * 1000f) / finalElapsed
                emitChunk(InferenceStreamProtocol.encodeTps(finalTps))
            }
        }
        answerAccum.toString()
    }

    private fun anyToJsonElementMap(values: Map<String, Any?>): Map<String, JsonElement> {
        return values.entries.associate { (key, value) ->
            key to anyToJsonElement(value)
        }
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> {
                val obj = value.entries.mapNotNull { (k, v) ->
                    val key = k?.toString() ?: return@mapNotNull null
                    key to anyToJsonElement(v)
                }.toMap()
                JsonObject(obj)
            }
            is List<*> -> {
                val elements = value.map { anyToJsonElement(it) }
                kotlinx.serialization.json.JsonArray(elements)
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun decodePromptTokens(ctx: Long, tokens: IntArray) {
        if (tokens.isEmpty()) return
        val batchCapacity = LlamaBridge.llamaGetBatchCapacity(ctx).coerceAtLeast(1)
        var offset = 0
        while (offset < tokens.size) {
            val end = minOf(offset + batchCapacity, tokens.size)
            val chunk = tokens.copyOfRange(offset, end)
            val result = LlamaBridge.llamaDecode(ctx, chunk)
            if (result != 0) {
                throw IllegalStateException(
                    "llamaDecode failed: $result (chunk ${offset + 1}..$end / capacity=$batchCapacity)"
                )
            }
            offset = end
        }
    }

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex: double-release guard triggered")
        }
    }

    /**
     * modelName から File を解決する。
     * "*.gguf" で終わる絶対パスはそのまま使用する。
     * それ以外は今後 ModelFileManager に統合する想定。
     */
    private fun resolveModelFile(modelName: String): File? {
        // 絶対パスで .gguf ファイルが指定された場合
        if (modelName.endsWith(".gguf", ignoreCase = true) && modelName.startsWith("/")) {
            val f = File(modelName)
            return if (f.exists() && f.canRead()) f else null
        }
        // TODO: ModelFileManager に GGUF エントリを追加後、ここで解決する
        Log.w(TAG, "resolveModelFile: unsupported modelName format: $modelName")
        return null
    }
}
