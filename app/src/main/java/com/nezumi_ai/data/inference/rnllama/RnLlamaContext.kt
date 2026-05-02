package com.nezumi_ai.data.inference.rnllama

class RnLlamaContext(
    val modelPath: String,
    nCtx: Int,
    nBatch: Int,
    nThreads: Int,
    nGpuLayers: Int,
    mmprojPath: String? = null
) {
    private var ptr: Long =
        RnLlamaNative.nativeCreateContext(
            modelPath = modelPath,
            nCtx = nCtx,
            nBatch = nBatch,
            nThreads = nThreads,
            nGpuLayers = nGpuLayers,
            useMmap = true,
            useMlock = false,
            ropeFreqBase = 0f,
            ropeFreqScale = 1f,
            mmprojPath = mmprojPath
        )

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
        mediaPaths: Array<String>
    ): String {
        val p = ptr
        if (p == 0L) return ""
        return RnLlamaNative.nativeCompleteWithMedia(
            p,
            prompt,
            nPredict,
            temperature,
            topP,
            topK,
            stopWords,
            mediaPaths
        )
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

    fun release() {
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        RnLlamaNative.nativeReleaseContext(p)
    }
}

