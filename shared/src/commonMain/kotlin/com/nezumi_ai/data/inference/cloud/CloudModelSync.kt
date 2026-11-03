package com.nezumi_ai.data.inference.cloud

/**
 * クラウド / ローカルサーバーのモデル一覧をレジストリへ同期する。
 *
 * - Claude / Gemini / OpenAI / Ollama クラウドは API キーをプロバイダにつき一つだけ使う。
 * - Ollama ローカルと LM Studio は接続先を複数登録できる。
 * - 取得に成功した一覧で、その接続先のモデルを置き換える (常に同期)。
 * - 取得に失敗したときは前回の一覧を残す。
 */
object CloudModelSync {

    enum class Availability { OK, NOT_CONFIGURED, NOT_IN_LIST }

    private const val ENDPOINT_PREFIX = "endpoints."
    private const val SYNC_PREFIX = "synced|"

    fun listEndpoints(plain: PlatformSecureStore, provider: CloudApiKeyStore.Provider): List<String> {
        val raw = plain.get(ENDPOINT_PREFIX + provider.id).orEmpty()
        return raw.split("\n").map { it.trim().trimEnd('/') }.filter { it.startsWith("http://") || it.startsWith("https://") }.distinct()
    }

    fun addEndpoint(plain: PlatformSecureStore, provider: CloudApiKeyStore.Provider, baseUrl: String) {
        val url = baseUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val next = (listEndpoints(plain, provider) + url).distinct()
        plain.put(ENDPOINT_PREFIX + provider.id, next.joinToString("\n"))
    }

    fun removeEndpoint(plain: PlatformSecureStore, provider: CloudApiKeyStore.Provider, baseUrl: String) {
        val url = baseUrl.trim().trimEnd('/')
        val next = listEndpoints(plain, provider).filterNot { it == url }
        if (next.isEmpty()) plain.remove(ENDPOINT_PREFIX + provider.id)
        else plain.put(ENDPOINT_PREFIX + provider.id, next.joinToString("\n"))
    }

    fun setEndpointApiKey(secure: PlatformSecureStore, baseUrl: String, apiKey: String) {
        val key = CloudUserModelRegistry.endpointKey(baseUrl)
        val normalized = apiKey.trim()
        if (normalized.isEmpty()) secure.remove(key) else secure.put(key, normalized)
    }

    suspend fun refreshAll(plain: PlatformSecureStore, secure: PlatformSecureStore) {
        CloudApiKeyStore.Provider.values().forEach { provider ->
            if (!shouldAutoRefresh(plain, secure, provider)) return@forEach
            val primary = CloudApiKeyStore.getBaseUrl(secure, provider)
            val targets = (listOf(primary) + listEndpoints(plain, provider)).map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()
            targets.forEach { endpoint ->
                runCatching { refreshEndpoint(plain, secure, provider, endpoint, primary, warmThinking = true) }
            }
        }
    }

    /**
     * 未接続の既定 URL (localhost の Ollama など) には繋がない。
     * 切断したあとに起動のたびにモデルが戻るのを防ぐ。
     */
    private fun shouldAutoRefresh(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        provider: CloudApiKeyStore.Provider
    ): Boolean {
        val local = provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
            provider == CloudApiKeyStore.Provider.LM_STUDIO
        if (!local) return CloudApiKeyStore.hasApiKey(secure, provider)
        if (CloudApiKeyStore.hasSavedBaseUrl(secure, provider)) return true
        if (listEndpoints(plain, provider).isNotEmpty()) return true
        return CloudUserModelRegistry.list(plain).any { CloudModelId.parse(it)?.provider == provider }
    }

    /**
     * 送信・モデル切替の直前に、そのモデルの接続先だけ取り直す。
     * @return 取得できたモデル数。失敗時は -1。
     */
    suspend fun ensureFresh(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String): Int {
        val parsed = CloudModelId.parse(modelId) ?: return -1
        val primary = CloudApiKeyStore.getBaseUrl(secure, parsed.provider)
        val endpoint = CloudModelId.endpointOf(parsed.modelName) ?: primary
        if (endpoint.isBlank()) return -1
        val count = refreshEndpoint(plain, secure, parsed.provider, endpoint, primary)
        if (parsed.provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
            parsed.provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE
        ) {
            val apiKey = secure.get(CloudUserModelRegistry.endpointKey(endpoint))?.trim().orEmpty()
                .ifBlank { CloudApiKeyStore.getApiKey(secure, parsed.provider) }
            runCatching {
                LocalModelListFetcher.fetchOllamaThinking(endpoint, CloudModelId.bareModelName(parsed.modelName), apiKey)
            }
        }
        return count
    }

