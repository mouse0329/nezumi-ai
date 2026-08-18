package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import com.nezumi_ai.data.inference.cloud.CloudLog
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Ollama ネイティブ API エンジン (commonMain / Ktor)。NDJSON を行単位で読む。 */
class OllamaInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?,
    provider: CloudApiKeyStore.Provider
) : AbstractCloudInferenceEngine(secureStore, configProvider, toolExecutor, provider) {

    init {
        require(provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL || provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE) {
            "OllamaInferenceEngine requires OLLAMA_LOCAL or OLLAMA_REMOTE provider"
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http get() = CloudHttpClient.instance

    override suspend fun runStreamingInference(
        session: ProducerScope<String>, sessionId: Long, model: String, prompt: String,
        images: List<ByteArray>, config: CloudInferenceParams, onDelta: (String) -> Unit
    ) {
        val baseUrl = resolveBaseUrl(); val apiKey = resolveApiKey()
        val endpoint = "$baseUrl/api/chat"
        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)
        val messages = buildOllamaMessages(systemPart, userPart, images)

        CloudLog.d(TAG, "Ollama request messages=${messages.size} hasToolTurn=${messages.any { it[\"role\"]?.jsonPrimitive?.content == \"tool\" }}")
        val bodyJson = buildJsonObject {
            put("model", model); put("stream", true)
            putJsonArray("messages") { messages.forEach { add(it) } }
            putJsonObject("options") {
                put("temperature", config.temperature.toDouble()); put("top_p", config.topP.toDouble())
                put("num_predict", config.maxTokens); put("num_ctx", config.contextWindow)
                if (config.customStopTokens.isNotEmpty()) putJsonArray("stop") { config.customStopTokens.forEach { add(it) } }
            }
        }

        http.preparePost(endpoint) {
            header(HttpHeaders.Accept, "application/x-ndjson"); contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(bodyJson.toString())
        }.execute { response ->
            registerResponse(response)
            if (!response.status.isSuccess()) {
                val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                throw CloudRequestException("Ollama request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
            }
            withStreamChannel(response) { ch ->
                while (!session.isClosedForSend) {
                    val line = readStreamLineOrThrow(ch) ?: break
                    if (line.isEmpty()) continue
                    val (delta, done) = parseChunk(line)
                    if (!delta.isNullOrEmpty()) onDelta(delta)
                    if (done) break
                }
            }
        }
        CloudLog.d(TAG, "Ollama stream finished session=$sessionId")
    }

    /**
     * Tool round の2回目以降では、tool call と tool result を Ollama の chat message role
     * として渡す。以前は currentPrompt 全体を1つの user message に押し込んでいたため、
     * <tool_response> が単なるユーザー文字列になり、モデルの tool-turn として解釈されない
     * ケースがあった。
     */
    private fun buildOllamaMessages(
        systemPart: String?,
        userPart: String,
        images: List<ByteArray>
    ): List<JsonObject> {
        val gemmaOpen = "<|tool_call>"
        val genericOpen = "<tool_call>"
        val responseOpen = "<tool_response>"
        val toolCallStart = maxOf(userPart.lastIndexOf(gemmaOpen), userPart.lastIndexOf(genericOpen))
        val responseStart = userPart.lastIndexOf(responseOpen)

        if (toolCallStart < 0 || responseStart <= toolCallStart) {
            return buildList {
                if (!systemPart.isNullOrBlank()) add(buildJsonObject { put("role", "system"); put("content", systemPart) })
                add(buildJsonObject {
                    put("role", "user"); put("content", userPart)
                    if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(ImageEncoding.encodeJpegBase64(it)) } }
                })
            }
        }

        val toolCallEnd = when {
            userPart.startsWith(gemmaOpen, toolCallStart) -> {
                val end = userPart.indexOf("<tool_call|>", toolCallStart + gemmaOpen.length)
                if (end >= 0) end + "<tool_call|>".length else responseStart
            }
            else -> {
                val end = userPart.indexOf("</tool_call>", toolCallStart + genericOpen.length)
                if (end >= 0) end + "</tool_call>".length else responseStart
            }
        }
        if (toolCallEnd > responseStart) return buildBasicMessages(systemPart, userPart, images)

        val beforeCall = userPart.substring(0, toolCallStart).trimEnd()
        val assistantCall = userPart.substring(toolCallStart, toolCallEnd).trim()
        val toolResult = userPart.substring(responseStart).trim()

        return buildList {
            if (!systemPart.isNullOrBlank()) add(buildJsonObject { put("role", "system"); put("content", systemPart) })
            add(buildJsonObject {
                put("role", "user")
                put("content", beforeCall)
                if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(ImageEncoding.encodeJpegBase64(it)) } }
            })
            add(buildJsonObject { put("role", "assistant"); put("content", assistantCall) })
            add(buildJsonObject { put("role", "tool"); put("content", toolResult) })
        }
    }

    private fun buildBasicMessages(
        systemPart: String?, userPart: String, images: List<ByteArray>
    ): List<JsonObject> = buildList {
        if (!systemPart.isNullOrBlank()) add(buildJsonObject { put("role", "system"); put("content", systemPart) })
        add(buildJsonObject {
            put("role", "user"); put("content", userPart)
            if (images.isNotEmpty()) putJsonArray("images") { images.forEach { add(ImageEncoding.encodeJpegBase64(it)) } }
        })
    }

    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: return null to false
        val done = runCatching { root["done"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
        val message = root["message"] as? JsonObject
        val contentDelta = message?.let { runCatching { it["content"]?.jsonPrimitive?.content }.getOrNull() }
        val toolCallsDelta = message?.get("tool_calls")?.let { synthesizeGemma4ToolCallText(it) }
        if (!toolCallsDelta.isNullOrEmpty()) {
            CloudLog.d(TAG, "Ollama tool_calls detected chunk; synthesizedLen=${toolCallsDelta.length}")
        }
        val delta = when {
            !toolCallsDelta.isNullOrEmpty() -> contentDelta.orEmpty() + toolCallsDelta
            else -> contentDelta
        }
        return delta to done
    }

    private fun synthesizeGemma4ToolCallText(toolCallsElement: JsonElement): String? {
        val array = toolCallsElement as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val builder = StringBuilder()
        for (entry in array) {
            val obj = entry as? JsonObject ?: continue
            val function = obj["function"] as? JsonObject ?: continue
            val name = runCatching { function["name"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val argumentsJson = when (val a = function["arguments"]) {
                is JsonObject -> a.toString()
                is JsonPrimitive -> a.content
                null -> "{}"
                else -> a.toString()
            }
            builder.append("<|tool_call>call:").append(name).append(argumentsJson).append("<tool_call|>")
        }
        return if (builder.isEmpty()) null else builder.toString()
    }
}
