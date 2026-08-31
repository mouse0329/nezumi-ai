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
     * Apply the model's GGUF tokenizer.chat_template to OpenAI-compatible messages.
     * The messages argument is a JSON array of {role, content} objects.
     */
    external fun nativeApplyGgufChatTemplate(
        contextPtr: Long,
        messagesJson: String,
        enableThinking: Boolean,
        addGenerationPrompt: Boolean
    ): String

    /**
     * Apply an explicit Jinja chat template (Hugging Face chat_template compatible)
     * to OpenAI-compatible messages. Passing a non-empty chatTemplate makes llama.cpp
     * build a temporary template for rendering and select the matching output parser.
     */
    external fun nativeApplyJinjaChatTemplate(
        contextPtr: Long,
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean,
        addGenerationPrompt: Boolean
    ): String

    /** Whether a GGUF chat template has been successfully applied to this context. */
    external fun nativeHasGgufChatTemplate(contextPtr: Long): Boolean

    /** Parse generated GGUF output into content/reasoning_content JSON. */
    external fun nativeParseGgufChatOutput(
        contextPtr: Long,
        output: String,
        isPartial: Boolean
    ): String

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
     * interrupt (is_interrupted) フラグをクリアする。
     * 推論開始前に必ず呼ばないと、停止を短時間に繰り返した際にフラグが
     * 残ったまま蓄積し、押していないのに次回推論が即座に中断される。
     */
    external fun nativeClearInterrupt(contextPtr: Long)

    /**
     * Release context and free native resources
     */
    external fun nativeReleaseContext(contextPtr: Long)

    /**
     * Clear KV cache for the given context
     * Call this when switching sessions or models to ensure clean state
     */
    external fun nativeClearKvCache(contextPtr: Long)

    /**
     * Whether the loaded mmproj/model supports vision input (mtmd_support_vision)
     */
    external fun nativeIsVisionSupported(contextPtr: Long): Boolean

    /**
     * Whether the loaded mmproj/model supports audio input (mtmd_support_audio)
     */
    external fun nativeIsAudioSupported(contextPtr: Long): Boolean

    /**
     * Required audio input sample rate in Hz.
     * Returns -1 when the model does not support audio input.
     */
    external fun nativeGetAudioSampleRate(contextPtr: Long): Int
}
