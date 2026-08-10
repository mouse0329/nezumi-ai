package com.nezumi_ai.data.inference.cloud.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.SseLineReader
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * OpenAI Chat Completions API (`https://api.openai.com/v1/chat/completions`) 用の
 * ストリーミング推論エンジン。
 *
 * ## リクエスト
 * - `POST {baseUrl}/v1/chat/completions`
 * - Header: `Authorization: Bearer <apiKey>`, `Content-Type: application/json`
 * - Body: [OpenAiCompatSupport.buildRequestBody] で組み立てる
 *
 * ## レスポンス (SSE)
 * ```
 * data: {"id":"...","choices":[{"delta":{"content":"He"}}]}
 * data: {"id":"...","choices":[{"delta":{"content":"llo"}}]}
 * ...
 * data: [DONE]
 * ```
 * `[DONE]` で送信終了。
 */
class OpenAiInferenceEngine(
    context: Context
) : AbstractCloudInferenceEngine(context, CloudApiKeyStore.Provider.OPENAI) {

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
        val endpoint = "$baseUrl/v1/chat/completions"

        val bodyJson = OpenAiCompatSupport.buildRequestBody(
            model = model,
            prompt = prompt,
            images = images,
            config = config,
            stream = true,
            useDataUriForImages = true
        )

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(APPLICATION_JSON))
            .build()

        val call = http.newCall(request)
        registerCall(call)

        val response = call.execute()
        if (!response.isSuccessful) {
            val bodyText = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
            response.close()
            throw java.io.IOException(
                "OpenAI request failed: HTTP ${response.code} ${bodyText.take(500)}"
            )
        }

        SseLineReader(response).use { reader ->
            reader.forEachMessage { _, data ->
                if (session.isClosedForSend) return@forEachMessage false
                val delta = OpenAiCompatSupport.extractDeltaContent(data) { parseSafely(it) }
                if (data.trim() == "[DONE]") return@forEachMessage false
                if (delta != null) onDelta(delta)
                true
            }
        }

        Log.d(TAG, "OpenAI stream finished session=$sessionId")
    }

    private fun parseSafely(text: String): JsonElement? {
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
