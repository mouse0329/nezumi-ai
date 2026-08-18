package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import com.nezumi_ai.data.inference.cloud.CloudLog
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
import kotlinx.serialization.json.JsonElement

/** LM Studio (OpenAI 互換) エンジン (commonMain / Ktor)。data URI→生Base64 リトライは同一。 */
class LmStudioInferenceEngine(
    secureStore: com.nezumi_ai.data.inference.cloud.PlatformSecureStore,
    configProvider: com.nezumi_ai.data.inference.CloudModelConfigProvider,
    toolExecutor: com.nezumi_ai.data.inference.CloudToolExecutor?
) : AbstractCloudInferenceEngine(secureStore, configProvider, toolExecutor, CloudApiKeyStore.Provider.LM_STUDIO) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http get() = CloudHttpClient.instance

    override suspend fun runStreamingInference(
        session: ProducerScope<String>, sessionId: Long, model: String, prompt: String,
        images: List<ByteArray>, config: CloudInferenceParams, onDelta: (String) -> Unit
    ) {
        val baseUrl = resolveBaseUrl(); val apiKey = resolveApiKey()
        val endpoint = "$baseUrl/v1/chat/completions"
        val firstErr = attempt(endpoint, apiKey, model, prompt, images, config, true, session, onDelta)
        if (firstErr != null && images.isNotEmpty()) {
            if (firstErr.contains(" 4", ignoreCase = false) || firstErr.contains("400", ignoreCase = false)) {
                CloudLog.w(TAG, "Retrying LM Studio without data URI due to: $firstErr")
                val secondErr = attempt(endpoint, apiKey, model, prompt, images, config, false, session, onDelta)
                if (secondErr != null) throw CloudRequestException("LM Studio request failed after retry: $secondErr")
                return
            }
            throw CloudRequestException("LM Studio request failed: $firstErr")
        }
        if (firstErr != null) throw CloudRequestException("LM Studio request failed: $firstErr")
    }

    private suspend fun attempt(
        endpoint: String, apiKey: String, model: String, prompt: String, images: List<ByteArray>,
        config: CloudInferenceParams, useDataUriForImages: Boolean,
        session: ProducerScope<String>, onDelta: (String) -> Unit
    ): String? {
        val bodyJson = OpenAiCompatSupport.buildRequestBody(model, prompt, images, config, stream = true, useDataUriForImages = useDataUriForImages)
        return http.preparePost(endpoint) {
            header(HttpHeaders.Accept, "text/event-stream"); contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(bodyJson.toString())
        }.execute { response ->
            registerResponse(response)
            if (!response.status.isSuccess()) {
                val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                return@execute "HTTP ${response.status.value} ${bodyText.take(500)}"
            }
            val dataBuffer = StringBuilder()
            suspend fun dispatch(data: String): Boolean {
                if (session.isClosedForSend) return false
                if (data.trim() == "[DONE]") return false
                val delta = OpenAiCompatSupport.extractDeltaContent(data) { parseSafely(it) }
                if (delta != null) onDelta(delta)
                return true
            }
            withStreamChannel(response) { ch ->
                while (true) {
                    val line = readStreamLineOrThrow(ch) ?: break
                    if (line.isEmpty()) { if (dataBuffer.isNotEmpty()) { val d = dataBuffer.toString(); dataBuffer.setLength(0); if (!dispatch(d)) return@withStreamChannel }; continue }
                    if (line.startsWith(":")) continue
                    val c = line.indexOf(':'); if (c < 0) continue
                    val field = line.substring(0, c); var raw = line.substring(c + 1); if (raw.startsWith(" ")) raw = raw.substring(1)
                    if (field == "data") { if (dataBuffer.isNotEmpty()) dataBuffer.append('\n'); dataBuffer.append(raw) }
                }
                if (dataBuffer.isNotEmpty()) dispatch(dataBuffer.toString())
            }
            null
        }
    }

    private fun parseSafely(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}
