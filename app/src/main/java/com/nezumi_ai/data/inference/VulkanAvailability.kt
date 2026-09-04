package com.nezumi_ai.data.inference

import java.io.File

/**
 * 端末に Vulkan ランタイムが存在するかを判定する。
 *
 * llama.cpp の Vulkan バックエンドは libvulkan.so が無い端末では無効。
 */
object VulkanAvailability {
    @Volatile
    private var cached: Boolean? = null

    fun isAvailable(): Boolean {
        cached?.let { return it }
        return detect().also { cached = it }
    }

    internal fun resetCacheForTests() {
        cached = null
    }

    internal fun detect(
        exists: (String) -> Boolean = { File(it).exists() }
    ): Boolean {
        val libraries = listOf(
            "/vendor/lib64/libvulkan.so",
            "/vendor/lib/libvulkan.so",
            "/system/lib64/libvulkan.so",
            "/system/lib/libvulkan.so",
            "/system/vendor/lib64/libvulkan.so",
            "/system/vendor/lib/libvulkan.so"
        )
        return libraries.any(exists)
    }
}
