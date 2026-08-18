package com.nezumi_ai.data.inference.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** EncryptedSharedPreferences ベースの [PlatformSecureStore] (旧 CloudApiKeyStore のロジック移設)。 */
class EncryptedPlatformSecureStore(context: Context) : PlatformSecureStore {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context.applicationContext, CloudApiKeyStore.PREFS_FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        CloudLog.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        null
    }

    override val isAvailable: Boolean get() = prefs != null
    override fun get(key: String): String? {
        val s = prefs ?: return null
        return try { s.getString(key, null) } catch (e: Throwable) { CloudLog.e(TAG, "get failed key=$key", e); null }
    }
    override fun put(key: String, value: String): Boolean {
        val s = prefs ?: return false
        return runCatching { s.edit().putString(key, value).commit() }
            .onFailure { CloudLog.e(TAG, "put failed key=$key", it) }.getOrDefault(false)
    }
    override fun remove(key: String): Boolean {
        val s = prefs ?: return false
        return runCatching { s.edit().remove(key).commit() }
            .onFailure { CloudLog.e(TAG, "remove failed key=$key", it) }.getOrDefault(false)
    }
    override fun contains(key: String): Boolean {
        val s = prefs ?: return false
        return runCatching { s.contains(key) }.getOrDefault(false)
    }
    private companion object { const val TAG = "EncryptedSecureStore" }
}

/** 通常 SharedPreferences を [PlatformSecureStore] として包む (非機密データ向け)。 */
class PlainPlatformSecureStore(context: Context, fileName: String) : PlatformSecureStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    override val isAvailable: Boolean get() = true
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String): Boolean =
        runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
    override fun remove(key: String): Boolean =
        runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
    override fun contains(key: String): Boolean = prefs.contains(key)
}

/** アプリ共通のストアインスタンス保持 (Application ライフタイム)。 */
object CloudStoresHolder {
    @Volatile private var secure: PlatformSecureStore? = null
    @Volatile private var userModels: PlatformSecureStore? = null

    fun secure(context: Context): PlatformSecureStore {
        secure?.let { return it }
        return synchronized(this) {
            secure ?: EncryptedPlatformSecureStore(context.applicationContext).also { secure = it }
        }
    }
    fun userModels(context: Context): PlatformSecureStore {
        userModels?.let { return it }
        return synchronized(this) {
            userModels ?: PlainPlatformSecureStore(context.applicationContext, "cloud_user_models")
                .also { userModels = it }
        }
    }
}
