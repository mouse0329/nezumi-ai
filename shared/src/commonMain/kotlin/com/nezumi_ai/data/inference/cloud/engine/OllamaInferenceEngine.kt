package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudHttpClient
import com.nezumi_ai.data.inference.cloud.CloudChatMessage
import com.nezumi_ai.data.inference.cloud.CloudLog
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

/** Ollama native API engine. Reads the streaming NDJSON response line-by-line. */
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
        ) {
            "OllamaInferenceEngine requires OLLAMA_LOCAL or OLLAMA_REMOTE provider"
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http
        get() = CloudHttpClient.instance

    override suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        messages: List<CloudChatMessage>,
        config: CloudInferenceParams,
        onDelta: (String) -> Unit
    ) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val endpoint = "$baseUrl/api/chat"

        // Phase 5: role 付きメッセージ配列から直接構築する。
        // <tools> ブロックの抽出や tool ターンの再構築はここでは行わず、
        // system 内のツール定義テキストはそのまま system メッセージとして送る。
        // 複数ターン履歴は role 配列として正しく構造化される (旧来の平文連結を廃止)。
        CloudLog.d(
            TAG,
            "Ollama request messages=${messages.size} " +
                "systemLen=${messages.filter { it.role == CloudChatMessage.Role.SYSTEM }.sumOf { it.text.length }} " +
                "hasToolsBlock=" + messages.any { it.text.contains("<tools>") && it.text.contains("</tools>") }
        )

        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { msg ->
                    val role = when (msg.role) {
                        CloudChatMessage.Role.SYSTEM -> "system"
                        CloudChatMessage.Role.ASSISTANT -> "assistant"
                        CloudChatMessage.Role.USER,
                        CloudChatMessage.Role.TOOL_RESULT -> "user"
                    }
                    if (msg.text.isBlank() && msg.images.isEmpty()) return@forEach
                    add(buildJsonObject {
                        put("role", role)
                        put("content", msg.text)
                        if (msg.images.isNotEmpty()) {
                            putJsonArray("images") {
                                msg.images.forEach { jpeg ->
                                    add(ImageEncoding.encodeJpegBase64(jpeg))
                                }
                            }
                        }
                    })
                }
            }
            putJsonObject("options") {
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
                put("num_predict", config.maxTokens)
                put("num_ctx", config.contextWindow)
                if (config.customStopTokens.isNotEmpty()) {
                    putJsonArray("stop") {
                        config.customStopTokens.forEach { stopToken -> add(stopToken) }
                    }
                }
            }
        }

        http.preparePost(endpoint) {
            header(HttpHeaders.Accept, "application/x-ndjson")
            contentType(ContentType.Application.Json)
            if (apiKey.isNotBlank()) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
            }
            setBody(bodyJson.toString())
        }.execute { response ->
            registerResponse(response)
            if (!response.status.isSuccess()) {
                val bodyText = runCatching { response.bodyAsText() }.getOrDefault("")
                throw CloudRequestException(
                    "Ollama request failed: HTTP ${response.status.value} ${bodyText.take(500)}"
                )
            }

            withStreamChannel(response) { channel ->
                while (!session.isClosedForSend) {
                    val line = readStreamLineOrThrow(channel) ?: break
                    if (line.isEmpty()) continue

                    CloudLog.d(
                        TAG,
                        "Ollama raw chunk session=$sessionId len=${line.length} preview=${line.take(300)}"
                    )

                    val parsed = parseChunk(line)
                    val delta = parsed.first
                    val done = parsed.second
                    if (!delta.isNullOrEmpty()) {
                        onDelta(delta)
                    }
                    if (done) break
                }
            }
        }

        CloudLog.d(TAG, "Ollama stream finished session=$sessionId")
    }

    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching {
            json.parseToJsonElement(line)
        }.getOrNull() as? JsonObject ?: return null to false

        val done = runCatching {
            root["done"]?.jsonPrimitive?.booleanOrNull
        }.getOrNull() ?: false

        val message = root["message"] as? JsonObject
        val contentDelta = message?.let { messageObject ->
            runCatching {
                messageObject["content"]?.jsonPrimitive?.content
            }.getOrNull()
        }

        val toolCallsDelta = message?.get("tool_calls")?.let { toolCalls ->
            synthesizeGemma4ToolCallText(toolCalls)
        }

        if (!toolCallsDelta.isNullOrEmpty()) {
            CloudLog.d(
                TAG,
                "Ollama native tool_calls detected chunk synthesizedLen=${toolCallsDelta.length}"
            )
        }

        val delta = if (!toolCallsDelta.isNullOrEmpty()) {
            contentDelta.orEmpty() + toolCallsDelta
        } else {
            contentDelta
        }

        return delta to done
    }

    private fun synthesizeGemma4ToolCallText(toolCallsElement: JsonElement): String? {
        val array = toolCallsElement as? JsonArray ?: return null
        if (array.isEmpty()) return null

        val builder = StringBuilder()
        for (entry in array) {
            val objectEntry = entry as? JsonObject ?: continue
            val function = objectEntry["function"] as? JsonObject ?: continue
            val name = runCatching {
                function["name"]?.jsonPrimitive?.content
            }.getOrNull() ?: continue

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
