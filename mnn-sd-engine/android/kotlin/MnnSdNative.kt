package com.nezumi_ai.sd

/**
 * Phase 0 JNI bridge for mnn-sd-engine.
 *
 * Not wired into the app module yet — integrate after CMake hook-up in app/build.gradle.kts.
 * Sibling of [LocalDreamModule]; target API shape matches the C API in engine.h.
 */
class MnnSdNative private constructor() {

    companion object {
        private const val LIB = "mnn_sd_jni"

        init {
            try {
                System.loadLibrary(LIB)
            } catch (e: UnsatisfiedLinkError) {
                // Expected until mnn-sd-engine is added to the app NDK build.
                android.util.Log.w("MnnSdNative", "Native library not loaded: ${e.message}")
            }
        }

        @JvmStatic external fun create(): Long

        @JvmStatic external fun destroy(handle: Long)

        @JvmStatic external fun load(
            handle: Long,
            modelDir: String,
            backend: Int,
            openclSafeMaxSide: Int
        ): Boolean

        @JvmStatic external fun unload(handle: Long)

        @JvmStatic external fun isLoaded(handle: Long): Boolean

        /** Returns probe log (tensor names / shapes) or error text. */
        @JvmStatic external fun probeModel(mnnPath: String, backend: Int): String

        @JvmStatic external fun getLastError(): String

        const val BACKEND_CPU = 0
        const val BACKEND_OPENCL = 1
    }
}
