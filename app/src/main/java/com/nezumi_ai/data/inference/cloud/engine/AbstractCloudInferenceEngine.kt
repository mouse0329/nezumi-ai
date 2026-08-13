package com.nezumi_ai.data.inference.cloud.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.inference.AIInferenceEngine
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.GgufToolCallParser
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.NezumiLiteRtToolExecutor
import com.nezumi_ai.data.inference.PromptBuilder
import com.nezumi_ai.data.inference.ToolExecutionResult
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.repository.MemoryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicReference

/**
 * クラウド API を叩く [AIInferenceEngine] 実装の共通基底。
 *
 * ## 責務
 * - 直列実行のロック管理 ([inferenceMutex])
 * - 進行中の [Call] を保持し、[cancelInference] / awaitClose でキャンセルする
 * - loadModel/unloadModel の軽量な既定実装 (現在のモデル名を覚えるだけ)
 * - [inferenceWithMedia] の骨格 (callbackFlow + FINAL 発行 + 例外整形)
 *
 * サブクラスは [runStreamingInference] で実際の HTTP + ストリーム解析を行う。
 * ストリーム中はデルタを [ProducerScope.trySend] でそのまま送るだけで、
 * FINAL チャンクの発行は本クラスが担当する。
 *
 * ## 「モデルロード」の意味
 * クラウド系ではオンデバイス推論のようなロード実体は存在しない。
 * ここでは「そのモデル名を今後の inference で使う」という宣言的な扱いに留め、
 * 実際の HTTP はリクエスト時にのみ発生する。
 */
