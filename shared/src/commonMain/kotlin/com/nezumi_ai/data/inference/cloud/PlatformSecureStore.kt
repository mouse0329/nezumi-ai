package com.nezumi_ai.data.inference.cloud

/** プラットフォームの Key-Value ストア抽象。ドメイン知識は持たない。 */
interface PlatformSecureStore {
    val isAvailable: Boolean
    fun get(key: String): String?
    fun put(key: String, value: String): Boolean
    fun remove(key: String): Boolean
    fun contains(key: String): Boolean
}
