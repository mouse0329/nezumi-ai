package com.nezumi_ai.data.inference.cloud

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android Keystore 経由で暗号化される [PlatformSecureStore] 実装。
 *
 * 旧 app 側 CloudApiKeyStore の EncryptedSharedPreferences 生成ロジックをそのまま移設したもの。
 * 初期化失敗時は平文 SharedPreferences にはフォールバックせず [isAvailable] = false とする
 * (「暗号化保存を約束したのに実態が平文」になる事故を避けるため)。
 */
class EncryptedPlatformSecureStore(context: Context) : PlatformSecureStore {

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            CloudApiKeyStore.PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Throwable) {
        CloudLog.e(TAG, "Failed to create EncryptedSharedPreferences", e)
        null
    }

    override val isAvailable: Boolean get() = prefs != null

    override fun get(key: String): String? {
        val store = prefs ?: return null
        return try {
            store.getString(key, null)
        } catch (e: Throwable) {
            CloudLog.e(TAG, "get failed key=$key", e)
            null
        }
    }

    override fun put(key: String, value: String): Boolean {
        val store = prefs ?: return false
        return runCatching { store.edit().putString(key, value).commit() }
            .onFailure { CloudLog.e(TAG, "put failed key=$key", it) }
            .getOrDefault(false)
    }

    override fun remove(key: String): Boolean {
        val store = prefs ?: return false
        return runCatching { store.edit().remove(key).commit() }
            .onFailure { CloudLog.e(TAG, "remove failed key=$key", it) }
            .getOrDefault(false)
    }

    override fun contains(key: String): Boolean {
        val store = prefs ?: return false
        return runCatching { store.contains(key) }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "EncryptedSecureStore"
    }
}

/**
 * 通常の SharedPreferences を [PlatformSecureStore] として包む実装。
 * 非機密データ (モデル ID 一覧や「個別設定を持つか」のフラグ等) 向け。
 */
class PlainPlatformSecureStore(context: Context, fileName: String) : PlatformSecureStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override val isAvailable: Boolean get() = true

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String): Boolean {
        return runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)
    }

    override fun remove(key: String): Boolean {
        return runCatching { prefs.edit().remove(key).commit() }.getOrDefault(false)
    }

    override fun contains(key: String): Boolean = prefs.contains(key)
}

/**
 * アプリ共通のストアインスタンス保持。EncryptedSharedPreferences のオープン/クローズを
 * 毎回繰り返す意味は無いので、ライフタイムは Application と一致させる。
 */
object CloudStoresHolder {

    @Volatile
    private var secure: PlatformSecureStore? = null

    @Volatile
    private var userModels: PlatformSecureStore? = null

    /** API キー等の機密値を入れる暗号化ストア (`cloud_api_credentials`)。 */
    fun secure(context: Context): PlatformSecureStore {
        secure?.let { return it }
        return synchronized(this) {
            secure ?: EncryptedPlatformSecureStore(context.applicationContext).also { secure = it }
        }
    }

    /** ユーザー定義クラウドモデル一覧を入れる平文ストア (`cloud_user_models`)。 */
    fun userModels(context: Context): PlatformSecureStore {
        userModels?.let { return it }
        return synchronized(this) {
            userModels
                ?: PlainPlatformSecureStore(context.applicationContext, "cloud_user_models")
                    .also { userModels = it }
        }
    }
}
