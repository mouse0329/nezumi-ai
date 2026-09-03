package com.nezumi_ai.data.inference

/** Direct vendor/llama.cpp context used by the GGUF inference engine. */
class LlamaCppContext(
    val modelPath: String,
    nCtx: Int,
    nBatch: Int,
    nUbatch: Int,
    nThreads: Int,
    nGpuLayers: Int,
    mmprojPath: String? = null,
    flashAttentionEnabled: Boolean = true,
    contextShiftEnabled: Boolean = true,
    ropeFreqBase: Float = 0f,
    ropeFreqScale: Float = 1f
) {
    private var ptr: Long = if (LlamaBridge.isLibraryLoaded()) {
        LlamaBridge.llamaInit(
            modelPath,
            nCtx,
            nBatch,
            nUbatch,
            nThreads,
            nGpuLayers,
            true,
            false,
            ropeFreqBase,
            ropeFreqScale,
            mmprojPath,
            flashAttentionEnabled,
            contextShiftEnabled,
            -1
        )
    } else {
        0L
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

    fun setTokenCallback(callback: ((String) -> Unit)?) {
        if (ptr == 0L) return
        val bridgeCallback = callback?.let { onToken ->
            object : LlamaBridge.TokenCallback {
                override fun onToken(token: String) = onToken(token)
            }
        }
        LlamaBridge.nativeSetTokenCallback(ptr, bridgeCallback)
    }

    fun applyGgufChatTemplate(messagesJson: String, enableThinking: Boolean, addGenerationPrompt: Boolean): String {
        if (ptr == 0L || messagesJson.isBlank()) return ""
        return LlamaBridge.nativeApplyGgufChatTemplate(ptr, messagesJson, enableThinking, addGenerationPrompt)
    }

    fun applyJinjaChatTemplate(messagesJson: String, chatTemplate: String, enableThinking: Boolean, addGenerationPrompt: Boolean): String {
        if (ptr == 0L || messagesJson.isBlank() || chatTemplate.isBlank()) return ""
        return LlamaBridge.nativeApplyJinjaChatTemplate(ptr, messagesJson, chatTemplate, enableThinking, addGenerationPrompt)
    }

    fun hasGgufChatTemplate(): Boolean = ptr != 0L && LlamaBridge.nativeHasGgufChatTemplate(ptr)

    fun parseGgufChatOutput(output: String, isPartial: Boolean): String =
        if (ptr == 0L) "{}" else LlamaBridge.nativeParseGgufChatOutput(ptr, output, isPartial)

    fun complete(prompt: String, nPredict: Int, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, stopWords: Array<String>): String {
        if (ptr == 0L) return ""
        return LlamaBridge.nativeComplete(ptr, prompt, nPredict, temperature, topP, topK, repeatPenalty, stopWords)
    }

    fun completeWithMedia(prompt: String, nPredict: Int, temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, stopWords: Array<String>, mediaPaths: Array<String>): String {
        if (ptr == 0L) return ""
        return LlamaBridge.nativeCompleteWithMedia(ptr, prompt, nPredict, temperature, topP, topK, repeatPenalty, stopWords, mediaPaths)
    }

    fun getLastTimings(): LastTimings? {
        if (ptr == 0L) return null
        val values = LlamaBridge.nativeGetLastTimings(ptr) ?: return null
        if (values.size < 4) return null
        return LastTimings(values[0], values[1], values[2], values[3])
    }

    fun interrupt() {
        if (ptr != 0L) LlamaBridge.nativeInterrupt(ptr)
    }

    fun clearInterrupt() {
        if (ptr != 0L) LlamaBridge.nativeClearInterrupt(ptr)
    }

    fun clearKvCache() {
        if (ptr != 0L) LlamaBridge.llamaClearKvCache(ptr)
    }

    val isVisionSupported: Boolean
        get() = ptr != 0L && LlamaBridge.nativeIsVisionSupported(ptr)

    val isAudioSupported: Boolean
        get() = ptr != 0L && LlamaBridge.nativeIsAudioSupported(ptr)

    val audioSampleRate: Int
        get() = if (ptr == 0L) -1 else LlamaBridge.nativeGetAudioSampleRate(ptr)

    fun release() {
        val context = ptr
        if (context == 0L) return
        ptr = 0L
        LlamaBridge.llamaFree(context)
    }
}