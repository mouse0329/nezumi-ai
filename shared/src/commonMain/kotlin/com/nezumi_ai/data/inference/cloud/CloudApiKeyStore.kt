package com.nezumi_ai.data.inference.cloud

/**
 * クラウド推論プロバイダ (Claude / Gemini / OpenAI / Ollama Local / Ollama Cloud /
 * LM Studio) の API キー・Base URL をセキュアストアに保存するドメインロジック。
 *
 * 永続化の実体は [PlatformSecureStore] に委譲され、このオブジェクトは
 * キー命名規則とフォールバック規則だけを担当する (KMP 化で commonMain 移設)。
 *
 * ## Android での保存先
 * HfAuthManager が使う平文 SharedPreferences (`hf_auth`) とは別ファイル
 * (`cloud_api_credentials`) の EncryptedSharedPreferences に隔離する。
 * マスターキーは `AES256_GCM` スキームで生成し、値/キー共に AES-256 で暗号化する。
 *
 * ## 保存キー
 * - `key.<providerId>` : API キー本体（Claude / Gemini / OpenAI / Ollama Cloud）
 *                        Ollama Local / LM Studio は任意（未設定 = 認証なし）
 * - `url.<providerId>` : Base URL（Ollama Cloud / LM Studio / (任意で) Ollama Local）
 *
 * ## Android の Context ベース API
 * 既存の呼び出し元 (`CloudApiKeyStore.getApiKey(context, provider)` 等) は
 * androidMain の拡張関数 (CloudApiKeyStoreAndroid.kt) がそのまま引き受ける。
 */
object CloudApiKeyStore {

    private const val TAG = "CloudApiKeyStore"

    /** 暗号化ストアのファイル名。Android 実装と共有するため public const とする。 */
    const val PREFS_FILE_NAME = "cloud_api_credentials"

    private const val KEY_PREFIX_API_KEY = "key."
    private const val KEY_PREFIX_BASE_URL = "url."

    /** 各プロバイダの識別子。CloudModelId のプロバイダ部と一致させる。 */
    enum class Provider(val id: String, val requiresApiKey: Boolean, val defaultBaseUrl: String?) {
        CLAUDE("claude", requiresApiKey = true, defaultBaseUrl = "https://api.anthropic.com"),
        GEMINI("gemini", requiresApiKey = true, defaultBaseUrl = "https://generativelanguage.googleapis.com"),
        OPENAI("openai", requiresApiKey = true, defaultBaseUrl = "https://api.openai.com"),
        OLLAMA_LOCAL("ollama-local", requiresApiKey = false, defaultBaseUrl = "http://127.0.0.1:11434"),
        // Ollama Cloud (ollama.com のホスト側 API)。実態はリモート接続ではなくクラウドサービスで、
        // 認証は ollama.com/settings/keys で発行した API キー (Bearer) が必須。
        // プロバイダ ID は既存データとの互換のため旧称 "ollama-remote" を維持する。
        OLLAMA_REMOTE("ollama-remote", requiresApiKey = true, defaultBaseUrl = "https://ollama.com"),
        LM_STUDIO("lmstudio", requiresApiKey = false, defaultBaseUrl = "http://127.0.0.1:1234");

        companion object {
            fun fromId(id: String): Provider? = values().firstOrNull { it.id == id }
        }
    }

    // ─── API キー ───────────────────────────────────────────────────

    /**
     * API キーを保存する。空文字を渡した場合はエントリを削除する。
     * @return 保存に成功したか。暗号化ストア初期化失敗時は false。
     */
    fun setApiKey(store: PlatformSecureStore, provider: Provider, apiKey: String): Boolean {
        if (!store.isAvailable) return false
        val normalized = apiKey.trim()
        val prefKey = KEY_PREFIX_API_KEY + provider.id
        val ok = if (normalized.isEmpty()) store.remove(prefKey) else store.put(prefKey, normalized)
        if (!ok) CloudLog.e(TAG, "setApiKey failed provider=${provider.id}")
        return ok
    }

    /** API キーを取得する。未設定・取得失敗時は空文字を返す。 */
    fun getApiKey(store: PlatformSecureStore, provider: Provider): String {
        if (!store.isAvailable) return ""
        return store.get(KEY_PREFIX_API_KEY + provider.id) ?: ""
    }

    fun hasApiKey(store: PlatformSecureStore, provider: Provider): Boolean {
        return getApiKey(store, provider).isNotBlank()
    }

    // ─── Base URL ───────────────────────────────────────────────────

    /**
     * Base URL を保存する。空文字を渡した場合はエントリを削除する
     * (＝デフォルト値へフォールバック)。
     */
    fun setBaseUrl(store: PlatformSecureStore, provider: Provider, baseUrl: String): Boolean {
        if (!store.isAvailable) return false
        val normalized = baseUrl.trim().trimEnd('/')
        val prefKey = KEY_PREFIX_BASE_URL + provider.id
        val ok = if (normalized.isEmpty()) store.remove(prefKey) else store.put(prefKey, normalized)
        if (!ok) CloudLog.e(TAG, "setBaseUrl failed provider=${provider.id}")
        return ok
    }

    /**
     * Base URL を取得する。ユーザーが明示的に設定していれば保存済みの値、
     * そうでなければ [Provider.defaultBaseUrl] を返す。
     * デフォルトが無いプロバイダで未設定の場合は空文字を返す。
     */
    fun getBaseUrl(store: PlatformSecureStore, provider: Provider): String {
        val saved = if (store.isAvailable) store.get(KEY_PREFIX_BASE_URL + provider.id) else null
        val resolved = saved?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        return resolved?.trimEnd('/') ?: ""
    }

    /**
     * プロバイダが「利用可能に構成済み」であるかを判定する。
     * - API キー必須プロバイダ: Base URL に加えて API キーが設定されていること
     * - Ollama Local / LM Studio: Base URL が有効な http(s) URL であること
     */
    fun isConfigured(store: PlatformSecureStore, provider: Provider): Boolean {
        val baseUrl = getBaseUrl(store, provider)
        val hasUrl = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        if (!hasUrl) return false
        return if (provider.requiresApiKey) hasApiKey(store, provider) else true
    }

    /**
     * 保存済みの設定を全て消去する。デバッグやサインアウト目的で使う。
     */
    fun clear(store: PlatformSecureStore, provider: Provider) {
        if (!store.isAvailable) return
        store.remove(KEY_PREFIX_API_KEY + provider.id)
        store.remove(KEY_PREFIX_BASE_URL + provider.id)
    }
}
