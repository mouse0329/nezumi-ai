package com.nezumi_ai.sd

import android.util.Log

/**
 * JNI bridge for [mnn-sd-engine]. Replaces LocalDream's HTTP subprocess.
 */
object MnnSdNative {
    private const val TAG = "MnnSdNative"
    private const val LIB = "mnn_sd_jni"

    @Volatile
    private var loaded = false

    init {
        loadNative()
    }

    private fun loadNative() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                try {
                    System.loadLibrary("MNN")
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "libMNN.so not in jniLibs yet")
                }
                try {
                    System.loadLibrary("mnn_sd_engine")
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "libmnn_sd_engine.so not in jniLibs yet")
                }
                System.loadLibrary(LIB)
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Native library not loaded: ${e.message}")
            }
        }
    }

    fun isAvailable(): Boolean = loaded

    external fun create(): Long
    external fun destroy(handle: Long)
    external fun load(
        handle: Long,
        modelDir: String,
        backend: Int,
        openclSafeMaxSide: Int
    ): Boolean

    /**
     * ロード済みモデルの capabilities を取得する。SDXL 判定・最大辺サイズを Kotlin 側で参照するために使う。
     * 返却フォーマットは JSON 文字列 (例: {"is_sdxl":1,"max_side_px":1536,"default_side_px":1024})。
     * ネイティブ側が未対応の古い .so では null / 空文字を返す。
     */
    fun getCapabilities(handle: Long): String? = try {
        getCapabilitiesNative(handle)
    } catch (_: UnsatisfiedLinkError) {
        null
    }

    private external fun getCapabilitiesNative(handle: Long): String?
    external fun unload(handle: Long)
    external fun isLoaded(handle: Long): Boolean
    external fun probeModel(mnnPath: String, backend: Int): String
    external fun getLastError(): String

    interface NativeProgressListener {
        fun onNativeProgress(step: Int, totalSteps: Int, elapsedSec: Float)
    }

    /**
     * @return packed RGB: first 8 bytes = width (int LE) + height (int LE), then RGB triplets.
     * Returns null if generation fails or the native symbol is not yet present in the loaded .so.
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
        progressListener: NativeProgressListener? = null
    ): ByteArray? = try {
        generateNative(handle, prompt, negativePrompt, width, height, steps, cfg, seed, scheduler, progressListener)
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "generate() not found in libmnn_sd_jni.so — rebuild required: ${e.message}")
        null
    }

    private external fun generateNative(
        handle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: Int,
        progressListener: NativeProgressListener?
    ): ByteArray?

    external fun cancel(handle: Long)

    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 1

    const val SCHEDULER_EULER = 0
    const val SCHEDULER_DDIM = 1
    const val SCHEDULER_DPM = 2

    // SDXL / SD1.5 で使う OpenCL 安全上限。SDXL は latent が 128x128 になるため
    // OpenCL 経路は現行のモバイル GPU では tuning が破綻するので CPU に落とす。
    // SD1.5: 448、SDXL: 0 (常に CPU) を推奨。
    const val OPENCL_SAFE_MAX_SIDE_SD15 = 448
    const val OPENCL_SAFE_MAX_SIDE_SDXL = 0
}
