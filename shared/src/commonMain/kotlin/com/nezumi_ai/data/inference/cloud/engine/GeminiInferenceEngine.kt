package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudLog
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Google Gemini API 向けエンジン (commonMain / Ktor 版)。
 * 画像は JPEG バイト列で受け取る。
 */
class GeminiInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?
) : AbstractCloudInferenceEngine(
    secureStore, configProvider, toolExecutor,
    com.nezumi_ai.data.inference.cloud.CloudApiKeyStore.Provider.GEMINI
) {

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
        val apiKey = resolveApiKey()
        val baseUrl = resolveBaseUrl()
        val endpoint = "$baseUrl/v1beta/models/$model:streamGenerateContent"

        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            if (!systemPart.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPart) } }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (userPart.isNotBlank()) addJsonObject { put("text", userPart) }
                        images.forEach { jpegBytes ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", ImageEncoding.DEFAULT_MIME)
                                    put("data", ImageEncoding.encodeJpegBase64(jpegBytes))
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", config.temperature.toDouble())
                put("topP", config.topP.toDouble())
                put("maxOutputTokens", config.maxTokens)
                if (config.customStopTokens.isNotEmpty()) {
                    putJsonArray("stopSequences") { config.customStopTokens.forEach { add(it) } }
                }
            }
        }

        val response = http.post(endpoint) {
            parameter("alt", "sse")
            parameter("key", apiKey)
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(bodyJson.toString())
        }
        registerResponse(response)

        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            throw CloudRequestException("Gemini request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
        }

        val dataBuffer = StringBuilder()
        suspend fun dispatch(data: String): Boolean {
            if (session.isClosedForSend) return false
            val trimmed = data.trim()
            if (trimmed.isEmpty()) return true
            val text = extractTextParts(trimmed)
            if (text != null) onDelta(text)
            return true
        }

        while (true) {
            val line = readStreamLine(response) ?: break
            if (line.isEmpty()) {
                if (dataBuffer.isNotEmpty()) {
                    val data = dataBuffer.toString()
                    dataBuffer.setLength(0)
                    if (!dispatch(data)) return
                }
                continue
            }
            if (line.startsWith(":")) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx < 0) continue
            val field = line.substring(0, colonIdx)
            var raw = line.substring(colonIdx + 1)
            if (raw.startsWith(" ")) raw = raw.substring(1)
            if (field == "data") {
                if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                dataBuffer.append(raw)
            }
        }
        if (dataBuffer.isNotEmpty()) dispatch(dataBuffer.toString())

        CloudLog.d(TAG, "Gemini stream finished session=$sessionId")
    }

    private fun extractTextParts(payload: String): String? {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrNull() as? JsonObject
            ?: return null
        val candidates = root["candidates"] as? JsonArray ?: return null
        val first = candidates.firstOrNull() as? JsonObject ?: return null
        val content = first["content"] as? JsonObject ?: return null
        val parts = content["parts"] as? JsonArray ?: return null
        val sb = StringBuilder()
        for (p in parts) {
            val obj = p as? JsonObject ?: continue
            val text = runCatching { obj["text"]?.jsonPrimitive?.content }.getOrNull()
            if (!text.isNullOrEmpty()) sb.append(text)
        }
        return if (sb.isEmpty()) null else sb.toString()
    }
}
