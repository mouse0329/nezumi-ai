package com.nezumi_ai.data.inference.cloud.engine

import com.nezumi_ai.data.inference.CloudInferenceParams
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions 互換 API 向け共通ヘルパ (commonMain 版)。
 * 未使用だった extractFullMessage は削除済み (レビュー回答 #5)。
 */
internal object OpenAiCompatSupport {

    fun buildRequestBody(
        model: String,
        prompt: String,
        images: List<ByteArray>,
        config: CloudInferenceParams,
        stream: Boolean = true,
        useDataUriForImages: Boolean = true
    ): JsonObject {
        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        return buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("temperature", config.temperature.toDouble())
            put("top_p", config.topP.toDouble())
            put("max_tokens", config.maxTokens)
            if (config.customStopTokens.isNotEmpty()) {
                putJsonArray("stop") { config.customStopTokens.forEach { add(it) } }
            }
            putJsonArray("messages") {
                if (!systemPart.isNullOrBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPart)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    if (images.isEmpty()) {
                        put("content", userPart)
                    } else {
                        putJsonArray("content") {
                            if (userPart.isNotBlank()) {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", userPart)
                                }
                            }
                            images.forEach { jpegBytes ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        val urlValue = if (useDataUriForImages) {
                                            ImageEncoding.encodeJpegDataUri(jpegBytes)
                                        } else {
                                            ImageEncoding.encodeJpegBase64(jpegBytes)
                                        }
                                        put("url", urlValue)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun extractDeltaContent(payload: String, jsonParser: (String) -> JsonElement?): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return null
        val root = jsonParser(trimmed) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        val content = delta["content"] ?: return null
        return runCatching { content.jsonPrimitive.content }.getOrNull()
    }
}
