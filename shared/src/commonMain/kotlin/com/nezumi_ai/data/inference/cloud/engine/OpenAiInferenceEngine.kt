package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudLog
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
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI Chat Completions API 用ストリーミングエンジン (commonMain / Ktor 版)。
 */
class OpenAiInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?
) : AbstractCloudInferenceEngine(
    secureStore, configProvider, toolExecutor,
    com.nezumi_ai.data.inference.cloud.CloudApiKeyStore.Provider.OPENAI
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
        val endpoint = "$baseUrl/v1/chat/completions"

        val bodyJson = OpenAiCompatSupport.buildRequestBody(
            model = model, prompt = prompt, images = images, config = config,
            stream = true, useDataUriForImages = true
        )

        val response = http.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            header(HttpHeaders.Accept, "text/event-stream")
            contentType(ContentType.Application.Json)
            setBody(bodyJson.toString())
        }
        registerResponse(response)

        if (!response.status.isSuccess()) {
            val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
            throw CloudRequestException("OpenAI request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
        }

        val dataBuffer = StringBuilder()
        suspend fun dispatch(data: String): Boolean {
            if (session.isClosedForSend) return false
            val delta = OpenAiCompatSupport.extractDeltaContent(data) { parseSafely(it) }
            if (data.trim() == "[DONE]") return false
            if (delta != null) onDelta(delta)
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

        CloudLog.d(TAG, "OpenAI stream finished session=$sessionId")
    }

    private fun parseSafely(text: String): JsonElement? {
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }
}
