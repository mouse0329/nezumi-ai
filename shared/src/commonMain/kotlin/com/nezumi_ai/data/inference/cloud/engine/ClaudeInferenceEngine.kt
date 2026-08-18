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
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Anthropic Claude Messages API エンジン (commonMain / Ktor)。画像は JPEG バイト列。 */
class ClaudeInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?
) : AbstractCloudInferenceEngine(secureStore, configProvider, toolExecutor, CloudApiKeyStore.Provider.CLAUDE) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http get() = CloudHttpClient.instance

    override suspend fun runStreamingInference(
        session: ProducerScope<String>, sessionId: Long, model: String, prompt: String,
        images: List<ByteArray>, config: CloudInferenceParams, onDelta: (String) -> Unit
    ) {
        val apiKey = resolveApiKey(); val baseUrl = resolveBaseUrl()
        val endpoint = "$baseUrl/v1/messages"
        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            put("model", model); put("max_tokens", config.maxTokens)
            put("temperature", config.temperature.toDouble()); put("top_p", config.topP.toDouble()); put("stream", true)
            if (!systemPart.isNullOrBlank()) put("system", systemPart)
            if (config.customStopTokens.isNotEmpty()) putJsonArray("stop_sequences") { config.customStopTokens.forEach { add(it) } }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (userPart.isNotBlank()) addJsonObject { put("type", "text"); put("text", userPart) }
                        images.forEach { jpeg ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64"); put("media_type", ImageEncoding.DEFAULT_MIME); put("data", ImageEncoding.encodeJpegBase64(jpeg))
                                }
                            }
                        }
                    }
                }
            }
        }

        http.preparePost(endpoint) {
            header("x-api-key", apiKey); header("anthropic-version", ANTHROPIC_VERSION)
            header(HttpHeaders.Accept, "text/event-stream"); contentType(ContentType.Application.Json)
            setBody(bodyJson.toString())
        }.execute { response ->
            registerResponse(response)
            if (!response.status.isSuccess()) {
                val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                throw CloudRequestException("Claude request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
            }
            var eventName: String? = null
            val dataBuffer = StringBuilder()
            suspend fun dispatch(): Boolean {
                val ev = eventName; val data = dataBuffer.toString()
                eventName = null; dataBuffer.setLength(0)
                if (session.isClosedForSend) return false
                if (ev == "message_stop") return false
                if (ev != null && ev != "content_block_delta") return true
                val text = extractTextDelta(data)
                if (text != null) onDelta(text)
                return true
            }
            withStreamChannel(response) { ch ->
                while (true) {
                    val line = readStreamLineOrThrow(ch) ?: break
                    if (line.isEmpty()) { if (dataBuffer.isNotEmpty() || eventName != null) { if (!dispatch()) return@withStreamChannel }; continue }
                    if (line.startsWith(":")) continue
                    val c = line.indexOf(':')
                    val field: String; val value: String
                    if (c < 0) { field = line; value = "" } else {
                        field = line.substring(0, c); var raw = line.substring(c + 1); if (raw.startsWith(" ")) raw = raw.substring(1); value = raw
                    }
                    when (field) {
                        "data" -> { if (dataBuffer.isNotEmpty()) dataBuffer.append('\n'); dataBuffer.append(value) }
                        "event" -> eventName = value
                    }
                }
                if (dataBuffer.isNotEmpty() || eventName != null) dispatch()
            }
        }
        CloudLog.d(TAG, "Claude stream finished session=$sessionId")
    }

    private fun extractTextDelta(payload: String): String? {
        val root = runCatching { json.parseToJsonElement(payload.trim()) }.getOrNull() as? JsonObject ?: return null
        if (runCatching { root["type"]?.jsonPrimitive?.content }.getOrNull() != "content_block_delta") return null
        val delta = root["delta"] as? JsonObject ?: return null
        if (runCatching { delta["type"]?.jsonPrimitive?.content }.getOrNull() != "text_delta") return null
        return runCatching { delta["text"]?.jsonPrimitive?.content }.getOrNull()
    }

    companion object { private const val ANTHROPIC_VERSION = "2023-06-01" }
}
