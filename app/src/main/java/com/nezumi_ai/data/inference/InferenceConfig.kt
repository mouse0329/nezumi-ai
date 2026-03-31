package com.nezumi_ai.data.inference

data class InferenceConfig(
    val contextWindow: Int = 4096,
    val contextCompressionEnabled: Boolean = false,
    val contextCompressionThresholdPercent: Int = 70,
    val temperature: Float = 0.7f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 512,
    val backendType: String = "CPU"
) {
    fun normalized(): InferenceConfig {
        val normalizedContext = 4096
        val normalizedCompressionThreshold = contextCompressionThresholdPercent.coerceIn(50, 95)
        val normalizedTemp = temperature.coerceIn(0.0f, 2.0f)
        val normalizedTopK = maxTopK.coerceIn(1, 128)
        val normalizedMaxTokens = maxTokens.coerceIn(64, normalizedContext)
        val normalizedBackend = if (backendType.uppercase() == "GPU") "GPU" else "CPU"
        return InferenceConfig(
            contextWindow = normalizedContext,
            contextCompressionEnabled = contextCompressionEnabled,
            contextCompressionThresholdPercent = normalizedCompressionThreshold,
            temperature = normalizedTemp,
            maxTopK = normalizedTopK,
            maxTokens = normalizedMaxTokens,
            backendType = normalizedBackend
        )
    }
}
