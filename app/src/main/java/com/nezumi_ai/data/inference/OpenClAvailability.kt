package com.nezumi_ai.data.inference

import java.io.File

/**
 * 端末に OpenCL ランタイムが存在するかを判定する。
 *
 * llama.cpp の GPU レイヤーオフロードは OpenCL (または同等の GPU バックエンド)
 * が無い端末では無効。Google Tensor など OpenCL 非対応 SoC では
 * libOpenCL.so 自体が存在しない。
 */
object OpenClAvailability {
    @Volatile
    private var cached: Boolean? = null

    fun isAvailable(): Boolean {
        cached?.let { return it }
        return detect().also { cached = it }
    }

    /** テスト用にキャッシュを捨てる。 */
    internal fun resetCacheForTests() {
        cached = null
    }

    internal fun detect(
        exists: (String) -> Boolean = { File(it).exists() },
        listDir: (String) -> Array<File>? = { File(it).listFiles() }
    ): Boolean {
        val libraries = listOf(
            "/vendor/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so",
            "/system/lib64/libOpenCL.so",
            "/system/lib/libOpenCL.so",
            "/system/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so",
            "/vendor/lib64/libPVROCL.so",
            "/vendor/lib/libPVROCL.so",
            "/vendor/lib64/libOpenCL-pixel.so",
            "/vendor/lib/libOpenCL-pixel.so"
        )
        if (libraries.any(exists)) return true

        val icdDirs = listOf(
            "/vendor/etc/OpenCL/vendors",
            "/system/etc/OpenCL/vendors",
            "/etc/OpenCL/vendors"
        )
        return icdDirs.any { dir ->
            listDir(dir)?.any { file ->
                file.isFile && file.name.endsWith(".icd", ignoreCase = true)
            } == true
        }
    }
}
