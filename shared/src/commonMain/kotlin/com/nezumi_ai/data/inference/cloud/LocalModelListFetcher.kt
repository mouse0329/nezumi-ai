package com.nezumi_ai.data.inference.cloud

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** ローカル/クラウドモデル一覧取得 (commonMain / Ktor)。 */
object LocalModelListFetcher {

    private const val TIMEOUT_MS = 5000L
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun fetch(provider: CloudApiKeyStore.Provider, baseUrl: String, apiKey: String = ""): List<String>? =
        withContext(Dispatchers.IO) {
            val root = apiRoot(baseUrl)
            if (root.isEmpty()) return@withContext null
            runCatching {
                withTimeout(TIMEOUT_MS) {
                    val names = when (provider) {
                        CloudApiKeyStore.Provider.OLLAMA_LOCAL,
                        CloudApiKeyStore.Provider.OLLAMA_REMOTE ->
                            fetchOllamaTags(root, apiKey).ifEmpty { fetchOpenAiModels(root, apiKey) }
                        CloudApiKeyStore.Provider.CLAUDE -> fetchClaudeModels(root, apiKey)
                        CloudApiKeyStore.Provider.GEMINI -> fetchGeminiModels(root, apiKey)
                        CloudApiKeyStore.Provider.LM_STUDIO,
                        CloudApiKeyStore.Provider.OPENAI -> fetchOpenAiModels(root, apiKey)
                    }
                    names.filter { isChatUsable(provider, it) }.distinct().sorted()
                }
            }.getOrNull()
        }

    /** Ollama `/api/show` の thinking.values。失敗時は null (ヒューリスティックに任せる)。 */
    suspend fun fetchOllamaThinking(
        baseUrl: String,
        model: String,
        apiKey: String = ""
    ): CloudThinkingEffort.OllamaThinkingSpec? = withContext(Dispatchers.IO) {
        val root = apiRoot(baseUrl)
        val bare = CloudModelId.bareModelName(model)
        if (root.isEmpty() || bare.isBlank()) return@withContext null
        runCatching {
            withTimeout(TIMEOUT_MS) {
                val response = CloudHttpClient.instance.post("$root/api/show") {
                    contentType(ContentType.Application.Json)
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    setBody(buildJsonObject { put("model", bare) }.toString())
                }
                if (!response.status.isSuccess()) return@withTimeout null
                val obj = json.parseToJsonElement(response.bodyAsText()).jsonObject
                parseOllamaMedia(obj)?.let { CloudMediaCapability.put(root, bare, it) }
                val spec = parseOllamaThinking(obj)
                    ?: CloudThinkingEffort.OllamaThinkingSpec(emptyList(), null)
                OllamaThinkingCache.put(root, bare, spec)
                spec
            }
        }.getOrNull()
    }

