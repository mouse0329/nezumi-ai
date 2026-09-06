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
        return detectAtRuntime().also { cached = it }
    }

    /**
     * 実際に llama.cpp が Vulkan バックエンドを初期化できるかをネイティブプローブで判定する。
     *
     * libvulkan.so の存在だけでは ICD の健全性までは分からず、Mini App API が
     * 嘘の available=true を返す原因になっていたため、判定は GgufInferenceEngine の
     * ロード時チェックと同じ [LlamaBridge.nativeProbeGpuBackendAvailable] に委譲する。
     * プローブ自体が失敗した (ネイティブ未ロード等) 場合のみ、従来のファイル存在
     * ベース検出 [detect] にフォールバックする。
     */
    private fun detectAtRuntime(): Boolean {
        runCatching { LlamaBridge.nativeProbeGpuBackendAvailable(LlamaCppGpuBackend.VULKAN) }
            .getOrNull()?.let { return it }
        return detect()
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
