package com.nezumi_ai.data.inference.cloud.engine

import android.graphics.Bitmap
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * OpenAI Chat Completions 互換 API (OpenAI 公式 / LM Studio / Ollama の
 * OpenAI 互換モード等) 向けの共通ヘルパ。
 *
 * リクエストボディ・SSE レスポンス形式はすべて OpenAI と同一なので、
 * この 1 ファイルに集約する。
 */
internal object OpenAiCompatSupport {

    /**
     * Chat Completions のリクエスト本文を組み立てる。
     *
     * @param useDataUriForImages true の場合、画像を `image_url.url = "data:image/jpeg;base64,..."`
     *   で埋め込む (OpenAI 公式の仕様)。false の場合は `image_url.url` に生 Base64 を入れる
     *   (LM Studio の一部バージョンで data URI が弾かれる不具合への対処)。
     */
    fun buildRequestBody(
        model: String,
        prompt: String,
        images: List<Bitmap>,
        config: InferenceConfig,
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
                putJsonArray("stop") {
                    config.customStopTokens.forEach { add(it) }
                }
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
                            images.forEach { bmp ->
                                addJsonObject {
                                    put("type", "image_url")
                                    putJsonObject("image_url") {
                                        val urlValue = if (useDataUriForImages) {
                                            ImageEncoding.encodeJpegDataUri(bmp)
                                        } else {
                                            ImageEncoding.encodeJpegBase64(bmp)
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

    /**
     * SSE の `data:` ペイロード 1 件から `choices[0].delta.content` を抽出する。
     * `data: [DONE]` の場合は null (ストリーム終了) を返す。
     * パースに失敗した場合も null (無害な行として無視) を返す。
     */
    fun extractDeltaContent(payload: String, jsonParser: (String) -> JsonElement?): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]") return null
        val root = jsonParser(trimmed) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val delta = first["delta"] as? JsonObject ?: return null
        val content = delta["content"] ?: return null
        // "content" が null の可能性 (role only チャンク) もあるので JsonPrimitive で判定
        return runCatching { content.jsonPrimitive.content }.getOrNull()
    }

    /**
     * 非ストリームレスポンスから `choices[0].message.content` を抽出する。
     * エラーレスポンスの整形などで使う想定。
     */
    fun extractFullMessage(root: JsonObject): String? {
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject ?: return null
        return runCatching { message["content"]?.jsonPrimitive?.content }.getOrNull()
    }
}
