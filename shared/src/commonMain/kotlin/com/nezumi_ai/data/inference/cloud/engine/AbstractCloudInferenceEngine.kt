package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.CloudModelConfigProvider
import com.nezumi_ai.data.inference.CloudToolCallParser
import com.nezumi_ai.data.inference.CloudToolExecutionResult
import com.nezumi_ai.data.inference.CloudToolExecutor
import com.nezumi_ai.data.inference.CloudToolResultCard
import com.nezumi_ai.data.inference.Gemma4ModelDetector
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ParsedToolCall
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudLog
import com.nezumi_ai.data.inference.cloud.PlatformSecureStore
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile

/** クラウド API リクエストの失敗を表す例外 (java.io.IOException の commonMain 代替)。 */
class CloudRequestException(message: String) : Exception(message)

/**
 * クラウド API を叩く推論エンジンの共通基底 (commonMain 版)。
 *
 * 責務は旧 app 側 AbstractCloudInferenceEngine と同一:
 * 直列実行ロック / 進行中ストリームのキャンセル / loadModel の既定実装 /
 * 推論の骨格 (callbackFlow + FINAL 発行 + 例外整形 + ツールコールループ)。
 *
 * プラットフォーム依存は注入で隔離する:
 * - [secureStore]    : プロバイダ共通の API キー / Base URL
 * - [configProvider] : モデル個別設定のオーバーライド解決
 * - [toolExecutor]   : ツール実行 (DB/ONNX 依存)。null ならツールコール無効
 *
 * 画像は JPEG バイト列 (List<ByteArray>) で受け取る (Bitmap 変換は Android 側の責務)。
 */
