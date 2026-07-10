package com.nezumi_ai.sd

import android.util.Log

/**
 * Phase 0 JNI bridge for [mnn-sd-engine].
 * Sibling of [LocalDreamModule]; full txt2img lands in Phase 1+.
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
                // libmnn_sd_engine.so depends on libMNN.so — load order matters on some devices.
                try {
                    System.loadLibrary("MNN")
                } catch (_: UnsatisfiedLinkError) {
                    Log.w(TAG, "libMNN.so not in jniLibs yet")
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

    const val BACKEND_CPU = 0
    const val BACKEND_OPENCL = 1
}
