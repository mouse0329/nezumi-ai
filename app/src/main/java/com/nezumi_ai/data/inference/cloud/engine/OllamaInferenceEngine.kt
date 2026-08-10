package com.nezumi_ai.data.inference.cloud.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.CloudPromptSplitter
import com.nezumi_ai.data.inference.cloud.ImageEncoding
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Ollama (`{baseUrl}/api/chat`) ネイティブ API 向けストリーミングエンジン。
 *
 * Ollama Local / Remote は接続先 URL とキー管理単位が異なるだけで
 * ワイヤフォーマットは同一なので、[provider] だけ差し替えて共通ロジックで扱う。
 *
 * ## リクエスト
 * ```json
 * {
 *   "model": "llama3.2:3b",
 *   "stream": true,
 *   "messages": [
 *     { "role": "system", "content": "..." },   // optional
 *     { "role": "user",   "content": "...",
 *       "images": ["<base64-jpeg>", "..."] }    // optional
 *   ],
 *   "options": {
 *     "temperature": 0.7, "top_p": 0.95,
 *     "num_predict": 1024, "stop": [...]
 *   }
 * }
 * ```
 *
 * ## レスポンス (NDJSON)
 * 1 行 1 JSON:
 * ```
 * {"model":"llama3.2:3b","message":{"role":"assistant","content":"He"},"done":false}
 * {"model":"llama3.2:3b","message":{"role":"assistant","content":"llo"},"done":false}
 * {"model":"llama3.2:3b","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop", ...}
 * ```
 * `done:true` が来たら終端。SSE と違って `data:` プレフィックスは付かない。
 */
class OllamaInferenceEngine(
    context: Context,
    provider: CloudApiKeyStore.Provider
) : AbstractCloudInferenceEngine(context, provider) {

    init {
        require(
            provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
                provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE
        ) { "OllamaInferenceEngine requires OLLAMA_LOCAL or OLLAMA_REMOTE provider" }
    }

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
        val baseUrl = CloudApiKeyStore.getBaseUrl(appContext, provider)
        // Remote は Bearer トークンによる認証を要求するホスティングがある。
        // Local は原則不要だが、リバースプロキシ越しに置いているケースもあるので
        // 「保存されていれば付ける」動作にする。
        val apiKey = CloudApiKeyStore.getApiKey(appContext, provider)
        val endpoint = "$baseUrl/api/chat"

        val (systemPart, userPart) = CloudPromptSplitter.splitOptionalSystem(prompt)

        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                if (!systemPart.isNullOrBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPart)
                    }
                }
                addJsonObject {
                    put("role", "user")
                    put("content", userPart)
                    if (images.isNotEmpty()) {
                        putJsonArray("images") {
                            images.forEach { bmp ->
                                add(ImageEncoding.encodeJpegBase64(bmp))
                            }
                        }
                    }
                }
            }
            putJsonObject("options") {
                put("temperature", config.temperature.toDouble())
                put("top_p", config.topP.toDouble())
                put("num_predict", config.maxTokens)
                // Ollama の "num_ctx" は現在の contextWindow をそのまま渡してよい。
                // ロード側の VRAM 割当に反映される。
                put("num_ctx", config.contextWindow)
                if (config.customStopTokens.isNotEmpty()) {
                    putJsonArray("stop") {
                        config.customStopTokens.forEach { add(it) }
                    }
                }
            }
        }

        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "application/x-ndjson")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(APPLICATION_JSON))
        if (apiKey.isNotBlank()) {
            builder.header("Authorization", "Bearer $apiKey")
        }
        val call = http.newCall(builder.build())
        registerCall(call)

        val response = call.execute()
        if (!response.isSuccessful) {
            val bodyText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            val code = response.code
            response.close()
            throw java.io.IOException("Ollama request failed: HTTP $code ${bodyText.take(500)}")
        }

        val source = response.body?.source()
        if (source == null) {
            response.close()
            throw java.io.IOException("Ollama response has no body")
        }

        try {
            while (!session.isClosedForSend) {
                val line = try {
                    source.readUtf8Line()
                } catch (t: Throwable) {
                    null
                } ?: break
                if (line.isEmpty()) continue
                val (delta, done) = parseChunk(line)
                if (!delta.isNullOrEmpty()) onDelta(delta)
                if (done) break
            }
        } finally {
            response.close()
        }

        Log.d(TAG, "Ollama stream finished session=$sessionId")
    }

    /** NDJSON 1 行を解析して (delta, done) を返す。 */
    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return null to false
        val done = runCatching {
            root["done"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true)
        }.getOrNull() ?: false
        val message = root["message"] as? JsonObject
        val delta = message?.let {
            runCatching { it["content"]?.jsonPrimitive?.content }.getOrNull()
        }
        return delta to done
    }

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
