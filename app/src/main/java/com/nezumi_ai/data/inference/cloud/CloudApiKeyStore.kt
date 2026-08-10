package com.nezumi_ai.data.inference.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * クラウド推論プロバイダ (Claude / Gemini / OpenAI / Ollama Local / Ollama Cloud /
 * LM Studio) の API キー・Base URL を Android Keystore 経由で暗号化保存する。
 *
 * HfAuthManager が使う平文 SharedPreferences (`hf_auth`) とは別ファイル
 * (`cloud_api_credentials`) に隔離する。マスターキー ([MasterKey]) は
 * `AES256_GCM` スキームで生成し、値/キー共に AES-256 で暗号化する。
 *
 * ## 保存キー
 * - `key.<providerId>` : API キー本体（Claude / Gemini / OpenAI / Ollama Cloud）
 *                        Ollama Local / LM Studio は任意（未設定 = 認証なし）
 * - `url.<providerId>` : Base URL（Ollama Cloud / LM Studio / (任意で) Ollama Local）
 *
 * ## スレッド安全性
 * EncryptedSharedPreferences 自体が内部で `synchronized` ブロックを持つため、
 * このクラスは追加のロックを取らない。
 */
object CloudApiKeyStore {

    private const val TAG = "CloudApiKeyStore"
    private const val PREFS_FILE_NAME = "cloud_api_credentials"

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

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * 暗号化 SharedPreferences を取得する。初期化失敗時はフォールバックとして
     * 通常の SharedPreferences を返さず、null を返す。フォールバックすると
     * 「暗号化保存を約束したのに実態が平文」になる事故を避けるため。
     */
    private fun prefs(context: Context): SharedPreferences? {
        cachedPrefs?.let { return it }
        return synchronized(this) {
            cachedPrefs?.let { return@synchronized it }
            val created = try {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context.applicationContext,
                    PREFS_FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences", e)
                null
            }
            cachedPrefs = created
            created
        }
    }

    // ─── API キー ───────────────────────────────────────────────────

    /**
     * API キーを保存する。空文字を渡した場合はエントリを削除する。
     * @return 保存に成功したか。暗号化ストア初期化失敗時は false。
     */
    fun setApiKey(context: Context, provider: Provider, apiKey: String): Boolean {
        val store = prefs(context) ?: return false
        val normalized = apiKey.trim()
        return runCatching {
            store.edit().apply {
                val prefKey = KEY_PREFIX_API_KEY + provider.id
                if (normalized.isEmpty()) remove(prefKey) else putString(prefKey, normalized)
            }.commit()
        }.onFailure { Log.e(TAG, "setApiKey failed provider=${provider.id}", it) }
            .getOrDefault(false)
    }

    /** API キーを取得する。未設定・取得失敗時は空文字を返す。 */
    fun getApiKey(context: Context, provider: Provider): String {
        val store = prefs(context) ?: return ""
        return try {
            store.getString(KEY_PREFIX_API_KEY + provider.id, "") ?: ""
        } catch (e: Throwable) {
            Log.e(TAG, "getApiKey failed provider=${provider.id}", e)
            ""
        }
    }

    fun hasApiKey(context: Context, provider: Provider): Boolean {
        return getApiKey(context, provider).isNotBlank()
    }

    // ─── Base URL ───────────────────────────────────────────────────

    /**
     * Base URL を保存する。空文字を渡した場合はエントリを削除する
     * (＝デフォルト値へフォールバック)。
     */
    fun setBaseUrl(context: Context, provider: Provider, baseUrl: String): Boolean {
        val store = prefs(context) ?: return false
        val normalized = baseUrl.trim().trimEnd('/')
        return runCatching {
            store.edit().apply {
                val prefKey = KEY_PREFIX_BASE_URL + provider.id
                if (normalized.isEmpty()) remove(prefKey) else putString(prefKey, normalized)
            }.commit()
        }.onFailure { Log.e(TAG, "setBaseUrl failed provider=${provider.id}", it) }
            .getOrDefault(false)
    }

    /**
     * Base URL を取得する。ユーザーが明示的に設定していれば保存済みの値、
     * そうでなければ [Provider.defaultBaseUrl] を返す。
     * デフォルトが無いプロバイダで未設定の場合は空文字を返す。
     */
    fun getBaseUrl(context: Context, provider: Provider): String {
        val store = prefs(context)
        val saved = try {
            store?.getString(KEY_PREFIX_BASE_URL + provider.id, null)
        } catch (e: Throwable) {
            Log.e(TAG, "getBaseUrl failed provider=${provider.id}", e)
            null
        }
        val resolved = saved?.takeIf { it.isNotBlank() } ?: provider.defaultBaseUrl
        return resolved?.trimEnd('/') ?: ""
    }

    /**
     * プロバイダが「利用可能に構成済み」であるかを判定する。
     * - API キー必須プロバイダ: Base URL に加えて API キーが設定されていること
     * - Ollama Local / LM Studio: Base URL が有効な http(s) URL であること
     */
    fun isConfigured(context: Context, provider: Provider): Boolean {
        val baseUrl = getBaseUrl(context, provider)
        val hasUrl = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        if (!hasUrl) return false
        return if (provider.requiresApiKey) hasApiKey(context, provider) else true
    }

    /**
     * 保存済みの設定を全て消去する。デバッグやサインアウト目的で使う。
     */
    fun clear(context: Context, provider: Provider) {
        val store = prefs(context) ?: return
        runCatching {
            store.edit()
                .remove(KEY_PREFIX_API_KEY + provider.id)
                .remove(KEY_PREFIX_BASE_URL + provider.id)
                .commit()
        }.onFailure { Log.e(TAG, "clear failed provider=${provider.id}", it) }
    }
}
