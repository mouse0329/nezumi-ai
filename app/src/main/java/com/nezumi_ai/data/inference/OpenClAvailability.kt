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
        return detectAtRuntime().also { cached = it }
    }

    /**
     * 実際に llama.cpp が OpenCL バックエンドを初期化できるかをネイティブプローブで判定する。
     *
     * ライブラリファイルの存在だけでは「ロードできるが初期化に失敗する」端末を
     * 見抜けず、Mini App API が嘘の available=true を返す原因になっていたため、
     * 判定は GgufInferenceEngine のロード時チェックと同じ
     * [LlamaBridge.nativeProbeGpuBackendAvailable] に委譲する。
     * プローブ自体が失敗した (ネイティブ未ロード等) 場合のみ、従来のファイル存在
     * ベース検出 [detect] にフォールバックする。
     */
    private fun detectAtRuntime(): Boolean {
        runCatching { LlamaBridge.nativeProbeGpuBackendAvailable(LlamaCppGpuBackend.OPENCL) }
            .getOrNull()?.let { return it }
        return detect()
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
