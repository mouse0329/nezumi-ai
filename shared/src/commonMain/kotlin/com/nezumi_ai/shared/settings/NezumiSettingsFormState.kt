package com.nezumi_ai.shared.settings

import kotlinx.serialization.Serializable

/**
 * Android / デスクトップ共通の設定フォーム状態。
 * プラットフォーム側で永続化・初期ロードを行い、このモデルを [NezumiUnifiedSettingsScreen] に渡す。
 */
@Serializable
data class NezumiSettingsFormState(
    val themeMode: NezumiThemeMode,
    val backendType: String,
    val contextWindowInput: String,
    val temperatureInput: String,
    val topkInput: String,
    val maxTokensInput: String,
    val contextCompressionEnabled: Boolean,
    val contextCompressionThresholdPercent: Int,
    val userNameInput: String,
    val systemPromptInput: String,
    val selectedModel: String,
    val llamaCppThreads: Int,
    val maxThreads: Int,
    val llamaCppGpuLayers: Int,
    val llamaCppBatchSize: Int,
    val llamaCppNKeep: Int,
    val llamaCppRopeFreqBase: Float,
    val llamaCppRopeFreqScale: Float,
    val chatHistoryLimit: Int,
    val sdSteps: Int,
    val sdCfg: Float,
) {
    companion object {
        fun default(maxThreads: Int = NezumiInferenceLimits.MAX_THREADS_DEFAULT) = NezumiSettingsFormState(
            themeMode = NezumiThemeMode.System,
            backendType = "CPU",
            contextWindowInput = "4096",
            temperatureInput = "0.7",
            topkInput = "40",
            maxTokensInput = "1024",
            contextCompressionEnabled = false,
            contextCompressionThresholdPercent = 70,
            userNameInput = "",
            systemPromptInput = "",
            selectedModel = "E2B",
            llamaCppThreads = (maxThreads / 2).coerceAtLeast(1),
            maxThreads = maxThreads,
            llamaCppGpuLayers = 0,
            llamaCppBatchSize = 512,
            llamaCppNKeep = 0,
            llamaCppRopeFreqBase = 0f,
            llamaCppRopeFreqScale = 1f,
            chatHistoryLimit = 30,
            sdSteps = 8,
            sdCfg = 7f,
        )
    }
}

@Serializable
enum class NezumiThemeMode {
    System,
    Light,
    Dark,
}

object NezumiInferenceLimits {
    const val MIN_CONTEXT_WINDOW = 512
    const val MAX_CONTEXT_WINDOW_DEFAULT = 4096
    const val MAX_CONTEXT_WINDOW_GEMMA4 = 8192
    const val MIN_COMPRESSION_THRESHOLD = 50
    const val MAX_COMPRESSION_THRESHOLD = 95
    const val MIN_TEMPERATURE = 0.0f
    const val MAX_TEMPERATURE = 2.0f
    const val MIN_TOP_K = 1
    const val MAX_TOP_K = 128
    const val MIN_MAX_TOKENS = 64
    const val MAX_MAX_TOKENS = 4096
    const val MIN_THREADS = 1
    const val MAX_THREADS_DEFAULT = 16
}

fun NezumiSettingsFormState.maxContextWindowForSelectedModel(): Int =
    if (selectedModel.equals("Gemma4-2B", ignoreCase = true) ||
        selectedModel.equals("Gemma4-4B", ignoreCase = true)
    ) {
        NezumiInferenceLimits.MAX_CONTEXT_WINDOW_GEMMA4
    } else {
        NezumiInferenceLimits.MAX_CONTEXT_WINDOW_DEFAULT
    }

fun validateNezumiSettingsFormState(state: NezumiSettingsFormState): String? {
    val temperature = state.temperatureInput.toFloatOrNull()
    val topK = state.topkInput.toIntOrNull()
    val maxTokens = state.maxTokensInput.toIntOrNull()
    val contextWindow = state.contextWindowInput.toIntOrNull()

    if (temperature == null || topK == null || maxTokens == null || contextWindow == null) {
        return "推論設定の入力値が不正です"
    }
    if (temperature !in NezumiInferenceLimits.MIN_TEMPERATURE..NezumiInferenceLimits.MAX_TEMPERATURE) {
        return "温度は ${NezumiInferenceLimits.MIN_TEMPERATURE} - ${NezumiInferenceLimits.MAX_TEMPERATURE} の範囲で入力してください"
    }
    if (topK !in NezumiInferenceLimits.MIN_TOP_K..NezumiInferenceLimits.MAX_TOP_K) {
        return "Top-K は ${NezumiInferenceLimits.MIN_TOP_K} - ${NezumiInferenceLimits.MAX_TOP_K} の範囲で入力してください"
    }
    if (maxTokens !in NezumiInferenceLimits.MIN_MAX_TOKENS..NezumiInferenceLimits.MAX_MAX_TOKENS) {
        return "Max Tokens は ${NezumiInferenceLimits.MIN_MAX_TOKENS} - ${NezumiInferenceLimits.MAX_MAX_TOKENS} の範囲で入力してください"
    }
    val maxCw = state.maxContextWindowForSelectedModel()
    if (contextWindow !in NezumiInferenceLimits.MIN_CONTEXT_WINDOW..maxCw) {
        return "コンテキストは ${NezumiInferenceLimits.MIN_CONTEXT_WINDOW} - $maxCw の範囲で入力してください"
    }
    if (state.contextCompressionThresholdPercent !in NezumiInferenceLimits.MIN_COMPRESSION_THRESHOLD..NezumiInferenceLimits.MAX_COMPRESSION_THRESHOLD) {
        return "圧縮しきい値は ${NezumiInferenceLimits.MIN_COMPRESSION_THRESHOLD} - ${NezumiInferenceLimits.MAX_COMPRESSION_THRESHOLD} の範囲で入力してください"
    }
    return null
}
