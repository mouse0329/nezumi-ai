package com.nezumi_ai.sd

/**
 * iOS 向け Nezumi Kiln 仮実装。
 *
 * 本番では mnn-sd-engine の `mnn_sd_*` C API を Objective-C++ から呼び出す。
 * Phase 4 の完成基準は SD1.5 txt2img (CPU)。現時点では未接続。
 */
class IosMnnSdNativeEngine : MnnSdNativeEngine {

    override fun isAvailable(): Boolean = false

    override fun create(): Long = 0L

    override fun destroy(handle: Long) = Unit

    override fun load(handle: Long, modelDir: String, backend: Int, openclSafeMaxSide: Int): Boolean = false

    override fun unload(handle: Long) = Unit

    override fun isLoaded(handle: Long): Boolean = false

    override fun lastError(): String = "iOS MNN SD engine is not linked yet"

    override fun generate(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: Int,
        initImageRgb: ByteArray?,
        initImageWidth: Int,
        initImageHeight: Int,
        denoiseStrength: Float,
        onProgress: ((step: Int, totalSteps: Int, elapsedSec: Float) -> Unit)?
    ): ByteArray? = null

    override fun cancel(handle: Long) = Unit
}
