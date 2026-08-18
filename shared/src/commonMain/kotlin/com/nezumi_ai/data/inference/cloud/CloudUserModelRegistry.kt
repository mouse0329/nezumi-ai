package com.nezumi_ai.data.inference.cloud

import com.nezumi_ai.data.inference.CloudModelConfigProvider

/** ユーザー定義クラウドモデル永続化ロジック (commonMain 版、ストア委譲)。 */
object CloudUserModelRegistry {

    /** 変更通知。app 側クラスを直接参照できないためコールバックで橋渡し (app が登録)。 */
    var onModelListChanged: (() -> Unit)? = null

    private fun notifyChanged() { runCatching { onModelListChanged?.invoke() } }

    private const val KEY = "models"
    private const val KEY_PREFIX_OVERRIDE = "override."
    private const val KEY_PREFIX_MODEL_API_KEY = "modelkey."
    private const val KEY_PREFIX_MODEL_BASE_URL = "modelurl."

    fun list(plain: PlatformSecureStore): List<String> {
        val raw = plain.get(KEY) ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    fun add(plain: PlatformSecureStore, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        val current = list(plain).toMutableList()
        if (current.contains(trimmed)) return
        current += trimmed
        save(plain, current)
        notifyChanged()
    }

    fun remove(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String) {
        val trimmed = modelId.trim()
        val current = list(plain).toMutableList()
        if (!current.remove(trimmed)) return
        save(plain, current)
        clearOverride(plain, secure, trimmed)
        notifyChanged()
    }

    fun hasOverride(plain: PlatformSecureStore, modelId: String): Boolean =
        plain.contains(KEY_PREFIX_OVERRIDE + modelId)

    fun getOverrideApiKey(secure: PlatformSecureStore, modelId: String): String =
        secure.get(KEY_PREFIX_MODEL_API_KEY + modelId)?.trim().orEmpty()

    fun getOverrideBaseUrl(secure: PlatformSecureStore, modelId: String): String =
        secure.get(KEY_PREFIX_MODEL_BASE_URL + modelId)?.trim().orEmpty()

    fun saveOverride(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String, apiKey: String, baseUrl: String) {
        val nk = apiKey.trim()
        val nu = baseUrl.trim().trimEnd('/')
        if (nk.isEmpty()) secure.remove(KEY_PREFIX_MODEL_API_KEY + modelId) else secure.put(KEY_PREFIX_MODEL_API_KEY + modelId, nk)
        if (nu.isEmpty()) secure.remove(KEY_PREFIX_MODEL_BASE_URL + modelId) else secure.put(KEY_PREFIX_MODEL_BASE_URL + modelId, nu)
        if (nk.isNotEmpty() || nu.isNotEmpty()) plain.put(KEY_PREFIX_OVERRIDE + modelId, "true")
        else plain.remove(KEY_PREFIX_OVERRIDE + modelId)
        notifyChanged()
    }

    fun resolveApiKey(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String, provider: CloudApiKeyStore.Provider): String {
        val own = getOverrideApiKey(secure, modelId)
        return own.ifBlank { CloudApiKeyStore.getApiKey(secure, provider) }
    }

    fun resolveBaseUrl(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String, provider: CloudApiKeyStore.Provider): String {
        val own = getOverrideBaseUrl(secure, modelId)
        return own.ifBlank { CloudApiKeyStore.getBaseUrl(secure, provider) }
    }

    fun isConfigured(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String): Boolean {
        val parsed = CloudModelId.parse(modelId) ?: return false
        val baseUrl = resolveBaseUrl(plain, secure, modelId, parsed.provider)
        val hasUrl = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        if (!hasUrl) return false
        if (!parsed.provider.requiresApiKey) return true
        return resolveApiKey(plain, secure, modelId, parsed.provider).isNotBlank()
    }

    private fun clearOverride(plain: PlatformSecureStore, secure: PlatformSecureStore, modelId: String) {
        secure.remove(KEY_PREFIX_MODEL_API_KEY + modelId)
        secure.remove(KEY_PREFIX_MODEL_BASE_URL + modelId)
        plain.remove(KEY_PREFIX_OVERRIDE + modelId)
    }

    private fun save(plain: PlatformSecureStore, list: List<String>) {
        plain.put(KEY, list.joinToString("\n"))
    }
}

/** [CloudModelConfigProvider] 共通実装。 */
class DefaultCloudModelConfigProvider(
    private val plain: PlatformSecureStore,
    private val secure: PlatformSecureStore
) : CloudModelConfigProvider {
    override fun hasOverride(modelId: String): Boolean = CloudUserModelRegistry.hasOverride(plain, modelId)
    override fun resolveApiKey(modelId: String, providerId: String): String {
        val p = CloudApiKeyStore.Provider.fromId(providerId) ?: return ""
        return CloudUserModelRegistry.resolveApiKey(plain, secure, modelId, p)
    }
    override fun resolveBaseUrl(modelId: String, providerId: String): String {
        val p = CloudApiKeyStore.Provider.fromId(providerId) ?: return ""
        return CloudUserModelRegistry.resolveBaseUrl(plain, secure, modelId, p)
    }
    override fun isConfigured(modelId: String): Boolean = CloudUserModelRegistry.isConfigured(plain, secure, modelId)
}
