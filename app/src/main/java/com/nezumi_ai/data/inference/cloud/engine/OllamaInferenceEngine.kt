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
 * Ollama Local / Cloud は接続先 URL とキー管理単位が異なるだけで
 * ワイヤフォーマットは同一なので、[provider] だけ差し替えて共通ロジックで扱う。
 * Ollama Cloud (ollama.com) は Bearer 認証が必須 (https://docs.ollama.com/cloud)。
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
                provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE // 旧称。実態は Ollama Cloud
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
        val baseUrl = resolveBaseUrl()
        // Cloud (ollama.com) は Bearer トークンによる認証が必須。
        // Local は原則不要だが、リバースプロキシ越しに置いているケースもあるので
        // 「保存されていれば付ける」動作にする。
        val apiKey = resolveApiKey()
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
                // デバッグ: message.content が常に空になる不具合の原因切り分け用。
                // Ollama がネイティブ tool_calls フィールドで応答している可能性があるため、
                // 生の NDJSON 行をそのまま出力して確認する。
                Log.d(TAG, "raw NDJSON line: $line")
                val (delta, done) = parseChunk(line)
                if (!delta.isNullOrEmpty()) onDelta(delta)
                if (done) break
            }
        } finally {
            response.close()
        }

        Log.d(TAG, "Ollama stream finished session=$sessionId")
    }

    /** NDJSON 1 行を解析して (delta, done) を返す。
     *
     * Ollama はモデルがツール対応と認識された場合、テキストの `message.content` ではなく
     * 構造化された `message.tool_calls` フィールドでツール呼び出しを返すことがある
     * （OpenAI 互換のネイティブツールコール機能）。このアプリのプロンプト設計は
     * システムプロンプト内で Gemma4 の `<|tool_call>` テキスト形式を指示しているため、
     * `message.content` しか見ないと `tool_calls` フィールドの内容を取りこぼし、
     * 応答が完全に空になってしまう。ここで `tool_calls` を検出したら Gemma4 形式の
     * テキストに合成し、通常の content デルタと同様に流す。
     */
    private fun parseChunk(line: String): Pair<String?, Boolean> {
        val root = runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
            ?: return null to false
        val done = runCatching {
            root["done"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true)
        }.getOrNull() ?: false
        val message = root["message"] as? JsonObject
        val contentDelta = message?.let {
            runCatching { it["content"]?.jsonPrimitive?.content }.getOrNull()
        }
        val toolCallsDelta = message?.get("tool_calls")?.let { synthesizeGemma4ToolCallText(it) }
        val delta = when {
            !toolCallsDelta.isNullOrEmpty() -> (contentDelta.orEmpty()) + toolCallsDelta
            else -> contentDelta
        }
        return delta to done
    }

    /**
     * Ollama ネイティブの `tool_calls` 配列 (`[{"function":{"name":..,"arguments":{...}}}]`) を
     * このアプリの Gemma4 パーサーが解釈できる `<|tool_call>call:NAME{...}<tool_call|>` テキストに変換する。
     * 配列でない/空/形式不明な場合は null を返す。
     */
    private fun synthesizeGemma4ToolCallText(toolCallsElement: kotlinx.serialization.json.JsonElement): String? {
        val array = toolCallsElement as? kotlinx.serialization.json.JsonArray ?: return null
        if (array.isEmpty()) return null
        val builder = StringBuilder()
        for (entry in array) {
            val obj = entry as? JsonObject ?: continue
            val function = obj["function"] as? JsonObject ?: continue
            val name = runCatching { function["name"]?.jsonPrimitive?.content }.getOrNull() ?: continue
            val argumentsElement = function["arguments"]
            val argumentsJson = when (argumentsElement) {
                is kotlinx.serialization.json.JsonObject -> argumentsElement.toString()
                is kotlinx.serialization.json.JsonPrimitive -> argumentsElement.content
                null -> "{}"
                else -> argumentsElement.toString()
            }
            builder.append("<|tool_call>call:").append(name).append(argumentsJson).append("<tool_call|>")
        }
        return if (builder.isEmpty()) null else builder.toString()
    }

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
