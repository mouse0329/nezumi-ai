package com.nezumi_ai.data.inference.cloud

/** TODO(ios): 機密値は Keychain、非機密値は NSUserDefaults に保存する実装を後日追加。 */
class IosPlatformSecureStore : PlatformSecureStore {
    override val isAvailable: Boolean get() = TODO("iOS Keychain implementation is not yet provided")
    override fun get(key: String): String? = TODO("iOS Keychain implementation is not yet provided")
    override fun put(key: String, value: String): Boolean = TODO("iOS Keychain implementation is not yet provided")
    override fun remove(key: String): Boolean = TODO("iOS Keychain implementation is not yet provided")
    override fun contains(key: String): Boolean = TODO("iOS Keychain implementation is not yet provided")
}
