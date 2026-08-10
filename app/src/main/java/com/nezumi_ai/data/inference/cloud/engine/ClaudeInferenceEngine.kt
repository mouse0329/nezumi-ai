package com.nezumi_ai.data.inference.cloud.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import com.nezumi_ai.data.inference.cloud.SseLineReader
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Anthropic Claude Messages API 向けストリーミングエンジン。
 *
 * ## エンドポイント
 * `POST {baseUrl}/v1/messages`
 *
 * ## 認証
 * - `x-api-key: <apiKey>`
 * - `anthropic-version: 2023-06-01` (Messages API の安定バージョンヘッダ)
 *
 * ## リクエスト
 * ```json
 * {
 *   "model": "claude-...",
 *   "max_tokens": 1024,
 *   "temperature": 0.7,
 *   "top_p": 0.95,
 *   "stream": true,
 *   "system": "...",
 *   "messages": [
 *     { "role": "user", "content": [
 *         { "type": "text", "text": "..." },
 *         { "type": "image", "source": { "type":"base64","media_type":"image/jpeg","data":"..." } }
 *     ] }
 *   ]
 * }
 * ```
 *
 * ## SSE
 * Anthropic は typed event で
 *   `event: content_block_delta` / `data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"..."}}`
 * のように text_delta を送ってくる。message_start / content_block_start /
 * content_block_stop / message_delta / message_stop は今回のスコープでは無視して良い。
 */
class ClaudeInferenceEngine(
    context: Context
) : AbstractCloudInferenceEngine(context, CloudApiKeyStore.Provider.CLAUDE) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun runStreamingInference(
        session: ProducerScope<String>,
        sessionId: Long,
        model: String,
        prompt: String,
        images: List<Bitmap>,
        config: InferenceConfig,
        onDelta: (String) -> Unit
    ) {
        val apiKey = resolveApiKey()
        val baseUrl = resolveBaseUrl()
        val endpoint = "$baseUrl/v1/messages"

        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            put("model", model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature.toDouble())
            put("top_p", config.topP.toDouble())
            put("stream", true)
            if (!systemPart.isNullOrBlank()) {
                put("system", systemPart)
            }
            if (config.customStopTokens.isNotEmpty()) {
                putJsonArray("stop_sequences") {
                    config.customStopTokens.forEach { add(it) }
                }
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        if (userPart.isNotBlank()) {
                            addJsonObject {
                                put("type", "text")
                                put("text", userPart)
                            }
                        }
                        images.forEach { bmp ->
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", ImageEncoding.DEFAULT_MIME)
                                    put("data", ImageEncoding.encodeJpegBase64(bmp))
                                }
                            }
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(APPLICATION_JSON))
            .build()

        val call = http.newCall(request)
        registerCall(call)

        val response = call.execute()
        if (!response.isSuccessful) {
            val bodyText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val code = response.code
            response.close()
            throw java.io.IOException("Claude request failed: HTTP $code ${bodyText.take(500)}")
        }

        SseLineReader(response).use { reader ->
            reader.forEachMessage { event, data ->
                if (session.isClosedForSend) return@forEachMessage false
                // 早期リターン: message_stop で終了
                if (event == "message_stop") return@forEachMessage false
                if (event != null && event != "content_block_delta") return@forEachMessage true
                val text = extractTextDelta(data)
                if (text != null) onDelta(text)
                true
            }
        }

        Log.d(TAG, "Claude stream finished session=$sessionId")
    }

    /**
     * `data:` ペイロードから text_delta のテキストだけを取り出す。
     * text_delta 以外の delta タイプ (input_json_delta 等) は無視。
     */
    private fun extractTextDelta(payload: String): String? {
        val root = runCatching { json.parseToJsonElement(payload.trim()) }.getOrNull() as? JsonObject
            ?: return null
        val type = runCatching { root["type"]?.jsonPrimitive?.content }.getOrNull()
        if (type != "content_block_delta") return null
        val delta = root["delta"] as? JsonObject ?: return null
        val deltaType = runCatching { delta["type"]?.jsonPrimitive?.content }.getOrNull()
        if (deltaType != "text_delta") return null
        return runCatching { delta["text"]?.jsonPrimitive?.content }.getOrNull()
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
