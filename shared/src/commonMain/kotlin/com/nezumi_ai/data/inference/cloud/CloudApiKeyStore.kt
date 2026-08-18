package com.nezumi_ai.data.inference.cloud

/**
 * クラウドプロバイダの API キー / Base URL のドメインロジック (commonMain)。
 * 永続化実体は [PlatformSecureStore] に委譲。Android Context 版 API は
 * androidMain の `*ForContext` 拡張関数 (名前衝突回避のため別名) が担う。
 */
object CloudApiKeyStore {

    private const val TAG = "CloudApiKeyStore"
    const val PREFS_FILE_NAME = "cloud_api_credentials"
    private const val KEY_PREFIX_API_KEY = "key."
    private const val KEY_PREFIX_BASE_URL = "url."

    enum class Provider(val id: String, val requiresApiKey: Boolean, val defaultBaseUrl: String?) {
        CLAUDE("claude", requiresApiKey = true, defaultBaseUrl = "https://api.anthropic.com"),
        GEMINI("gemini", requiresApiKey = true, defaultBaseUrl = "https://generativelanguage.googleapis.com"),
        OPENAI("openai", requiresApiKey = true, defaultBaseUrl = "https://api.openai.com"),
        OLLAMA_LOCAL("ollama-local", requiresApiKey = false, defaultBaseUrl = "http://127.0.0.1:11434"),
        OLLAMA_REMOTE("ollama-remote", requiresApiKey = true, defaultBaseUrl = "https://ollama.com"),
        LM_STUDIO("lmstudio", requiresApiKey = false, defaultBaseUrl = "http://127.0.0.1:1234");

        companion object {
            fun fromId(id: String): Provider? = values().firstOrNull { it.id == id }
        }
    }

    fun setApiKey(store: PlatformSecureStore, provider: Provider, apiKey: String): Boolean {
        if (!store.isAvailable) return false
        val normalized = apiKey.trim()
        val key = KEY_PREFIX_API_KEY + provider.id
        val ok = if (normalized.isEmpty()) store.remove(key) else store.put(key, normalized)
        if (!ok) CloudLog.e(TAG, "setApiKey failed provider=${provider.id}")
        return ok
    }

    fun getApiKey(store: PlatformSecureStore, provider: Provider): String {
        if (!store.isAvailable) return ""
        return store.get(KEY_PREFIX_API_KEY + provider.id) ?: ""
    }

    fun hasApiKey(store: PlatformSecureStore, provider: Provider): Boolean =
        getApiKey(store, provider).isNotBlank()

    fun setBaseUrl(store: PlatformSecureStore, provider: Provider, baseUrl: String): Boolean {
        if (!store.isAvailable) return false
        val normalized = baseUrl.trim().trimEnd('/')
        val key = KEY_PREFIX_BASE_URL + provider.id
        val ok = if (normalized.isEmpty()) store.remove(key) else store.put(key, normalized)
        if (!ok) CloudLog.e(TAG, "setBaseUrl failed provider=${provider.id}")
        return ok
    }

    fun getBaseUrl(store: PlatformSecureStore, provider: Provider): String {
        val saved = if (store.isAvailable) store.get(KEY_PREFIX_BASE_URL + provider.id) else null
        val resolved = saved?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        return resolved?.trimEnd('/') ?: ""
    }

    fun isConfigured(store: PlatformSecureStore, provider: Provider): Boolean {
        val baseUrl = getBaseUrl(store, provider)
        val hasUrl = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        if (!hasUrl) return false
        return if (provider.requiresApiKey) hasApiKey(store, provider) else true
    }

    fun clear(store: PlatformSecureStore, provider: Provider) {
        if (!store.isAvailable) return
        store.remove(KEY_PREFIX_API_KEY + provider.id)
        store.remove(KEY_PREFIX_BASE_URL + provider.id)
    }
}
