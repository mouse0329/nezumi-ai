package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudLog
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Ollama (`/api/chat`) ネイティブ API 向けストリーミングエンジン (commonMain / Ktor 版)。
 * Local / Cloud でワイヤフォーマットは同一。NDJSON を行単位で読む。
 */
class OllamaInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?,
    provider: CloudApiKeyStore.Provider
) : AbstractCloudInferenceEngine(secureStore, configProvider, toolExecutor, provider) {

    init {
        require(
            provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
                provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE
        ) { "OllamaInferenceEngine requires OLLAMA_LOCAL or OLLAMA_REMOTE provider" }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http get() = com.nezumi_ai.data.inference.cloud.CloudHttpClient.instance

    override suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        prompt: String,
        images: List<ByteArray>,
        config: CloudInferenceParams,
        onDelta: (String) -> Unit
    ) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val endpoint = "$baseUrl/api/chat"

        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                if (!systemPart.isNullOrBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPart)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPart)
                    if (images.isNotEmpty()) {
                        putJsonArray("images") {
                            images.forEach { jpegBytes -> add(ImageEncoding.encodeJpegBase64(jpegBytes)) }
                        }
                    }
                }
            }
            putJsonObject("options") {
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
                put("num_predict", config.maxTokens)
                put("num_ctx", config.contextWindow)
                if (config.customStopTokens.isNotEmpty()) {
                    putJsonArray("stop") { config.customStopTokens.forEach { add(it) } }
                }
            }
        }

        val response = http.post(endpoint) {
            header(HttpHeaders.Accept, "application/x-ndjson")
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(bodyJson.toString())
        }
        registerResponse(response)

        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            throw CloudRequestException("Ollama request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
        }

        while (!session.isClosedForSend) {
            val line = readStreamLine(response) ?: break
            if (line.isEmpty()) continue
            CloudLog.d(TAG, "raw NDJSON line: $line")
            val (delta, done) = parseChunk(line)
            if (!delta.isNullOrEmpty()) onDelta(delta)
            if (done) break
        }

        CloudLog.d(TAG, "Ollama stream finished session=$sessionId")
    }

    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return null to false
        val done = runCatching {
            root["done"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true)
        }.getOrNull() ?: false
        val message = root["message"] as? JsonObject
        val contentDelta = message?.let {
            runCatching { it["content"]?.jsonPrimitive?.content }.getOrNull()
        }
        val toolCallsDelta = message?.get("tool_calls")?.let { synthesizeGemma4ToolCallText(it) }
        val delta = when {
            !toolCallsDelta.isNullOrEmpty() -> (contentDelta.orEmpty()) + toolCallsDelta
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
            val argumentsElement = function["arguments"]
            val argumentsJson = when (argumentsElement) {
                is JsonObject -> argumentsElement.toString()
                is JsonPrimitive -> argumentsElement.content
                null -> "{}"
                else -> argumentsElement.toString()
            }
            builder.append("<|tool_call>call:").append(name).append(argumentsJson).append("<tool_call|>")
        }
        return if (builder.isEmpty()) null else builder.toString()
    }
}
