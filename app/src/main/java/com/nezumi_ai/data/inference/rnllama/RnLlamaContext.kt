package com.nezumi_ai.data.inference.rnllama

class RnLlamaContext(
    val modelPath: String,
    nCtx: Int,
    nBatch: Int,
    nUbatch: Int,
    nThreads: Int,
    nGpuLayers: Int,
    mmprojPath: String? = null,
    // Performance optimization parameters
    val mtpEnabled: Boolean = false,
    val mtpDraftTokens: Int = 5,
    val flashAttentionEnabled: Boolean = true,
    val kvCacheOptimizationEnabled: Boolean = true,
    val contextShiftEnabled: Boolean = true,
    ropeFreqBase: Float = 0f,
    ropeFreqScale: Float = 1f
) {
    private var ptr: Long = 0L

    init {
        ptr = if (RnLlamaNative.loadLibraryIfNeeded()) {
            RnLlamaNative.nativeCreateContext(
                modelPath = modelPath,
                nCtx = nCtx,
                nBatch = nBatch,
                nUbatch = nUbatch,
                nThreads = nThreads,
                nGpuLayers = nGpuLayers,
                useMmap = true,
                useMlock = false,
                ropeFreqBase = ropeFreqBase,
                ropeFreqScale = ropeFreqScale,
                mmprojPath = mmprojPath
            )
        } else {
            0L
        }
    }

    val isValid: Boolean get() = ptr != 0L

    data class LastTimings(
        val promptMs: Float,
        val promptTokens: Float,
        val decodeMs: Float,
        val decodeTokens: Float
    ) {
        val decodeTokensPerSecond: Float?
            get() = if (decodeMs > 0f && decodeTokens > 0f) decodeTokens * 1000f / decodeMs else null
    }

    fun setTokenCallback(cb: ((String) -> Unit)?) {
        if (ptr == 0L) return
        if (cb == null) {
            RnLlamaNative.nativeSetTokenCallback(ptr, null)
            return
        }
        RnLlamaNative.nativeSetTokenCallback(ptr, object : RnLlamaNative.TokenCallback {
            override fun onToken(token: String) {
                cb(token)
            }
        })
    }

    fun complete(
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: Array<String>
    ): String {
        val p = ptr
        if (p == 0L) return ""
        return RnLlamaNative.nativeComplete(p, prompt, nPredict, temperature, topP, topK, stopWords)
    }

    fun completeWithMedia(
        prompt: String,
        nPredict: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: Array<String>,
        mediaPaths: Array<String>,
        // Optional per-token streaming callback.
        //
        // IMPORTANT: nativeCompleteWithMedia() is a blocking JNI call that
        // internally pushes each generated token into `sendToken()`
        // (NezumiRnLlamaJni.cpp). `sendToken()` is a no-op unless
        // `holder->token_callback` has been installed beforehand via
        // nativeSetTokenCallback(). If we don't register a callback here,
        // every token is dropped on the native side and the only output the
        // caller ever sees is the final `out` string that is returned at the
        // end of generation — i.e. the UI appears frozen during image
        // analysis and the entire reply pops in at once after the model
        // finishes (often minutes later for vision models).
        //
        // Pass a non-null `onToken` to get real-time streaming.
        onToken: ((String) -> Unit)? = null
    ): String {
        val p = ptr
        if (p == 0L) return ""
        if (onToken != null) {
            setTokenCallback(onToken)
        }
        return try {
            RnLlamaNative.nativeCompleteWithMedia(
                p,
                prompt,
                nPredict,
                temperature,
                topP,
                topK,
                stopWords,
                mediaPaths
            )
        } finally {
            if (onToken != null) {
                // Always clear the callback so a stale lambda doesn't capture
                // a cancelled Flow / closed channel on the next round.
                setTokenCallback(null)
            }
        }
    }

    fun getLastTimings(): LastTimings? {
        val p = ptr
        if (p == 0L) return null
        val values = RnLlamaNative.nativeGetLastTimings(p) ?: return null
        if (values.size < 4) return null
        return LastTimings(
            promptMs = values[0],
            promptTokens = values[1],
            decodeMs = values[2],
            decodeTokens = values[3]
        )
    }

    fun interrupt() {
        val p = ptr
        if (p == 0L) return
        RnLlamaNative.nativeInterrupt(p)
    }

    fun clearKvCache() {
        val p = ptr
        if (p == 0L) return
        RnLlamaNative.nativeClearKvCache(p)
    }

    fun release() {
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        RnLlamaNative.nativeReleaseContext(p)
    }
}