    /**
     * プロバイダの API キーは一つ。使えるモデルは取得結果で置き換える。
     * Ollama ローカルと LM Studio は URL を追加でき、接続先ごとに一覧を同期する。
     * @return 取得できたモデル数。失敗時は -1。
     */
    suspend fun connect(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        provider: CloudApiKeyStore.Provider,
        baseUrl: String,
        apiKey: String
    ): Int {
        val url = baseUrl.trim().trimEnd('/').ifBlank { provider.defaultBaseUrl.orEmpty() }.trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) return -1
        val local = provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL ||
            provider == CloudApiKeyStore.Provider.LM_STUDIO
        if (apiKey.isNotBlank()) {
            if (local) setEndpointApiKey(secure, url, apiKey)
            else CloudApiKeyStore.setApiKey(secure, provider, apiKey)
        } else if (!local && provider.requiresApiKey && !CloudApiKeyStore.hasApiKey(secure, provider)) {
            return -1
        }
        if (!local) {
            CloudApiKeyStore.setBaseUrl(secure, provider, url)
            return refreshEndpoint(plain, secure, provider, url, url, warmThinking = true)
        }
        if (!CloudApiKeyStore.hasSavedBaseUrl(secure, provider)) {
            CloudApiKeyStore.setBaseUrl(secure, provider, url)
        } else {
            val primary = CloudApiKeyStore.getBaseUrl(secure, provider).trimEnd('/')
            if (url != primary && url !in listEndpoints(plain, provider)) addEndpoint(plain, provider, url)
        }
        val primaryNow = CloudApiKeyStore.getBaseUrl(secure, provider)
        return refreshEndpoint(plain, secure, provider, url, primaryNow, warmThinking = true)
    }

    suspend fun refreshEndpoint(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        provider: CloudApiKeyStore.Provider,
        endpointUrl: String,
        primaryUrl: String = CloudApiKeyStore.getBaseUrl(secure, provider),
        warmThinking: Boolean = false
    ): Int {
        val endpoint = endpointUrl.trim().trimEnd('/')
        if (endpoint.isEmpty()) return -1
        val apiKey = secure.get(CloudUserModelRegistry.endpointKey(endpoint))?.trim().orEmpty()
            .ifBlank { CloudApiKeyStore.getApiKey(secure, provider) }
        val names = LocalModelListFetcher.fetch(provider, endpoint, apiKey) ?: return -1
        CloudUserModelRegistry.replaceForEndpoint(plain, secure, provider, endpoint, primaryUrl, names)
        plain.put(syncKey(provider, endpoint), names.joinToString("\n"))
        // 送信のたびに全モデルの /api/show はしない。起動時と接続時だけ温める。
        if (warmThinking &&
            (provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL || provider == CloudApiKeyStore.Provider.OLLAMA_REMOTE)
        ) {
            names.forEach { name ->
                runCatching { LocalModelListFetcher.fetchOllamaThinking(endpoint, name, apiKey) }
            }
        }
        return names.size
    }

    fun availability(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String): Availability {
        val parsed = CloudModelId.parse(modelId) ?: return Availability.NOT_CONFIGURED
        if (!CloudUserModelRegistry.isConfigured(plain, secure, modelId)) return Availability.NOT_CONFIGURED
        val primary = CloudApiKeyStore.getBaseUrl(secure, parsed.provider)
        val endpoint = CloudModelId.endpointOf(parsed.modelName) ?: primary
        val raw = plain.get(syncKey(parsed.provider, endpoint))
        if (raw == null) return Availability.OK
        val names = raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val bare = CloudModelId.bareModelName(parsed.modelName)
        return if (bare in names) Availability.OK else Availability.NOT_IN_LIST
    }

    fun disconnect(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        provider: CloudApiKeyStore.Provider,
        endpointUrl: String
    ) {
        val endpoint = endpointUrl.trim().trimEnd('/').ifBlank { CloudApiKeyStore.getBaseUrl(secure, provider) }
        val primary = CloudApiKeyStore.getBaseUrl(secure, provider)
        CloudUserModelRegistry.replaceForEndpoint(plain, secure, provider, endpoint, primary, emptyList())
        plain.remove(syncKey(provider, endpoint))
        secure.remove(CloudUserModelRegistry.endpointKey(endpoint))
        removeEndpoint(plain, provider, endpoint)
        val local = provider == CloudApiKeyStore.Provider.OLLAMA_LOCAL || provider == CloudApiKeyStore.Provider.LM_STUDIO
        if (!local || endpoint == primary.trimEnd('/')) {
            val remaining = listEndpoints(plain, provider)
            if (!local || remaining.isEmpty()) {
                CloudApiKeyStore.clear(secure, provider)
                if (!local) CloudUserModelRegistry.removeProvider(plain, secure, provider)
            } else {
                CloudApiKeyStore.setBaseUrl(secure, provider, remaining.first())
            }
        }
    }

    private fun syncKey(provider: CloudApiKeyStore.Provider, endpoint: String): String =
        SYNC_PREFIX + provider.id + "|" + endpoint.trim().trimEnd('/')
}
