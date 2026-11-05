package com.nezumi_ai.sd

/**
 * Nezumi Kiln (mnn-sd-engine) の共通インターフェース。
 *
 * ネイティブ側は既に `mnn_sd_*` C API を公開している。
 * Android は JNI (`MnnSdNative`) 経由、iOS は同じ C API を Objective-C++ から呼ぶ。
 *
 * Phase 4 の完成基準は SD1.5 txt2img (CPU)。img2img / SDXL / Metal は後段。
 */
interface MnnSdNativeEngine {

    fun isAvailable(): Boolean

    fun create(): Long

    fun destroy(handle: Long)

    /**
     * モデルディレクトリをロードする。
     * @param backend 0 = CPU, 1 = OpenCL (iOS では 0 を推奨)
     * @return 成功なら true
     */
    fun load(handle: Long, modelDir: String, backend: Int, openclSafeMaxSide: Int): Boolean

    fun unload(handle: Long)

    fun isLoaded(handle: Long): Boolean

    fun lastError(): String

    /**
     * 画像生成。成功時は packed RGB (先頭 8 バイト = width/height LE, 以降 RGB)。
     * initImageRgb が null なら txt2img、非 null なら img2img。
     * 失敗時は null。
     */
    fun generate(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: Int,
        initImageRgb: ByteArray? = null,
        initImageWidth: Int = 0,
        initImageHeight: Int = 0,
        denoiseStrength: Float = 0f,
        onProgress: ((step: Int, totalSteps: Int, elapsedSec: Float) -> Unit)? = null
    ): ByteArray?

    fun cancel(handle: Long)

    companion object {
        const val BACKEND_CPU = 0
        const val BACKEND_OPENCL = 1

        const val SCHEDULER_EULER = 0
        const val SCHEDULER_DDIM = 1
        const val SCHEDULER_DPM = 2
    }
}
