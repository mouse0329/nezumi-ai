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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
        val messages = buildMessages(systemPart, userPart, images)

        CloudLog.d(TAG, "Ollama request messages=${messages.size} hasToolTurn=${messages.any { message -> message[\"role\"]?.jsonPrimitive?.content == \"tool\" }}")

        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") { messages.forEach { message -> add(message) } }
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

        http.preparePost(endpoint) {
            header(HttpHeaders.Accept, "application/x-ndjson")
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            setBody(bodyJson.toString())
        }.execute { response ->
            registerResponse(response)
            if (!response.status.isSuccess()) {
                val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                throw CloudRequestException("Ollama request failed: HTTP ${response.status.value} ${bodyText.take(500)}")
            }

            var lineCount = 0
            withStreamChannel(response) { ch ->
                while (!session.isClosedForSend) {
                    // IMPORTANT: never reacquire bodyAsChannel() and never swallow read errors.
                    // A swallowed read failure previously looked exactly like an empty model
                    // response (rawLen=0), preventing the Gemma4 tool-call parser from running.
                    val line = readStreamLineOrThrow(ch) ?: break
                    if (line.isEmpty()) continue
                    lineCount++
                    if (lineCount == 1) {
                        CloudLog.d(TAG, "Ollama first stream line len=${line.length} preview=\"${line.take(300)}\"")
                    }
                    val (delta, done) = parseChunk(line)
                    if (!delta.isNullOrEmpty()) onDelta(delta)
                    if (done) break
                }
            }
            CloudLog.d(TAG, "Ollama stream reader finished session=$sessionId lines=$lineCount")
        }
        CloudLog.d(TAG, "Ollama stream finished session=$sessionId")
    }

    /**
     * AbstractCloudInferenceEngine は tool round の履歴を prompt 文字列として組み立てる。
     * Ollama ではそれを chat の role に戻す必要がある。
     *
     * 2回目の request:
     *   user      = 元の質問
     *   assistant = Gemma4 の tool_call
     *   tool      = 実行結果
     *
     * これを user 1件に押し込むと、tool 実行後の結果をモデルが tool turn として
     * 解釈できず、最終回答が空になる。
     */
    private fun buildMessages(
        systemPart: String?,
        userPart: String,
        images: List<ByteArray>
    ): List<JsonObject> {
        val gemmaOpen = "<|tool_call>"
        val gemmaClose = "<tool_call|>"
        val responseOpen = "<tool_response>"
        val responseClose = "</tool_response>"

        val callStart = userPart.indexOf(gemmaOpen)
        val responseStart = userPart.indexOf(responseOpen, if (callStart >= 0) callStart else 0)

        if (callStart < 0 || responseStart < 0) {
            return buildSimpleMessages(systemPart, userPart, images)
        }

        val callEndMarker = userPart.indexOf(gemmaClose, callStart + gemmaOpen.length)
        if (callEndMarker < 0 || callEndMarker >= responseStart) {
            return buildSimpleMessages(systemPart, userPart, images)
        }
        val callEnd = callEndMarker + gemmaClose.length
        val responseEndMarker = userPart.indexOf(responseClose, responseStart + responseOpen.length)
        val responseEnd = if (responseEndMarker >= 0) responseEndMarker else userPart.length

        val beforeCall = userPart.substring(0, callStart).trim()
        val assistantCall = userPart.substring(callStart, callEnd).trim()
        val toolResult = userPart.substring(responseStart + responseOpen.length, responseEnd).trim()

        CloudLog.d(TAG, "Ollama tool round reconstructed: userLen=${beforeCall.length} assistantToolLen=${assistantCall.length} toolResultLen=${toolResult.length}")

        return buildList {
            if (!systemPart.isNullOrBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPart)
                })
            }
            add(buildJsonObject {
                put("role", "user")
                put("content", beforeCall)
                if (images.isNotEmpty()) {
                    putJsonArray("images") { images.forEach { jpeg -> add(ImageEncoding.encodeJpegBase64(jpeg)) } }
                }
            })
            add(buildJsonObject {
                put("role", "assistant")
                put("content", assistantCall)
            })
            add(buildJsonObject {
                put("role", "tool")
                put("content", toolResult)
            })
        }
    }

    private fun buildSimpleMessages(
        systemPart: String?,
        userPart: String,
        images: List<ByteArray>
    ): List<JsonObject> = buildList {
        if (!systemPart.isNullOrBlank()) {
            add(buildJsonObject {
                put("role", "system")
                put("content", systemPart)
            })
        }
        add(buildJsonObject {
            put("role", "user")
            put("content", userPart)
            if (images.isNotEmpty()) {
                putJsonArray("images") { images.forEach { jpeg -> add(ImageEncoding.encodeJpegBase64(jpeg)) } }
            }
        })
    }

    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return null to false
        val done = runCatching { root["done"]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: false
        val message = root["message"] as? JsonObject
        val contentDelta = message?.let {
            runCatching { it["content"]?.jsonPrimitive?.content }.getOrNull()
        }

        // tool_calls は done=true の最終チャンクに限らない。
        // Ollama のストリーム途中の chunk に来た場合も必ず上位へ渡す。
        val toolCallsDelta = message?.get("tool_calls")?.let {
            synthesizeGemma4ToolCallText(it)
        }
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
            val argumentsJson = when (val arguments = function["arguments"]) {
                is JsonObject -> arguments.toString()
                is JsonPrimitive -> arguments.content
                null -> "{}"
                else -> arguments.toString()
            }
            builder.append("<|tool_call>call:")
                .append(name)
                .append(argumentsJson)
                .append("<tool_call|>")
        }
        return if (builder.isEmpty()) null else builder.toString()
    }
}