abstract class AbstractCloudInferenceEngine(
    protected val appContext: Context,
    val provider: CloudApiKeyStore.Provider
) : AIInferenceEngine {

    protected val TAG: String = "Cloud/${provider.id}"

    protected val http: OkHttpClient = CloudHttpClient.instance

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    /** GGUF / LiteRT と同じツール実行器。クラウドでもプロンプトベース tool_call を実行する。 */
    private val toolExecutor by lazy {
        val db = NezumiAiDatabase.getInstance(appContext)
        NezumiLiteRtToolExecutor(
            appContext,
            db.alarmDao(),
            MemoryRepository(db.memoryDao()),
            MemoryTextEmbedder
        )
    }

    /** 現在バインドしているモデル名 (`gemini-2.5-flash` などの生の modelName)。 */
    @Volatile
    protected var currentModelName: String? = null

    /**
     * 現在バインドしているモデルに紐づく個別設定があるかの元となるモデル ID
     * (`cloud:{provider}:{model}` または レガシー `gemini_api` など)。
     * loadModel には生の modelName が渡ってくるため、ModelManager 側が
     * modelId も一緒に伝えてくる (下記 [loadModelWithId])。
     */
    @Volatile
    protected var currentModelId: String? = null

    /**
     * モデル個別設定を優先した API キー解決。個別設定が無ければプロバイダ共通値。
     */
    protected fun resolveApiKey(): String {
        val id = currentModelId
        return if (id != null) {
            CloudUserModelRegistry.resolveApiKey(appContext, id, provider)
        } else {
            CloudApiKeyStore.getApiKey(appContext, provider)
        }
    }

    /**
     * モデル個別設定を優先した Base URL 解決。個別設定が無ければプロバイダ共通値。
     */
    protected fun resolveBaseUrl(): String {
        val id = currentModelId
        return if (id != null) {
            CloudUserModelRegistry.resolveBaseUrl(appContext, id, provider)
        } else {
            CloudApiKeyStore.getBaseUrl(appContext, provider)
        }
    }

    /**
     * モデル ID つきの loadModel。ModelManager から呼ばれる。
     * modelId を保持しておくことで、後続の inference で個別設定を解決できる。
     */
    open suspend fun loadModelWithId(modelId: String, modelName: String, config: InferenceConfig): Result<Unit> {
        currentModelId = modelId
        return loadModel(modelName, config)
    }

    /** 現在進行中の [Call]。cancelInference / awaitClose から中断する。 */
    private val inflight = AtomicReference<Call?>(null)

    // ─── AIInferenceEngine ────────────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return loadMutex.withLock {
            val cleaned = modelName.trim()
            if (cleaned.isBlank()) {
                Result.failure(IllegalArgumentException("model name is blank"))
            } else if (!isConfiguredForCurrentModel()) {
                Result.failure(
                    IllegalStateException(
                        "Cloud provider '${provider.id}' is not configured. " +
                            "Please set the API key / base URL in the model settings."
                    )
                )
            } else {
                currentModelName = cleaned
                Log.d(TAG, "loadModel bound modelName=$cleaned (modelId=$currentModelId)")
                Result.success(Unit)
            }
        }
    }

    /**
     * 「現在バインドしようとしているモデル」が利用可能に構成済みかを返す。
     * モデル個別設定を持っていればそれを優先し、なければプロバイダ共通設定を見る。
     */
    private fun isConfiguredForCurrentModel(): Boolean {
        val id = currentModelId
        return if (id != null && CloudUserModelRegistry.hasOverride(appContext, id)) {
            CloudUserModelRegistry.isConfigured(appContext, id)
        } else {
            CloudApiKeyStore.isConfigured(appContext, provider)
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return loadMutex.withLock {
            currentModelName = null
            currentModelId = null
            cancelInflight()
            Result.success(Unit)
        }
    }

    override suspend fun cancelInference() {
        cancelInflight()
    }

    override suspend fun isAvailable(): Boolean {
        return currentModelName != null && isConfiguredForCurrentModel()
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
    ): Flow<String> = callbackFlow<String> {
        inferenceMutex.lock()
        val model = currentModelName
        if (model == null) {
            inferenceMutex.unlock()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }
        if (!isConfiguredForCurrentModel()) {
            inferenceMutex.unlock()
            close(
                IllegalStateException(
                    "Cloud provider '${provider.id}' is not configured. " +
                        "Please open the model settings and set the API key / base URL."
                )
            )
            return@callbackFlow
        }

        val normalized = config.normalized()
        val toolCallingEnabled = normalized.enableToolCalling
        val maxToolRounds = if (toolCallingEnabled) 5 else 1
        val fullAnswer = StringBuilder()
        val toolResultCards = mutableListOf<ToolResultCard>()
        var closed = false
        try {
            Log.d(
                TAG,
                "inference start session=$sessionId model=$model promptLen=${prompt.length} " +
                    "images=${images.size} toolCalling=$toolCallingEnabled"
            )
            var currentPrompt = prompt
            var toolRound = 0
            // プロンプト構築側（ChatViewModel/GgufToolPromptBuilder）は PromptBuilder.isGemma4Model
            // でモデル名を判定し、true なら Gemma4 専用の <|tool_call> 形式をシステムプロンプトに
            // 注入している。パース側がここで isGemma4=false 固定だと、Gemma4 系クラウドモデル
            // （例: cloud:ollama-remote:gemma4:31b）が Gemma4 形式でツール呼び出しを返しても
            // 汎用 <tool_call> 形式として解析され、常にマッチせず「ツール呼び出しなし」
            // 扱いになってしまう（= web_fetch 等のツールが一切発火しない不具合の原因）。
            // プロンプト構築側と同じ判定関数を使い、注入した形式とパース形式を一致させる。
            val isGemma4 = PromptBuilder.isGemma4Model(model)
            Log.d(TAG, "TOOL_FORMAT cloud model=$model isGemma4=$isGemma4")
            while (toolRound < maxToolRounds) {
                toolRound++
                val roundText = StringBuilder()
                // 2 ラウンド目以降はツール結果を含む続きなので画像は付けない
                val roundImages = if (toolRound == 1) images else emptyList()
                runStreamingInference(
                    session = this,
                    sessionId = sessionId,
                    model = model,
                    prompt = currentPrompt,
                    images = roundImages,
                    config = normalized,
                    onDelta = { delta ->
                        if (delta.isNotEmpty()) {
                            roundText.append(delta)
                            fullAnswer.append(delta)
                            trySend(delta)
                        }
                    }
                )

                if (!toolCallingEnabled) break

                val parsed = GgufToolCallParser.parse(roundText.toString(), isGemma4 = isGemma4)
                if (parsed.toolCalls.isEmpty()) {
                    // ツール呼び出しが検出できなかった原因を切り分けるため、モデルの生応答を
                    // 出力する（長文は先頭のみ）。ここが空/短文なら「モデルが何も生成していない」、
                    // それらしいテキストがあるのにツールとして拾えていないなら「フォーマット不一致」
                    // と判断できる。
                    val preview = roundText.toString().take(500)
                    Log.d(
                        TAG,
                        "No tool calls in round=$toolRound session=$sessionId " +
                            "rawLen=${roundText.length} rawPreview=\"$preview\""
                    )
                    break
                }
                if (toolRound >= maxToolRounds) {
                    Log.w(TAG, "Tool call loop hit max rounds session=$sessionId")
                    break
                }

                Log.d(
                    TAG,
                    "Tool calls detected round=$toolRound count=${parsed.toolCalls.size} " +
                        "names=${parsed.toolCalls.map { it.name }}"
                )
                trySend(
                    InferenceStreamProtocol.encodeToolCallChunk(parsed.toolCalls.map { it.name })
                )

                val toolResults = mutableListOf<Pair<ToolCall, ToolExecutionResult>>()
                for (toolCall in parsed.toolCalls) {
                    val result = toolExecutor.execute(toolCall)
                    toolResults.add(toolCall to result)
                    val status = if (result.success) "success" else "error"
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(toolCall.name, status))
                    toolResultCards.add(
                        ToolResultCard(
                            toolName = toolCall.name.lowercase(),
                            success = result.success,
                            payload = anyToJsonElementMap(result.payload)
                        )
                    )
                }

                val toolResponseBlock = GgufToolCallParser.formatToolResults(toolResults, isGemma4 = isGemma4)
                if (toolResponseBlock.isNotEmpty()) {
                    fullAnswer.append(toolResponseBlock)
                    // UI にツール結果テキストも流す
                    trySend(toolResponseBlock)
                }
                if (toolResultCards.isNotEmpty()) {
                    trySend(
                        InferenceStreamProtocol.encodeToolResults(
                            ToolResultCard.listToJsonArray(toolResultCards)
                        )
                    )
                    trySend(
                        InferenceStreamProtocol.encodeExecutedToolsList(
                            toolResultCards.map { it.toolName }.distinct()
                        )
                    )
                }

                // 次ラウンド: 元プロンプト + モデル出力 (thinking 除去) + ツール結果
                currentPrompt = buildString {
                    append(prompt)
                    append(
                        Gemma4ThinkingParser.stripThinkingForModelPrompt(roundText.toString())
                    )
                    append(toolResponseBlock)
                }
            }

            if (toolResultCards.isNotEmpty()) {
                trySend(
                    InferenceStreamProtocol.encodeToolResults(
                        ToolResultCard.listToJsonArray(toolResultCards)
                    )
                )
                trySend(
                    InferenceStreamProtocol.encodeExecutedToolsList(
                        toolResultCards.map { it.toolName }.distinct()
                    )
                )
            }

            trySend(
                InferenceStreamProtocol.encodeFinal(
                    Gemma4ThinkingParser.sanitizeVisibleText(
                        fullAnswer.toString(),
                        preserveToolCallTags = toolCallingEnabled
                    )
                )
            )
            close()
            closed = true
        } catch (c: CancellationException) {
            Log.d(TAG, "inference cancelled session=$sessionId")
            // ChatViewModel は FINAL 到達を「完了扱い」する。キャンセル時は
            // 途中までのテキストを FINAL として送出しておく。
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close()
            closed = true
            throw c
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed session=$sessionId", t)
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close(if (t is Exception) t else RuntimeException(t))
            closed = true
        } finally {
            if (!closed) {
                // 例外・close ルートを一つも通らなかった場合の保険
                runCatching { close() }
            }
            cancelInflight()
            if (inferenceMutex.isLocked) {
                runCatching { inferenceMutex.unlock() }
            }
        }

        awaitClose {
            Log.d(TAG, "awaitClose session=$sessionId")
            cancelInflight()
        }
    }.flowOn(Dispatchers.IO)

    // ─── サブクラスに実装させる箇所 ─────────────────────────────

    /**
     * 実際の HTTP リクエストとストリーム解析を行う。
     *
     * サブクラスは Provider ごとの
     *   - リクエスト JSON 組み立て
     *   - Authorization ヘッダ設定
     *   - SSE / NDJSON パース
     * を実装し、テキストデルタを [onDelta] へ渡す。
     *
     * 進行中の [Call] は [registerCall] で登録すること。
     * 登録した Call は本基底が [cancelInflight] でキャンセルする。
     *
     * 例外を投げた場合は inferenceWithMedia 側で捕捉され、
     * FINAL 発行後に上流へ再スローされる。
     */
    protected abstract suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        prompt: String,
        images: List<Bitmap>,
        config: InferenceConfig,
        onDelta: (String) -> Unit
    )

    /** サブクラスから、開始した Call を「進行中」として本基底に預ける。 */
    protected fun registerCall(call: Call) {
        val previous = inflight.getAndSet(call)
        previous?.cancel()
    }

    private fun cancelInflight() {
        inflight.getAndSet(null)?.let { call ->
            runCatching { call.cancel() }
                .onFailure { Log.w(TAG, "cancel call failed", it) }
        }
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
                JsonArray(elements)
            }
            else -> JsonPrimitive(value.toString())
        }
    }
}
