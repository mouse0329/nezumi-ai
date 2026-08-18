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
        prompt: String,
        images: List<ByteArray>,
        config: CloudInferenceParams,
        onDelta: (String) -> Unit
    ) {
        val baseUrl = resolveBaseUrl()
        val apiKey = resolveApiKey()
        val endpoint = "$baseUrl/api/chat"
        val split = CloudPromptSplitter.splitOptionalSystem(prompt)
        val systemPart = split.first
        val userPart = split.second

        val messages = buildMessages(systemPart, userPart, images)
        val roles = messages.map { message ->
            message["role"]?.jsonPrimitive?.content
        }
        val hasToolsBlock = prompt.contains("<tools>") && prompt.contains("</tools>")
        CloudLog.d(
            TAG,
            "Ollama request messages=${messages.size} roles=$roles " +
                "hasToolTurn=${roles.any { role -> role == \"tool\" }} " +
                "hasToolsBlock=$hasToolsBlock promptLen=${prompt.length}"
        )

        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                messages.forEach { message -> add(message) }
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

    /**
     * Reconstruct the second and later tool rounds as proper Ollama chat turns.
     *
     * The Gemma4 tool schema is injected into the flattened prompt by
     * GgufToolPromptBuilder. Depending on the prompt builder, it may not be preceded
     * by the literal "System:" marker, so CloudPromptSplitter can leave it inside
     * userPart. Extract it explicitly and keep it in the system message. Otherwise
     * the round-2 reconstruction would lose the tool schema (or leave it in the
     * ordinary user turn), which is exactly the failure mode seen in logs.
     */
    private fun buildMessages(
        systemPart: String?,
        userPart: String,
        images: List<ByteArray>
    ): List<JsonObject> {
        val toolsStart = userPart.indexOf("<tools>")
        val toolsEndMarker = "</tools>"
        val toolsEnd = if (toolsStart >= 0) {
            userPart.indexOf(toolsEndMarker, toolsStart + "<tools>".length)
        } else {
            -1
        }
        val extractedTools = if (toolsStart >= 0 && toolsEnd >= 0) {
            userPart.substring(toolsStart, toolsEnd + toolsEndMarker.length).trim()
        } else {
            null
        }

        val effectiveSystem = when {
            !systemPart.isNullOrBlank() && extractedTools != null ->
                systemPart.trimEnd() + "\n\n" + extractedTools
            !systemPart.isNullOrBlank() -> systemPart
            extractedTools != null -> extractedTools
            else -> null
        }

        val withoutTools = if (toolsStart >= 0 && toolsEnd >= 0) {
            (userPart.substring(0, toolsStart) + userPart.substring(toolsEnd + toolsEndMarker.length)).trim()
        } else {
            userPart
        }

        val callOpen = "<|tool_call>"
        val callClose = "<tool_call|>"
        val responseOpen = "<tool_response>"
        val responseClose = "</tool_response>"
        val callStart = withoutTools.lastIndexOf(callOpen)
        val responseStart = withoutTools.lastIndexOf(responseOpen)
        val callEndMarker = if (callStart >= 0) {
            withoutTools.indexOf(callClose, callStart + callOpen.length)
        } else {
            -1
        }
        val hasCompletedToolTurn =
            callStart >= 0 && callEndMarker >= 0 && responseStart > callEndMarker

        if (!hasCompletedToolTurn) {
            return buildSimpleMessages(effectiveSystem, withoutTools, images)
        }

        val callEnd = callEndMarker + callClose.length
        val originalUser = withoutTools.substring(0, callStart).trimEnd()
        val assistantCall = withoutTools.substring(callStart, callEnd).trim()

        val toolResults = mutableListOf<String>()
        var cursor = responseStart
        while (cursor < withoutTools.length) {
            val open = withoutTools.indexOf(responseOpen, cursor)
            if (open < 0) break
            val contentStart = open + responseOpen.length
            val close = withoutTools.indexOf(responseClose, contentStart)
            if (close < 0) {
                toolResults += withoutTools.substring(contentStart).trim()
                break
            }
            toolResults += withoutTools.substring(contentStart, close).trim()
            cursor = close + responseClose.length
        }

        if (toolResults.isEmpty()) {
            return buildSimpleMessages(effectiveSystem, withoutTools, images)
        }

        CloudLog.d(
            TAG,
            "Ollama tool round reconstructed: systemLen=${effectiveSystem?.length ?: 0} " +
                "userLen=${originalUser.length} assistantToolLen=${assistantCall.length} " +
                "toolResults=${toolResults.size}"
        )

        return buildList {
            if (!effectiveSystem.isNullOrBlank()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", effectiveSystem)
                })
            }

            add(buildJsonObject {
                put("role", "user")
                put("content", originalUser)
                if (images.isNotEmpty()) {
                    putJsonArray("images") {
                        images.forEach { jpeg ->
                            add(ImageEncoding.encodeJpegBase64(jpeg))
                        }
                    }
                }
            })

            add(buildJsonObject {
                put("role", "assistant")
                put("content", assistantCall)
            })

            toolResults.forEach { result ->
                add(buildJsonObject {
                    put("role", "tool")
                    put("content", result)
                })
            }
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
                putJsonArray("images") {
                    images.forEach { jpeg ->
                        add(ImageEncoding.encodeJpegBase64(jpeg))
                    }
                }
            }
        })
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
                .append("<tool_call|")
                .append(">")
        }

        return if (builder.isEmpty()) null else builder.toString()
    }
}
