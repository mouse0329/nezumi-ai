package com.nezumi_ai

import com.nezumi_ai.data.inference.cloud.CloudApiKeyStore
import com.nezumi_ai.data.inference.cloud.IosPlatformSecureStore
import com.nezumi_ai.data.inference.stripGemmaTokens

/**
 * iOS シェル (iosApp) から shared の共通ロジックを呼ぶ入口。
 *
 * Phase 7: 完成条件のうち
 * - SwiftUI から shared を利用できる
 * - API key を Keychain に保存できる
 * を進める。クラウド推論の実通信は次のステップ。
 */
object NezumiIosShell {
    private val secureStore = IosPlatformSecureStore()

    fun title(): String = "ネズミAI"

    fun sanitizePreview(text: String): String = text.stripGemmaTokens()

    /** 利用可能なクラウドプロバイダの表示名一覧。 */
    fun cloudProviderIds(): List<String> =
        CloudApiKeyStore.Provider.entries.map { it.id }

    /** 指定プロバイダが Keychain 上で設定済みか。 */
    fun isCloudProviderConfigured(providerId: String): Boolean {
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return false
        return CloudApiKeyStore.isConfigured(secureStore, provider)
    }

    /** API キーを Keychain に保存する。空文字なら削除。 */
    fun setCloudApiKey(providerId: String, apiKey: String): Boolean {
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return false
        return CloudApiKeyStore.setApiKey(secureStore, provider, apiKey)
    }

    /** 保存済み Base URL。未設定なら既定値。 */
    fun cloudBaseUrl(providerId: String): String {
        val provider = CloudApiKeyStore.Provider.fromId(providerId) ?: return ""
        return CloudApiKeyStore.getBaseUrl(secureStore, provider)
    }
}

