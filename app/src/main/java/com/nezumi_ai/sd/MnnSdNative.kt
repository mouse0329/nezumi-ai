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
        progressListener: NativeProgressListener? = null
    ): ByteArray? = try {
        generateNative(handle, prompt, negativePrompt, width, height, steps, cfg, seed, progressListener)
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
        progressListener: NativeProgressListener?
    ): ByteArray?

    external fun cancel(handle: Long)

    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 1
}
