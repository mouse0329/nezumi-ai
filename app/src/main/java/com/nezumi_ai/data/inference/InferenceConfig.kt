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
    /** LiteRT-LM の投機的デコーディング有効化（推論高速化。デフォルトはオフ） */
    val enableSpeculativeDecoding: Boolean = false,
    val backendType: String = "CPU",
    // llama.cpp settings (最適化版 - Gallery 相準化)
    val llamaCppThreads: Int = 2,  // ★ スレッド削減：4 → 2（Tensor G3 CPU 最適化）
    val llamaCppGpuLayers: Int = 0,  // GPU 無効化（Tensor G3 は OpenCL 非対応）
    val llamaCppBatchSize: Int = 32,  // ★ バッチサイズ削減：512 → 32（メモリ効率重視）
    val llamaCppNKeep: Int = 0,  // KV キャッシュ無効化
    val llamaCppRopeFreqBase: Float = 500000.0f,  // 標準値
    val llamaCppRopeFreqScale: Float = 1.0f  // 標準値
) {
    companion object {
        const val MIN_CONTEXT_WINDOW = 512
        const val MAX_CONTEXT_WINDOW = 8192
        const val MIN_COMPRESSION_THRESHOLD = 50
        const val MAX_COMPRESSION_THRESHOLD = 95
        const val MIN_TEMPERATURE = 0.0f
        const val MAX_TEMPERATURE = 2.0f
        const val MIN_TOP_K = 1
        const val MAX_TOP_K = 128
        const val MIN_MAX_TOKENS = 64
        const val MAX_MAX_TOKENS = 4096
        const val MIN_TOP_P = 0.0f
        const val MAX_TOP_P = 1.0f
        const val MIN_THREADS = 1
        const val MAX_THREADS = 16

        fun getDefaultThreadCount(): Int {
            val availableCores = Runtime.getRuntime().availableProcessors()
            return availableCores.coerceIn(MIN_THREADS, MAX_THREADS)
        }
    }

    fun normalized(): InferenceConfig {
        val normalizedContext = contextWindow.coerceIn(MIN_CONTEXT_WINDOW, MAX_CONTEXT_WINDOW)
        val normalizedCompressionThreshold =
            contextCompressionThresholdPercent.coerceIn(
                MIN_COMPRESSION_THRESHOLD,
                MAX_COMPRESSION_THRESHOLD
            )
        val normalizedTemp = temperature.coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)
        val normalizedTopK = maxTopK.coerceIn(MIN_TOP_K, MAX_TOP_K)
        val normalizedMaxTokens = maxTokens.coerceIn(MIN_MAX_TOKENS, MAX_MAX_TOKENS)
        val normalizedTopP = topP.coerceIn(MIN_TOP_P, MAX_TOP_P)
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