    fun apiRoot(baseUrl: String): String {
        var url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) return ""
        if (url.endsWith("/v1beta")) url = url.removeSuffix("/v1beta")
        if (url.endsWith("/v1")) url = url.removeSuffix("/v1")
        return url
    }

    /** チャットに使えない埋め込み・音声・画像専用などを落とす。 */
    fun isChatUsable(provider: CloudApiKeyStore.Provider, id: String): Boolean {
        val name = id.lowercase()
        if (name.isBlank()) return false
        val banned = listOf(
            "embed", "embedding", "tts", "whisper", "dall-e", "moderation",
            "realtime", "transcribe", "imagen", "veo-", "aqa", "text-embedding"
        )
        if (banned.any { it in name }) return false
        if (name.startsWith("ft:") || ":ft-" in name) return false
        return when (provider) {
            CloudApiKeyStore.Provider.CLAUDE -> name.startsWith("claude") || "fable" in name || "mythos" in name
            CloudApiKeyStore.Provider.GEMINI -> name.startsWith("gemini")
            CloudApiKeyStore.Provider.OPENAI ->
                name.startsWith("gpt-") || name.startsWith("chatgpt") ||
                    name.startsWith("o1") || name.startsWith("o3") || name.startsWith("o4")
            else -> true
        }
    }

    internal fun parseOllamaThinking(root: JsonObject): CloudThinkingEffort.OllamaThinkingSpec? {
        val thinking = root["thinking"] as? JsonObject ?: return null
        val valuesEl = thinking["values"] as? JsonArray ?: return CloudThinkingEffort.OllamaThinkingSpec(emptyList(), null)
        val values = valuesEl.mapNotNull { el ->
            val primitive = el as? JsonPrimitive ?: return@mapNotNull null
            val bool = primitive.booleanOrNull
            if (bool != null) bool.toString() else primitive.content.trim().lowercase().takeIf { it.isNotEmpty() }
        }
        val defaultValue = thinking["default"]?.let { el ->
            val primitive = el as? JsonPrimitive ?: return@let null
            primitive.booleanOrNull?.toString() ?: primitive.content.trim().lowercase().takeIf { it.isNotEmpty() }
        }
        return CloudThinkingEffort.OllamaThinkingSpec(values, defaultValue)
    }

    /** Ollama `/api/show` の capabilities。vision / audio が明示されたときだけ返す。 */
    internal fun parseOllamaMedia(root: JsonObject): CloudMediaCapability.State? {
        val caps = root["capabilities"] as? JsonArray ?: return null
        val names = caps.mapNotNull { el ->
            (el as? JsonPrimitive)?.content?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        }
        if (names.isEmpty()) return null
        return CloudMediaCapability.State(
            image = names.any { it == "vision" || it == "image" },
            audio = names.any { it == "audio" }
        )
    }

    private suspend fun fetchOpenAiModels(baseUrl: String, apiKey: String): List<String> {
        val response = CloudHttpClient.instance.get("$baseUrl/v1/models") {
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) error("models http ${response.status.value}")
        val data = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return data.mapNotNull { runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { id -> id.isNotBlank() } }
    }

    private suspend fun fetchOllamaTags(baseUrl: String, apiKey: String): List<String> {
        val response = CloudHttpClient.instance.get("$baseUrl/api/tags") {
            if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
        }
        if (!response.status.isSuccess()) error("models http ${response.status.value}")
        val models = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject["models"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return models.mapNotNull { runCatching { it.jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { n -> n.isNotBlank() } }
    }

    private suspend fun fetchClaudeModels(baseUrl: String, apiKey: String): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val ids = mutableListOf<String>()
        var after: String? = null
        repeat(5) {
            val url = buildString {
                append("$baseUrl/v1/models?limit=100")
                if (!after.isNullOrBlank()) append("&after_id=").append(after)
            }
            val response = CloudHttpClient.instance.get(url) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
            if (!response.status.isSuccess()) return ids
            val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull() ?: return ids
            val data = root["data"]?.jsonArray ?: return ids
            data.mapNotNullTo(ids) { runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() } }
            val hasMore = runCatching { root["has_more"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true
            after = data.lastOrNull()?.let { runCatching { it.jsonObject["id"]?.jsonPrimitive?.content }.getOrNull() }
            if (!hasMore || after.isNullOrBlank()) return ids
        }
        return ids
    }

    private suspend fun fetchGeminiModels(baseUrl: String, apiKey: String): List<String> {
        if (apiKey.isBlank()) return emptyList()
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        repeat(10) {
            val url = buildString {
                append("$baseUrl/v1beta/models?key=$apiKey&pageSize=100")
                if (!pageToken.isNullOrBlank()) append("&pageToken=").append(pageToken)
            }
            val response = CloudHttpClient.instance.get(url) {
                header(HttpHeaders.Accept, "application/json")
            }
            if (!response.status.isSuccess()) error("models http ${response.status.value}")
            val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
                ?: return ids
            val models = root["models"]?.jsonArray ?: return ids
            models.mapNotNullTo(ids) { el ->
                val obj = el.jsonObject
                val methods = obj["supportedGenerationMethods"]?.jsonArray?.mapNotNull {
                    runCatching { it.jsonPrimitive.content }.getOrNull()
                }.orEmpty()
                if (methods.isNotEmpty() && methods.none { it.equals("generateContent", ignoreCase = true) }) return@mapNotNullTo null
                val raw = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNullTo null
                raw.removePrefix("models/").takeIf { it.isNotBlank() }
            }
            pageToken = runCatching { root["nextPageToken"]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }
            if (pageToken == null) return ids
        }
        return ids
    }
}
