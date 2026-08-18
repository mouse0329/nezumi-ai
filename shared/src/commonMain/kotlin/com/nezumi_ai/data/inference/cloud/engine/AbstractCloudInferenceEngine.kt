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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import kotlin.concurrent.Volatile

/** クラウド API リクエスト失敗を表す例外 (java.io.IOException の commonMain 代替)。 */
class CloudRequestException(message: String) : Exception(message)

/** クラウド API を叩く推論エンジンの共通基底 (commonMain 版)。画像は JPEG バイト列。 */
abstract class AbstractCloudInferenceEngine(
    protected val secureStore: PlatformSecureStore,
    protected val configProvider: CloudModelConfigProvider,
    protected val toolExecutor: CloudToolExecutor?,
    val provider: CloudApiKeyStore.Provider
) {

    protected val TAG: String = "Cloud/${provider.id}"

    private val loadMutex = Mutex()
    private val inferenceMutex = Mutex()

    @Volatile protected var currentModelName: String? = null
    @Volatile protected var currentModelId: String? = null

    private val inflightLock = Any()
    private var inflightResponse: HttpResponse? = null

    // 行リーダが使い回すボディチャネル。bodyAsChannel() は呼ぶたびに
    // 先頭からの新しいチャネルを返しうるため、1レスポンスにつき1つだけ掴んで進める。
    private val inflightChannelMutex = Mutex()
    private var inflightChannel: io.ktor.utils.io.ByteReadChannel? = null

    open suspend fun loadModelWithId(modelId: String, modelName: String, config: CloudInferenceParams): Result<Unit> {
        currentModelId = modelId
        return loadModel(modelName, config)
    }

    open suspend fun loadModel(modelName: String, config: CloudInferenceParams): Result<Unit> {
        return loadMutex.withLock {
            val cleaned = modelName.trim()
            if (cleaned.isBlank()) Result.failure(IllegalArgumentException("model name is blank"))
            else if (!isConfiguredForCurrentModel()) Result.failure(IllegalStateException(
                "Cloud provider '${provider.id}' is not configured. Please set the API key / base URL in the model settings."))
            else {
                currentModelName = cleaned
                CloudLog.d(TAG, "loadModel bound modelName=$cleaned (modelId=$currentModelId)")
                Result.success(Unit)
            }
        }
    }

    private fun isConfiguredForCurrentModel(): Boolean {
        val id = currentModelId
        return if (id != null && configProvider.hasOverride(id)) configProvider.isConfigured(id)
        else CloudApiKeyStore.isConfigured(secureStore, provider)
    }

    open suspend fun unloadModel(): Result<Unit> = loadMutex.withLock {
        currentModelName = null; currentModelId = null; cancelInflight(); Result.success(Unit)
    }

    open suspend fun cancelInference() { cancelInflight() }

    open suspend fun isAvailable(): Boolean = currentModelName != null && isConfiguredForCurrentModel()

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

    fun inference(sessionId: Long, prompt: String, config: CloudInferenceParams): Flow<String> =
        inferenceWithMedia(sessionId, prompt, emptyList(), config)

    fun inferenceWithMedia(
        sessionId: Long, prompt: String, images: List<ByteArray>, config: CloudInferenceParams
    ): Flow<String> = callbackFlow<String> {
        inferenceMutex.lock()
        val model = currentModelName
        if (model == null) {
            inferenceMutex.unlock()
            close(IllegalStateException("Model not loaded. Call loadModel() first.")); return@callbackFlow
        }
        if (!isConfiguredForCurrentModel()) {
            inferenceMutex.unlock()
            close(IllegalStateException("Cloud provider '${provider.id}' is not configured. Please open the model settings and set the API key / base URL.")); return@callbackFlow
        }

        val toolCallingEnabled = config.enableToolCalling && toolExecutor != null
        val maxToolRounds = if (toolCallingEnabled) 5 else 1
        val fullAnswer = StringBuilder()
        val toolResultCards = mutableListOf<CloudToolResultCard>()
        var closed = false
        try {
            CloudLog.d(TAG, "inference start session=$sessionId model=$model promptLen=${prompt.length} images=${images.size} toolCalling=$toolCallingEnabled")
            var currentPrompt = prompt
            var toolRound = 0
            val isGemma4 = Gemma4ModelDetector.isGemma4Model(model)
            CloudLog.d(TAG, "TOOL_FORMAT cloud model=$model isGemma4=$isGemma4")
            while (toolRound < maxToolRounds) {
                toolRound++
                val roundText = StringBuilder()
                val roundImages = if (toolRound == 1) images else emptyList()
                runStreamingInference(this, sessionId, model, currentPrompt, roundImages, config) { delta ->
                    if (delta.isNotEmpty()) { roundText.append(delta); fullAnswer.append(delta); trySend(delta) }
                }
                if (!toolCallingEnabled) break
                val parsed = CloudToolCallParser.parse(roundText.toString(), isGemma4 = isGemma4)
                if (parsed.toolCalls.isEmpty()) {
                    CloudLog.d(TAG, "No tool calls in round=$toolRound session=$sessionId rawLen=${roundText.length} rawPreview=\"${roundText.toString().take(500)}\"")
                    break
                }
                if (toolRound >= maxToolRounds) { CloudLog.w(TAG, "Tool call loop hit max rounds session=$sessionId"); break }
                CloudLog.d(TAG, "Tool calls detected round=$toolRound count=${parsed.toolCalls.size} names=${parsed.toolCalls.map { it.name }}")
                trySend(InferenceStreamProtocol.encodeToolCallChunk(parsed.toolCalls.map { it.name }))
                val toolResults = mutableListOf<Pair<ParsedToolCall, CloudToolExecutionResult>>()
                for (toolCall in parsed.toolCalls) {
                    val result = toolExecutor!!.execute(toolCall)
                    toolResults.add(toolCall to result)
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(toolCall.name, if (result.success) "success" else "error"))
                    toolResultCards.add(CloudToolResultCard(toolCall.name.lowercase(), result.success, anyToJsonElementMap(result.payload)))
                }
                val toolResponseBlock = CloudToolCallParser.formatToolResults(toolResults)
                if (toolResponseBlock.isNotEmpty()) { fullAnswer.append(toolResponseBlock); trySend(toolResponseBlock) }
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
            trySend(InferenceStreamProtocol.encodeFinal(Gemma4ThinkingParser.sanitizeVisibleText(fullAnswer.toString(), preserveToolCallTags = toolCallingEnabled)))
            close(); closed = true
        } catch (c: CancellationException) {
            CloudLog.d(TAG, "inference cancelled session=$sessionId")
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close(); closed = true; throw c
        } catch (t: Throwable) {
            CloudLog.e(TAG, "inference failed session=$sessionId", t)
            trySend(InferenceStreamProtocol.encodeFinal(fullAnswer.toString()))
            close(if (t is Exception) t else RuntimeException(t)); closed = true
        } finally {
            if (!closed) runCatching { close() }
            cancelInflight()
            if (inferenceMutex.isLocked) runCatching { inferenceMutex.unlock() }
        }
        awaitClose { CloudLog.d(TAG, "awaitClose session=$sessionId") }
    }.flowOn(Dispatchers.IO)

    protected abstract suspend fun runStreamingInference(
        session: ProducerScope<String>, sessionId: Long, model: String, prompt: String,
        images: List<ByteArray>, config: CloudInferenceParams, onDelta: (String) -> Unit
    )

    protected fun registerResponse(response: HttpResponse) {
        synchronized(inflightLock) { inflightResponse = response }
    }

    /** ストリーミングボディのチャネルを1つだけ掴んで [block] に渡す (行の読み進め用)。 */
    protected suspend fun <T> withStreamChannel(response: HttpResponse, block: suspend (io.ktor.utils.io.ByteReadChannel) -> T): T {
        val ch = inflightChannelMutex.withLock {
            inflightChannel ?: response.bodyAsChannel().also { inflightChannel = it }
        }
        return block(ch)
    }

    private suspend fun cancelInflight() {
        val resp = synchronized(inflightLock) { val r = inflightResponse; inflightResponse = null; r }
        val ch = inflightChannelMutex.withLock {
            val c = inflightChannel; inflightChannel = null; c
        }
        runCatching { ch?.cancel(CancellationException("cloud inference cancelled")) }
            .onFailure { CloudLog.w(TAG, "cancel stream failed", it) }
        if (resp == null && ch == null) return
    }

    private fun anyToJsonElementMap(values: Map<String, Any?>): Map<String, JsonElement> =
        values.entries.associate { (k, v) -> k to anyToJsonElement(v) }

    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.mapNotNull { (k, v) -> (k?.toString() ?: return@mapNotNull null) to anyToJsonElement(v) }.toMap())
        is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }
}
