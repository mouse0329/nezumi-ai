package com.nezumi_ai.data.inference.cloud

import com.nezumi_ai.data.inference.CloudModelConfigProvider

/**
 * ユーザー定義クラウドモデル (プロバイダ + モデル名 + 個別設定) の永続化ロジック
 * (commonMain 版)。ストア実体は [PlatformSecureStore] に委譲する。
 *
 * 保存キー規則は app 側の旧実装と完全互換:
 * - 平文ストア: キー `"models"` = 改行区切り modelId 一覧、`"override.{modelId}"` = フラグ
 * - 暗号化ストア: `"modelkey.{modelId}"` / `"modelurl.{modelId}"`
 */
object CloudUserModelRegistry {

    /**
     * モデル一覧・個別設定が変更されたときのコールバック。
     * shared は app 側クラス (PresetModelCatalog 等) を直接参照できない (逆方向依存) ため、
     * キャッシュ無効化の実体は app 側がここに登録する。未登録なら何もしない。
     */
    var onModelListChanged: (() -> Unit)? = null

    private fun notifyChanged() {
        runCatching { onModelListChanged?.invoke() }
    }

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

    fun hasOverride(plain: PlatformSecureStore, modelId: String): Boolean {
        return plain.contains(KEY_PREFIX_OVERRIDE + modelId)
    }

    fun getOverrideApiKey(secure: PlatformSecureStore, modelId: String): String {
        return secure.get(KEY_PREFIX_MODEL_API_KEY + modelId)?.trim().orEmpty()
    }

    fun getOverrideBaseUrl(secure: PlatformSecureStore, modelId: String): String {
        return secure.get(KEY_PREFIX_MODEL_BASE_URL + modelId)?.trim().orEmpty()
    }

    fun saveOverride(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        modelId: String,
        apiKey: String,
        baseUrl: String
    ) {
        val normalizedKey = apiKey.trim()
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        if (normalizedKey.isEmpty()) secure.remove(KEY_PREFIX_MODEL_API_KEY + modelId)
        else secure.put(KEY_PREFIX_MODEL_API_KEY + modelId, normalizedKey)
        if (normalizedUrl.isEmpty()) secure.remove(KEY_PREFIX_MODEL_BASE_URL + modelId)
        else secure.put(KEY_PREFIX_MODEL_BASE_URL + modelId, normalizedUrl)

        if (normalizedKey.isNotEmpty() || normalizedUrl.isNotEmpty()) {
            plain.put(KEY_PREFIX_OVERRIDE + modelId, "true")
        } else {
            plain.remove(KEY_PREFIX_OVERRIDE + modelId)
        }
        notifyChanged()
    }

    fun resolveApiKey(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        modelId: String,
        provider: CloudApiKeyStore.Provider
    ): String {
        val own = getOverrideApiKey(secure, modelId)
        return own.ifBlank { CloudApiKeyStore.getApiKey(secure, provider) }
    }

    fun resolveBaseUrl(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        modelId: String,
        provider: CloudApiKeyStore.Provider
    ): String {
        val own = getOverrideBaseUrl(secure, modelId)
        return own.ifBlank { CloudApiKeyStore.getBaseUrl(secure, provider) }
    }

    fun isConfigured(
        plain: PlatformSecureStore,
        secure: PlatformSecureStore,
        modelId: String
    ): Boolean {
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

/**
 * [CloudModelConfigProvider] の共通実装。2 つのストアを受けて個別設定を解決する。
 * エンジン共通基底はこのインターフェース越しに個別設定へアクセスする。
 */
class DefaultCloudModelConfigProvider(
    private val plain: PlatformSecureStore,
    private val secure: PlatformSecureStore
) : CloudModelConfigProvider {

    override fun hasOverride(modelId: String): Boolean =
        CloudUserModelRegistry.hasOverride(plain, modelId)

    override fun resolveApiKey(modelId: String, providerId: String): String {
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return ""
        return CloudUserModelRegistry.resolveApiKey(plain, secure, modelId, provider)
    }

    override fun resolveBaseUrl(modelId: String, providerId: String): String {
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return ""
        return CloudUserModelRegistry.resolveBaseUrl(plain, secure, modelId, provider)
    }

    override fun isConfigured(modelId: String): Boolean =
        CloudUserModelRegistry.isConfigured(plain, secure, modelId)
}
