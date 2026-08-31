package com.nezumi_ai.data.inference.rnllama

class RnLlamaContext(
    val modelPath: String,
    nCtx: Int,
    nBatch: Int,
    nUbatch: Int,
    nThreads: Int,
    nGpuLayers: Int,
    mmprojPath: String? = null,
    val flashAttentionEnabled: Boolean = true,
    val contextShiftEnabled: Boolean = true,
    // Performance optimization parameters
    val mtpEnabled: Boolean = false,
    val mtpDraftTokens: Int = 5,
    val kvCacheOptimizationEnabled: Boolean = true,
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
                mmprojPath = mmprojPath,
                flashAttentionEnabled = flashAttentionEnabled,
                contextShiftEnabled = contextShiftEnabled
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

    /**
     * Render OpenAI-compatible messages using the loaded GGUF's
     * tokenizer.chat_template through llama.cpp's native Jinja engine.
     */
    fun applyGgufChatTemplate(
        messagesJson: String,
        enableThinking: Boolean = false,
        addGenerationPrompt: Boolean = true
    ): String {
        val p = ptr
        if (p == 0L || messagesJson.isBlank()) return ""
        return RnLlamaNative.nativeApplyGgufChatTemplate(
            p,
            messagesJson,
            enableThinking,
            addGenerationPrompt
        )
    }

    /**
     * Render OpenAI-compatible messages using an explicit Jinja chat template
     * (Hugging Face chat_template compatible). Used for user-selected custom /
     * builtin templates instead of the model's embedded GGUF template.
     */
    fun applyJinjaChatTemplate(
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean = true,
        addGenerationPrompt: Boolean = true
    ): String {
        val p = ptr
        if (p == 0L || messagesJson.isBlank() || chatTemplate.isBlank()) return ""
        return RnLlamaNative.nativeApplyJinjaChatTemplate(
            p,
            messagesJson,
            chatTemplate,
            enableThinking,
            addGenerationPrompt
        )
    }

    /** Whether a GGUF chat template has been successfully applied to this context. */
    fun hasGgufChatTemplate(): Boolean {
        val p = ptr
        return p != 0L && RnLlamaNative.nativeHasGgufChatTemplate(p)
    }

    /** Parse accumulated model output using the parser selected by the GGUF chat template. */
    fun parseGgufChatOutput(output: String, isPartial: Boolean): String {
        val p = ptr
        if (p == 0L) return "{}"
        return RnLlamaNative.nativeParseGgufChatOutput(p, output, isPartial)
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

    fun clearInterrupt() {
        val p = ptr
        if (p == 0L) return
        RnLlamaNative.nativeClearInterrupt(p)
    }

    fun clearKvCache() {
        val p = ptr
        if (p == 0L) return
        RnLlamaNative.nativeClearKvCache(p)
    }

    /** ロード中のモデル/mmproj が画像入力をサポートするか。 */
    val isVisionSupported: Boolean
        get() {
            val p = ptr
            return p != 0L && RnLlamaNative.nativeIsVisionSupported(p)
        }

    /** ロード中のモデル/mmproj が音声入力をサポートするか。 */
    val isAudioSupported: Boolean
        get() {
            val p = ptr
            return p != 0L && RnLlamaNative.nativeIsAudioSupported(p)
        }

    /**
     * 音声入力が要求するサンプルレート (Hz)。音声非対応モデルでは -1。
     * ネイティブ側 (libmtmd/miniaudio) が wav/mp3/flac を自動でこのレートへ
     * リサンプルするため、Kotlin 側での事前リサンプルは省略可能。
     */
    val audioSampleRate: Int
        get() {
            val p = ptr
            return if (p == 0L) -1 else RnLlamaNative.nativeGetAudioSampleRate(p)
        }

    fun release() {
        val p = ptr
        if (p == 0L) return
        ptr = 0L
        RnLlamaNative.nativeReleaseContext(p)
    }
}

