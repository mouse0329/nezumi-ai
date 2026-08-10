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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini API (`v1beta/models/{model}:streamGenerateContent`) 向けエンジン。
 *
 * ## エンドポイント
 * `POST {baseUrl}/v1beta/models/{model}:streamGenerateContent?alt=sse&key={apiKey}`
 *
 * `alt=sse` を付けないと "chunked JSON array" 形式で返ってきて、
 * 行単位で扱えず面倒なので必ず SSE を要求する。
 *
 * ## リクエスト
 * ```json
 * {
 *   "contents": [
 *     { "role": "user", "parts": [
 *         { "text": "..." },
 *         { "inline_data": { "mime_type": "image/jpeg", "data": "<b64>" } }
 *     ] }
 *   ],
 *   "systemInstruction": { "parts": [ { "text": "..." } ] },  // 任意
 *   "generationConfig": {
 *     "temperature": 0.7, "topP": 0.95,
 *     "maxOutputTokens": 1024,
 *     "stopSequences": [...]
 *   }
 * }
 * ```
 *
 * ## SSE レスポンス
 * ```
 * data: {"candidates":[{"content":{"parts":[{"text":"He"}],"role":"model"}}]}
 * data: {"candidates":[{"content":{"parts":[{"text":"llo"}],"role":"model"}}]}
 * ...
 * ```
 * 終端シグナルは `finishReason` を含むチャンクの到来か、単純にストリーム終了。
 */
class GeminiInferenceEngine(
    context: Context
) : AbstractCloudInferenceEngine(context, CloudApiKeyStore.Provider.GEMINI) {

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

        // model 名は URL パス埋め込みなので、":streamGenerateContent" 手前に安全に置く。
        // Ollama と違って ':' を含む Gemini モデル名は無いので、そのまま連結してよい。
        val endpoint = "$baseUrl/v1beta/models/$model:streamGenerateContent"
        val url = endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("alt", "sse")
            .addQueryParameter("key", apiKey)
            .build()

        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            if (!systemPart.isNullOrBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") {
                        addJsonObject { put("text", systemPart) }
                    }
                }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        if (userPart.isNotBlank()) {
                            addJsonObject { put("text", userPart) }
                        }
                        images.forEach { bmp ->
                            addJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", ImageEncoding.DEFAULT_MIME)
                                    put("data", ImageEncoding.encodeJpegBase64(bmp))
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
                    putJsonArray("stopSequences") {
                        config.customStopTokens.forEach { add(it) }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url(url)
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
            throw java.io.IOException("Gemini request failed: HTTP $code ${bodyText.take(500)}")
        }

        SseLineReader(response).use { reader ->
            reader.forEachMessage { _, data ->
                if (session.isClosedForSend) return@forEachMessage false
                val trimmed = data.trim()
                if (trimmed.isEmpty()) return@forEachMessage true
                val text = extractTextParts(trimmed)
                if (text != null) onDelta(text)
                true
            }
        }

        Log.d(TAG, "Gemini stream finished session=$sessionId")
    }

    /**
     * `candidates[0].content.parts[*].text` を連結して返す。
     * 複数 parts が入る可能性 (関数呼び出しなど) もあるが、今回は text 部分のみ拾う。
     */
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

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
