package com.nezumi_ai.data.inference.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * ローカルサーバー系プロバイダ (LM Studio / Ollama) と Ollama Cloud のモデル一覧を
 * `/v1/models` (LM Studio / OpenAI 互換) や `/api/tags` (Ollama) から取得するユーティリティ。
 *
 * 追加モーダルで「モデル選択ドロップダウン」を実現するために使う。
 * 取得はバックグラウンドスレッドで行い、タイムアウトを設ける
 * (ローカルサーバーが落ちているときに UI を固まらせないため)。
 */
object LocalModelListFetcher {

    private const val TIMEOUT_MS = 5000L

    /**
     * [provider] のモデル名一覧を返す。接続失敗・非対応プロバイダの場合は空リスト。
     *
     * - LM Studio (OpenAI 互換): `GET {baseUrl}/v1/models`
     * - Ollama Local / Ollama Cloud (native): `GET {baseUrl}/api/tags` (`models[].name`)
     *   ※ Ollama Cloud は Bearer 認証が必須なので apiKey を付けて呼ぶこと。
     */
    suspend fun fetch(
        provider: CloudApiKeyStore.Provider,
        baseUrl: String,
        apiKey: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext emptyList()

        runCatching {
            withTimeout(TIMEOUT_MS) {
                when (provider) {
                    CloudApiKeyStore.Provider.OLLAMA_LOCAL,
                    CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
                        fetchOllamaTags(url, apiKey)
                            .ifEmpty { fetchOpenAiModels(url, apiKey) }

                    CloudApiKeyStore.Provider.LM_STUDIO,
                    CloudApiKeyStore.Provider.OPENAI,
                    CloudApiKeyStore.Provider.CLAUDE,
                    CloudApiKeyStore.Provider.GEMINI ->
                        fetchOpenAiModels(url, apiKey)
                }
            }
        }.getOrDefault(emptyList())
            .distinct()
            .sorted()
    }

    /** OpenAI 互換 `GET /v1/models` → `data[].id` を抽出する。 */
    private fun fetchOpenAiModels(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/v1/models")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .build()
        CloudHttpClient.instance.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
            return buildList {
                for (i in 0 until data.length()) {
                    val id = data.optJSONObject(i)?.optString("id").orEmpty()
                    if (id.isNotBlank()) add(id)
                }
            }
        }
    }

    /** Ollama ネイティブ `GET /api/tags` → `models[].name` を抽出する。 */
    private fun fetchOllamaTags(baseUrl: String, apiKey: String): List<String> {
        val request = Request.Builder()
            .url("$baseUrl/api/tags")
            .apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }
            .build()
        CloudHttpClient.instance.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val models = JSONObject(body).optJSONArray("models") ?: JSONArray()
            return buildList {
                for (i in 0 until models.length()) {
                    val name = models.optJSONObject(i)?.optString("name").orEmpty()
                    if (name.isNotBlank()) add(name)
                }
            }
        }
    }
}
