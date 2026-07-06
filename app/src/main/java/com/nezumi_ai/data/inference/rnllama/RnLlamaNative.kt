package com.nezumi_ai.data.inference.rnllama

/**
 * JNI interface for rnllama native library
 * Methods correspond to native C/C++ implementations in llama.cpp/rnllama
 */
object RnLlamaNative {

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var loadSuccess: Boolean = false

    /**
     * GGUF 利用時のみロードする（stable-diffusion と ggml 二重ロードを避ける）。
     */
    fun loadLibraryIfNeeded(): Boolean {
        if (loadAttempted) return loadSuccess
        synchronized(this) {
            if (loadAttempted) return loadSuccess
            loadAttempted = true
            loadSuccess = try {
                System.loadLibrary("nezumi_rnllama_jni")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
            return loadSuccess
        }
    }

    /**
     * Callback interface for token streaming
     * C++ side expects this to have an onToken method
     */
    interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Create a new llama context from a model file
     */
    external fun nativeCreateContext(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        ropeFreqBase: Float,
        ropeFreqScale: Float,
        mmprojPath: String?,
        flashAttentionEnabled: Boolean,
        contextShiftEnabled: Boolean
    ): Long

    /**
     * Set token callback for streaming response
     */
    external fun nativeSetTokenCallback(
        contextPtr: Long,
        callback: TokenCallback?
    )

    /**
     * Complete prompt with model
     */
    external fun nativeComplete(
        contextPtr: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: Array<String>?
    ): String

    /**
     * Complete prompt with media (images, audio, etc.)
     */
    external fun nativeCompleteWithMedia(
        contextPtr: Long,
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: Array<String>?,
        mediaPaths: Array<String>?
    ): String

    /**
     * Get timing information from last completion
     * Returns [promptMs, promptTokens, decodeMs, decodeTokens] or null
     */
    external fun nativeGetLastTimings(contextPtr: Long): FloatArray?

    /**
     * Interrupt ongoing completion (sets is_interrupted flag on native side)
     */
    external fun nativeInterrupt(contextPtr: Long)

    /**
     * Release context and free native resources
     */
    external fun nativeReleaseContext(contextPtr: Long)

    /**
     * Clear KV cache for the given context
     * Call this when switching sessions or models to ensure clean state
     */
    external fun nativeClearKvCache(contextPtr: Long)
}
