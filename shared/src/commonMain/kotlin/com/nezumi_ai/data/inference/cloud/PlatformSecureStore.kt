package com.nezumi_ai.data.inference.cloud

/**
 * プラットフォームのセキュア (または平文) Key-Value ストアの抽象。
 *
 * クラウド推論層の永続化はすべてこのインターフェース越しに行う。
 * - Android: EncryptedSharedPreferences 実装 (機密) と 通常 SharedPreferences 実装 (非機密)
 * - iOS: Keychain / NSUserDefaults 実装 (フェーズ1 では TODO)
 *
 * ドメイン知識 (プロバイダ ID やキー命名規則) は持たせず、
 * 命名規則は [CloudApiKeyStore] / [CloudUserModelRegistry] 側が担う。
 */
interface PlatformSecureStore {

    /** このストアが利用可能か。Android の暗号化ストア初期化失敗検知に使う。 */
    val isAvailable: Boolean

    /** 値の取得。未設定・取得失敗時は null。 */
    fun get(key: String): String?

    /** 値の保存。成功時 true。 */
    fun put(key: String, value: String): Boolean

    /** キーの削除。成功時 true。 */
    fun remove(key: String): Boolean

    /** キーが存在するか。 */
    fun contains(key: String): Boolean
}
