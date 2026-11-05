package com.nezumi_ai.data.inference.cloud

import kotlinx.cinterop.CFDictionaryRef
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.CoreFoundation.CFTypeRef
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS のクラウド設定用 Key-Value ストア。
 *
 * API キーとエンドポイントを UserDefaults に置かず、Keychain の generic-password
 * item として保存する。service を固定し、account に shared 側のキーを使うことで
 * Android の PlatformSecureStore と同じ抽象に収める。
 */
class IosPlatformSecureStore(
    private val service: String = "com.nezumi_ai.cloud"
) : PlatformSecureStore {
    override val isAvailable: Boolean get() = true

    override fun get(key: String): String? = memScoped {
        val result = alloc<ObjCObjectVar<CFTypeRef?>>()
        val status = SecItemCopyMatching(
            query(key, returnData = true) as CFDictionaryRef,
            result.ptr
        )
        if (status != errSecSuccess) return@memScoped null
        val data = result.value as? NSData ?: return@memScoped null
        NSString.create(
            bytes = data.bytes,
            length = data.length,
            encoding = NSUTF8StringEncoding
        )?.toString()
    }

    override fun put(key: String, value: String): Boolean {
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return false
        val attributes = mapOf(kSecValueData to data)
        val query = query(key, returnData = false)
        val added = SecItemAdd((query + attributes) as CFDictionaryRef, null)
        if (added == errSecSuccess) return true
        if (added != errSecDuplicateItem) return false
        return SecItemUpdate(
            query as CFDictionaryRef,
            attributes as CFDictionaryRef
        ) == errSecSuccess
    }

    override fun remove(key: String): Boolean {
        val status = SecItemDelete(query(key, returnData = false) as CFDictionaryRef)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    override fun contains(key: String): Boolean {
        val status = SecItemCopyMatching(
            query(key, returnData = false) as CFDictionaryRef,
            null
        )
        return status == errSecSuccess
    }

    private fun query(key: String, returnData: Boolean): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to key,
        kSecReturnData to returnData,
        kSecMatchLimit to kSecMatchLimitOne
    )
}
