package com.nezumi_ai.data.miniapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * installed Mini App の永続レジストリ（§3/§35.5）。
 *
 * - 一覧は SharedPreferences (JSON配列) に保持。
 * - ファイル配置:
 *   ```
 *   filesDir/miniapps/<appId>/package/   ← Package（不変、§4）
 *   filesDir/miniapps/<appId>/data/      ← App Data（可変、§5）
 *   filesDir/miniapps/<appId>/staging/   ← インストール中の一時領域（§32 Atomic Install）
 *   ```
 */
class MiniAppStore private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** インストール済みアプリのレジストリ行。 */
    data class InstalledApp(
        val manifest: MiniAppManifest,
        val keyId: String?,
        val trusted: Boolean,
        val devMode: Boolean,
        val installedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("manifest", manifest.toJson())
            put("keyId", keyId ?: JSONObject.NULL)
            put("trusted", trusted)
            put("devMode", devMode)
            put("installedAt", installedAt)
        }

        companion object {
            fun fromJson(obj: JSONObject): InstalledApp = InstalledApp(
                manifest = MiniAppManifest.fromJson(obj.getJSONObject("manifest")),
                keyId = if (obj.isNull("keyId")) null else obj.optString("keyId", null),
                trusted = obj.optBoolean("trusted", false),
                devMode = obj.optBoolean("devMode", false),
                installedAt = obj.optLong("installedAt", 0L)
            )
        }
    }

    fun list(): List<InstalledApp> {
        val raw = prefs.getString(KEY_APPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { InstalledApp.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "installed app registry is broken, resetting", e)
            emptyList()
        }
    }

    fun get(appId: String): InstalledApp? = list().firstOrNull { it.manifest.id == appId }

    fun isInstalled(appId: String): Boolean = get(appId) != null

    fun register(app: InstalledApp) {
        val apps = list().filterNot { it.manifest.id == app.manifest.id }.toMutableList()
        apps.add(app)
        persist(apps)
    }

    fun unregister(appId: String) {
        persist(list().filterNot { it.manifest.id == appId })
    }

    private fun persist(apps: List<InstalledApp>) {
        val arr = JSONArray()
        apps.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_APPS, arr.toString()).apply()
    }

    // ---- ディレクトリ解決（§5/§6 ストレージ境界） ----

    fun appsRoot(): File = File(context.filesDir, "miniapps").apply { mkdirs() }

    fun appRoot(appId: String): File = File(appsRoot(), sanitize(appId))

    fun packageDir(appId: String): File = File(appRoot(appId), "package")

    fun dataDir(appId: String): File = File(appRoot(appId), "data")

    fun stagingDir(appId: String): File = File(appRoot(appId), "staging")

    fun trustedKeysFile(): File = File(context.filesDir, "miniapp_trusted_keys.json")

    companion object {
        private const val TAG = "MiniAppStore"
        private const val PREF_NAME = "miniapp_store"
        private const val KEY_APPS = "installed_apps"

        /** appId は検証済み形式のみ許容するが、防御的にパス区切り等を除去する。 */
        fun sanitize(appId: String): String = appId.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        @Volatile
        private var instance: MiniAppStore? = null

        fun get(context: Context): MiniAppStore =
            instance ?: synchronized(this) {
                instance ?: MiniAppStore(context.applicationContext).also { instance = it }
            }
    }
}
