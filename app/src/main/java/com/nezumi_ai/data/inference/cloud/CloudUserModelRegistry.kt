package com.nezumi_ai.data.inference.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * ユーザー定義クラウドモデル (プロバイダ + モデル名 + 個別設定) を永続化するリポジトリ。
 *
 * UI 側で「クラウドモデルを追加」した結果を [com.nezumi_ai.data.preset.PresetModelCatalog] の
 * `downloadedModels()` と同じ配列に流し込むための保管庫。
 *
 * ## 保存形式
 * `SharedPreferences "cloud_user_models"`:
 * - キー `"models"` : 改行区切りの modelId (`cloud:{provider}:{modelName}`) 一覧
 * - キー `"override.{modelId}"` : `{apiKey}\n{baseUrl}` 形式のモデル個別設定
 *   (行が無い要素は「プロバイダの共通設定を使う」の意味)
 *
 * API キー値自体は [CloudApiKeyStore] と同じ EncryptedSharedPreferences
 * (`cloud_api_credentials`) にモデル単位で保存する。ここにはモデル名と
 * 「個別設定を持っているか」のフラグだけを置く (機密情報は置かない)。
 */
object CloudUserModelRegistry {

    private const val TAG = "CloudUserModelRegistry"
    private const val PREFS = "cloud_user_models"
    private const val KEY = "models"
    private const val KEY_PREFIX_OVERRIDE = "override."

    /** モデル個別設定の永続化キー (CloudApiKeyStore 側)。 */
    private const val KEY_PREFIX_MODEL_API_KEY = "modelkey."
    private const val KEY_PREFIX_MODEL_BASE_URL = "modelurl."

    fun list(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * モデルを登録する。既に存在する modelId は無視される (設定だけ更新したい場合は
     * [saveOverride] を使う)。
     */
    fun add(context: Context, modelId: String) {
        val trimmed = modelId.trim()
        if (trimmed.isEmpty()) return
        val current = list(context).toMutableList()
        if (current.contains(trimmed)) return
        current += trimmed
        save(context, current)
        com.nezumi_ai.data.preset.PresetModelCatalog.invalidateCache()
    }

    /**
     * モデル登録を削除する。個別設定もあわせて消す。
     */
    fun remove(context: Context, modelId: String) {
        val trimmed = modelId.trim()
        val current = list(context).toMutableList()
        if (!current.remove(trimmed)) return
        save(context, current)
        clearOverride(context, trimmed)
        com.nezumi_ai.data.preset.PresetModelCatalog.invalidateCache()
    }

    // ─── モデル個別設定 ────────────────────────────────────────────

    /** このモデルが「プロバイダ共通設定ではなく個別設定」を持っているか。 */
    fun hasOverride(context: Context, modelId: String): Boolean {
        return prefs(context).contains(KEY_PREFIX_OVERRIDE + modelId)
    }

    /**
     * モデル個別の API キー。未設定時は空文字 (呼び出し側でプロバイダ共通値へ
     * フォールバックする)。
     */
    fun getOverrideApiKey(context: Context, modelId: String): String {
        return encrypted(context)
            ?.getString(KEY_PREFIX_MODEL_API_KEY + modelId, null)
            ?.trim()
            .orEmpty()
    }

    /**
     * モデル個別の Base URL。未設定時は空文字。
     */
    fun getOverrideBaseUrl(context: Context, modelId: String): String {
        return encrypted(context)
            ?.getString(KEY_PREFIX_MODEL_BASE_URL + modelId, null)
            ?.trim()
            .orEmpty()
    }

    /**
     * モデル個別設定を保存する。
     * @param apiKey 空文字なら「プロバイダ共通設定を使う」を意味する。
     * @param baseUrl 空文字なら「プロバイダ共通/デフォルトを使う」を意味する。
     */
    fun saveOverride(context: Context, modelId: String, apiKey: String, baseUrl: String) {
        val normalizedKey = apiKey.trim()
        val normalizedUrl = baseUrl.trim().trimEnd('/')
        val store = encrypted(context) ?: return
        runCatching {
            store.edit().apply {
                if (normalizedKey.isEmpty()) remove(KEY_PREFIX_MODEL_API_KEY + modelId)
                else putString(KEY_PREFIX_MODEL_API_KEY + modelId, normalizedKey)

                if (normalizedUrl.isEmpty()) remove(KEY_PREFIX_MODEL_BASE_URL + modelId)
                else putString(KEY_PREFIX_MODEL_BASE_URL + modelId, normalizedUrl)
            }.commit()
        }.onFailure { Log.e(TAG, "saveOverride failed for $modelId", it) }

        // 「個別設定を持っている」フラグ自体は平文 SharedPreferences 側に立てる。
        // (キーの存在確認を EncryptedSharedPreferences に毎回問い合わせると重いため)
        if (normalizedKey.isNotEmpty() || normalizedUrl.isNotEmpty()) {
            prefs(context).edit()
                .putBoolean(KEY_PREFIX_OVERRIDE + modelId, true)
                .apply()
        } else {
            prefs(context).edit()
                .remove(KEY_PREFIX_OVERRIDE + modelId)
                .apply()
        }
        // isConfigured 結果が変わるため一覧キャッシュを破棄
        com.nezumi_ai.data.preset.PresetModelCatalog.invalidateCache()
    }

    /**
     * モデルの API キー / Base URL を解決する。個別設定があればそれを優先し、
     * なければプロバイダ共通設定 ([CloudApiKeyStore]) にフォールバックする。
     */
    fun resolveApiKey(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String {
        val own = getOverrideApiKey(context, modelId)
        return own.ifBlank { CloudApiKeyStore.getApiKey(context, provider) }
    }

    fun resolveBaseUrl(context: Context, modelId: String, provider: CloudApiKeyStore.Provider): String {
        val own = getOverrideBaseUrl(context, modelId)
        return own.ifBlank { CloudApiKeyStore.getBaseUrl(context, provider) }
    }

    /** モデルが「利用可能に構成済み」か。個別設定優先で判定する。 */
    fun isConfigured(context: Context, modelId: String): Boolean {
        val parsed = CloudModelId.parse(modelId) ?: return false
        val baseUrl = resolveBaseUrl(context, modelId, parsed.provider)
        val hasUrl = baseUrl.startsWith("http://") || baseUrl.startsWith("https://")
        if (!hasUrl) return false
        if (!parsed.provider.requiresApiKey) return true
        return resolveApiKey(context, modelId, parsed.provider).isNotBlank()
    }

    private fun clearOverride(context: Context, modelId: String) {
        runCatching {
            encrypted(context)?.edit()
                ?.remove(KEY_PREFIX_MODEL_API_KEY + modelId)
                ?.remove(KEY_PREFIX_MODEL_BASE_URL + modelId)
                ?.apply()
        }
        prefs(context).edit().remove(KEY_PREFIX_OVERRIDE + modelId).apply()
    }

    private fun save(context: Context, list: List<String>) {
        prefs(context).edit()
            .putString(KEY, list.joinToString("\n"))
            .apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * EncryptedSharedPreferences を [CloudApiKeyStore] と共有するための取得。
     * CloudApiKeyStore 内部の prefs() は private なので、ここでは同じファイルを
     * 自分で開く (暗号化マスターキーは CloudApiKeyStore 側と同一)。
     */
    private fun encrypted(context: Context): SharedPreferences? {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context.applicationContext)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context.applicationContext,
                "cloud_api_credentials",
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open encrypted store", e)
            null
        }
    }
}
