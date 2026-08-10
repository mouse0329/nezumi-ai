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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * LM Studio の OpenAI 互換エンドポイント (`{baseUrl}/v1/chat/completions`) 向けエンジン。
 *
 * ## OpenAI との相違点
 * 1. 既定 [baseUrl] は `http://127.0.0.1:1234` (ローカル動作前提)。
 * 2. API キーは基本不要 (LM Studio の設定で有効にしている場合のみ Bearer を付ける)。
 * 3. 画像入力の `image_url.url` の形式が LM Studio のビルドによって
 *    - data URI (`data:image/jpeg;base64,...`) を受け付けるもの
 *    - 生 Base64 のみを受け付けるもの
 *   の 2 系統がある。既定は data URI で試行し、Vision が有効なはずのモデルで
 *   HTTP 4xx が返った場合のみ生 Base64 でリトライする、というフォールバックを持つ。
 */
class LmStudioInferenceEngine(
    context: Context
) : AbstractCloudInferenceEngine(context, CloudApiKeyStore.Provider.LM_STUDIO) {

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
        val apiKey = resolveApiKey() // 任意
        val endpoint = "$baseUrl/v1/chat/completions"

        // 1st attempt: data URI 形式
        val firstErr = attempt(
            endpoint = endpoint,
            apiKey = apiKey,
            model = model,
            prompt = prompt,
            images = images,
            config = config,
            useDataUriForImages = true,
            session = session,
            onDelta = onDelta
        )

        if (firstErr != null && images.isNotEmpty()) {
            // 画像付きで 4xx が返った場合のみ、生 Base64 でリトライする。
            // (Vision 未対応モデルへの画像送信そのものが不正なケースはこれでも直らないが、
            //  LM Studio 特有の "url field must be a base64-encoded image" バグには効く)
            if (firstErr.contains(" 4", ignoreCase = false) || firstErr.contains("400", ignoreCase = false)) {
                Log.w(TAG, "Retrying LM Studio without data URI due to: $firstErr")
                val secondErr = attempt(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    model = model,
                    prompt = prompt,
                    images = images,
                    config = config,
                    useDataUriForImages = false,
                    session = session,
                    onDelta = onDelta
                )
                if (secondErr != null) {
                    throw java.io.IOException("LM Studio request failed after retry: $secondErr")
                }
                return
            }
            throw java.io.IOException("LM Studio request failed: $firstErr")
        }

        if (firstErr != null) {
            throw java.io.IOException("LM Studio request failed: $firstErr")
        }
    }

    /**
     * 1 回の HTTP を実行する。成功時は null、失敗時はエラー文字列を返す。
     * ネットワーク層の例外はここでキャッチせず throw する (基底クラス側で FINAL 発行)。
     */
    private fun attempt(
        endpoint: String,
        apiKey: String,
        model: String,
        prompt: String,
        images: List<Bitmap>,
        config: InferenceConfig,
        useDataUriForImages: Boolean,
        session: ProducerScope<String>,
        onDelta: (String) -> Unit
    ): String? {
        val bodyJson = OpenAiCompatSupport.buildRequestBody(
            model = model,
            prompt = prompt,
            images = images,
            config = config,
            stream = true,
            useDataUriForImages = useDataUriForImages
        )

        val builder = Request.Builder()
            .url(endpoint)
            .header("Accept", "text/event-stream")
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
            return "HTTP $code ${bodyText.take(500)}"
        }

        SseLineReader(response).use { reader ->
            reader.forEachMessage { _, data ->
                if (session.isClosedForSend) return@forEachMessage false
                if (data.trim() == "[DONE]") return@forEachMessage false
                val delta = OpenAiCompatSupport.extractDeltaContent(data) { parseSafely(it) }
                if (delta != null) onDelta(delta)
                true
            }
        }
        return null
    }

    private fun parseSafely(text: String): JsonElement? {
        return runCatching { json.parseToJsonElement(text) }.getOrNull()
    }

    companion object {
        private val APPLICATION_JSON = "application/json; charset=utf-8".toMediaType()
    }
}
