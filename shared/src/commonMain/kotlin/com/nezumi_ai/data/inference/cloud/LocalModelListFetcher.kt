package com.nezumi_ai.data.inference.cloud

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * ローカルサーバー系プロバイダ (LM Studio / Ollama) と Ollama Cloud のモデル一覧を
 * 取得するユーティリティ (commonMain / Ktor 版)。org.json から kotlinx.serialization へ置き換え済み。
 */
object LocalModelListFetcher {

    private const val TIMEOUT_MS = 5000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
                        fetchOllamaTags(url, apiKey).ifEmpty { fetchOpenAiModels(url, apiKey) }

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

    private suspend fun fetchOpenAiModels(baseUrl: String, apiKey: String): List<String> {
        val response = CloudHttpClient.instance.get("$baseUrl/v1/models") {
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) return emptyList()
        val body = response.bodyAsText()
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return data.mapNotNull { el ->
            runCatching { el.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun fetchOllamaTags(baseUrl: String, apiKey: String): List<String> {
        val response = CloudHttpClient.instance.get("$baseUrl/api/tags") {
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) return emptyList()
        val body = response.bodyAsText()
        val models = runCatching { json.parseToJsonElement(body).jsonObject["models"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return models.mapNotNull { el ->
            runCatching { el.jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
