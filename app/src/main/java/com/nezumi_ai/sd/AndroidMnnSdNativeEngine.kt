package com.nezumi_ai.sd

import android.util.Log

/**
 * Android 向け Nezumi Kiln。既存の MnnSdNative (JNI) に委譲する。
 *
 * iOS 側は IosMnnSdNativeEngine が mnn_sd_* C API 経由で同じ契約を満たす。
 */
class AndroidMnnSdNativeEngine : MnnSdNativeEngine {

    override fun isAvailable(): Boolean = MnnSdNative.isAvailable()

    override fun create(): Long = MnnSdNative.create()

    override fun destroy(handle: Long) {
        if (handle != 0L) MnnSdNative.destroy(handle)
    }

    override fun load(handle: Long, modelDir: String, backend: Int, openclSafeMaxSide: Int): Boolean =
        MnnSdNative.load(handle, modelDir, backend, openclSafeMaxSide)

    override fun unload(handle: Long) {
        if (handle != 0L) MnnSdNative.unload(handle)
    }

    override fun isLoaded(handle: Long): Boolean =
        handle != 0L && MnnSdNative.isLoaded(handle)

    override fun lastError(): String = MnnSdNative.getLastError()

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
    ): ByteArray? {
        if (handle == 0L) return null
        val listener = onProgress?.let { cb ->
            object : MnnSdNative.NativeProgressListener {
                override fun onNativeProgress(step: Int, totalSteps: Int, elapsedSec: Float) {
                    cb(step, totalSteps, elapsedSec)
                }
            }
        }
        return try {
            MnnSdNative.generate(
                handle = handle,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfg = cfg,
                seed = seed,
                scheduler = scheduler,
                initImageRgb = initImageRgb,
                initImageWidth = initImageWidth,
                initImageHeight = initImageHeight,
                denoiseStrength = denoiseStrength,
                progressListener = listener
            )
        } catch (t: Throwable) {
            Log.e("AndroidMnnSdNativeEngine", "generate failed", t)
            null
        }
    }

    override fun cancel(handle: Long) {
        if (handle != 0L) MnnSdNative.cancel(handle)
    }
}