abstract class AbstractCloudInferenceEngine(
    protected val secureStore: PlatformSecureStore,
    protected val configProvider: CloudModelConfigProvider,
    protected val toolExecutor: CloudToolExecutor?,
    val provider: CloudApiKeyStore.Provider
) {

    protected val TAG: String = "Cloud/${provider.id}"

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    /** 現在バインドしているモデル名 (生の modelName)。 */
    @Volatile
    protected var currentModelName: String? = null

    /** 個別設定解決の元となるモデル ID (`cloud:{provider}:{model}` or レガシー)。 */
    @Volatile
    protected var currentModelId: String? = null

    /** 現在進行中の HTTP レスポンス。cancelInference / awaitClose から中断する。 */
    private val inflightLock = Any()
    private var inflightResponse: HttpResponse? = null

    // ─── モデルロード ─────────────────────────────────────────────

    open suspend fun loadModelWithId(modelId: String, modelName: String, config: CloudInferenceParams): Result<Unit> {
        currentModelId = modelId
        return loadModel(modelName, config)
    }

    open suspend fun loadModel(modelName: String, config: CloudInferenceParams): Result<Unit> {
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
                CloudLog.d(TAG, "loadModel bound modelName=$cleaned (modelId=$currentModelId)")
                Result.success(Unit)
            }
        }
    }

    private fun isConfiguredForCurrentModel(): Boolean {
        val id = currentModelId
        return if (id != null && configProvider.hasOverride(id)) {
            configProvider.isConfigured(id)
        } else {
            CloudApiKeyStore.isConfigured(secureStore, provider)
        }
    }

    open suspend fun unloadModel(): Result<Unit> {
        return loadMutex.withLock {
            currentModelName = null
            currentModelId = null
            cancelInflight()
            Result.success(Unit)
        }
    }

    open suspend fun cancelInference() {
        cancelInflight()
    }

    open suspend fun isAvailable(): Boolean {
        return currentModelName != null && isConfiguredForCurrentModel()
    }

    // ─── 設定解決 ─────────────────────────────────────────────────

    protected fun resolveApiKey(): String {
        val id = currentModelId
        return if (id != null) configProvider.resolveApiKey(id, provider.id)
        else CloudApiKeyStore.getApiKey(secureStore, provider)
    }

    protected fun resolveBaseUrl(): String {
        val id = currentModelId
        return if (id != null) configProvider.resolveBaseUrl(id, provider.id)
        else CloudApiKeyStore.getBaseUrl(secureStore, provider)
    }

    // ─── 推論骨格 ─────────────────────────────────────────────────

    fun inference(sessionId: Long, prompt: String, config: CloudInferenceParams): Flow<String> =
        inferenceWithMedia(sessionId, prompt, emptyList(), config)

    fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<ByteArray>,
        config: CloudInferenceParams
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

        val toolCallingEnabled = config.enableToolCalling && toolExecutor != null
        val maxToolRounds = if (toolCallingEnabled) 5 else 1
        val fullAnswer = StringBuilder()
        val toolResultCards = mutableListOf<CloudToolResultCard>()
        var closed = false
        try {
            CloudLog.d(
                TAG,
                "inference start session=$sessionId model=$model promptLen=${prompt.length} " +
                    "images=${images.size} toolCalling=$toolCallingEnabled"
            )
            var currentPrompt = prompt
            var toolRound = 0
            val isGemma4 = Gemma4ModelDetector.isGemma4Model(model)
            CloudLog.d(TAG, "TOOL_FORMAT cloud model=$model isGemma4=$isGemma4")
            while (toolRound < maxToolRounds) {
                toolRound++
                val roundText = StringBuilder()
                val roundImages = if (toolRound == 1) images else emptyList()
                runStreamingInference(
                    session = this,
                    sessionId = sessionId,
                    model = model,
                    prompt = currentPrompt,
                    images = roundImages,
                    config = config,
                    onDelta = { delta ->
                        if (delta.isNotEmpty()) {
                            roundText.append(delta)
                            fullAnswer.append(delta)
                            trySend(delta)
                        }
                    }
                )

                if (!toolCallingEnabled) break

                val parsed = CloudToolCallParser.parse(roundText.toString(), isGemma4 = isGemma4)
                if (parsed.toolCalls.isEmpty()) {
                    val preview = roundText.toString().take(500)
                    CloudLog.d(
                        TAG,
                        "No tool calls in round=$toolRound session=$sessionId " +
                            "rawLen=${roundText.length} rawPreview=\"$preview\""
                    )
                    break
                }
                if (toolRound >= maxToolRounds) {
                    CloudLog.w(TAG, "Tool call loop hit max rounds session=$sessionId")
                    break
                }

                CloudLog.d(
                    TAG,
                    "Tool calls detected round=$toolRound count=${parsed.toolCalls.size} " +
                        "names=${parsed.toolCalls.map { it.name }}"
                )
                trySend(InferenceStreamProtocol.encodeToolCallChunk(parsed.toolCalls.map { it.name }))

                val toolResults = mutableListOf<Pair<ParsedToolCall, CloudToolExecutionResult>>()
                for (toolCall in parsed.toolCalls) {
                    val result = toolExecutor!!.execute(toolCall)
                    toolResults.add(toolCall to result)
                    val status = if (result.success) "success" else "error"
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(toolCall.name, status))
                    toolResultCards.add(
                        CloudToolResultCard(
                            toolName = toolCall.name.lowercase(),
                            success = result.success,
                            payload = anyToJsonElementMap(result.payload)
                        )
                    )
                }

                val toolResponseBlock = CloudToolCallParser.formatToolResults(toolResults)
                if (toolResponseBlock.isNotEmpty()) {
                    fullAnswer.append(toolResponseBlock)
                    trySend(toolResponseBlock)
                }
                if (toolResultCards.isNotEmpty()) {
                    trySend(InferenceStreamProtocol.encodeToolResults(CloudToolResultCard.listToJsonArray(toolResultCards)))
                    trySend(InferenceStreamProtocol.encodeExecutedToolsList(toolResultCards.map { it.toolName }.distinct()))
                }

                currentPrompt = buildString {
                    append(prompt)
                    append(Gemma4ThinkingParser.stripThinkingForModelPrompt(roundText.toString()))
                    append(toolResponseBlock)
                }
            }

            if (toolResultCards.isNotEmpty()) {
                trySend(InferenceStreamProtocol.encodeToolResults(CloudToolResultCard.listToJsonArray(toolResultCards)))
                trySend(InferenceStreamProtocol.encodeExecutedToolsList(toolResultCards.map { it.toolName }.distinct()))
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
            CloudLog.d(TAG, "inference cancelled session=$sessionId")
            // ChatViewModel は FINAL 到達を「完了扱い」するため、途中テキストを FINAL として送出する。
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close()
            closed = true
            throw c
        } catch (t: Throwable) {
            CloudLog.e(TAG, "inference failed session=$sessionId", t)
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close(if (t is Exception) t else RuntimeException(t))
            closed = true
        } finally {
            if (!closed) runCatching { close() }
            cancelInflight()
            if (inferenceMutex.isLocked) runCatching { inferenceMutex.unlock() }
        }

        awaitClose {
            CloudLog.d(TAG, "awaitClose session=$sessionId")
            cancelInflight()
        }
    }.flowOn(Dispatchers.IO)

    // ─── サブクラス実装 ───────────────────────────────────────────

    /** 実際の HTTP リクエストとストリーム解析を行う。レスポンスは [registerResponse] に預ける。 */
    protected abstract suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        prompt: String,
        images: List<ByteArray>,
        config: CloudInferenceParams,
        onDelta: (String) -> Unit
    )

    /** サブクラスから、開始したレスポンスを「進行中」として預ける。 */
    protected fun registerResponse(response: HttpResponse) {
        synchronized(inflightLock) { inflightResponse = response }
    }

    /** レスポンスボディを行単位で読む。終端・切断時は null。 */
    protected suspend fun readStreamLine(response: HttpResponse): String? {
        return try {
            val ch = response.bodyAsChannel()
            if (ch.isClosedForRead) null else ch.readUTF8Line()
        } catch (t: Throwable) {
            null
        }
    }

    private fun cancelInflight() {
        val resp = synchronized(inflightLock) {
            val r = inflightResponse
            inflightResponse = null
            r
        } ?: return

        // awaitClose / callbackFlow の非 suspend ラムダからでも呼べるように、
        // bodyAsChannel().cancel() だけを独立した IO コルーチンで実行する。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { resp.bodyAsChannel().cancel(CancellationException("cloud inference cancelled")) }
                .onFailure { CloudLog.w(TAG, "cancel stream failed", it) }
        }
    }

    private fun anyToJsonElementMap(values: Map<String, Any?>): Map<String, JsonElement> {
        return values.entries.associate { (key, value) -> key to anyToJsonElement(value) }
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
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }
}
