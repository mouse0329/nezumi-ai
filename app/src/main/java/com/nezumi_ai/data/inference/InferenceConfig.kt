package com.nezumi_ai.data.inference

data class InferenceConfig(
    val contextWindow: Int = 4096,
    val contextCompressionEnabled: Boolean = false,
    val contextCompressionThresholdPercent: Int = 70,
    val temperature: Float = 0.7f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 384,
    val topP: Float = 0.95f,
    /** LiteRT-LM の extraContext enable_thinking（Gemma 4 のみ対応。他モデルでは無視される）。デフォルトはオフ */
    val enableThinking: Boolean = false,
    val backendType: String = "CPU"
) {
    fun normalized(): InferenceConfig {
        val normalizedContext = contextWindow.coerceIn(512, 32768)
        val normalizedCompressionThreshold = contextCompressionThresholdPercent.coerceIn(50, 95)
        val normalizedTemp = temperature.coerceIn(0.0f, 2.0f)
        val normalizedTopK = maxTopK.coerceIn(1, 128)
        val normalizedMaxTokens = maxTokens.coerceIn(64, 2096)
        val normalizedTopP = topP.coerceIn(0.0f, 1.0f)
        val normalizedBackend = when (backendType.uppercase()) {
            "GPU" -> "GPU"
            "NPU" -> "NPU"
            else -> "CPU"
        }
        return copy(
            contextWindow = normalizedContext,
            contextCompressionThresholdPercent = normalizedCompressionThreshold,
            temperature = normalizedTemp,
            maxTopK = normalizedTopK,
            maxTokens = normalizedMaxTokens,
            topP = normalizedTopP,
            backendType = normalizedBackend
        )
    }
}
