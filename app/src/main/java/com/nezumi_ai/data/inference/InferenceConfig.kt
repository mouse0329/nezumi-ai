package com.nezumi_ai.data.inference

data class InferenceConfig(
    val contextWindow: Int = 4096,
    val contextCompressionEnabled: Boolean = false,
    val contextCompressionThresholdPercent: Int = 70,
    val temperature: Float = 0.7f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 1024,
    val topP: Float = 0.95f,
    /** LiteRT-LM の投機的デコーディング有効化（推論高速化。デフォルトはオフ） */
    val enableThinking: Boolean = false,
    val enableSpeculativeDecoding: Boolean = false,
    val backendType: String = "CPU",
    /** LiteRT-LM のロード時に vision/audio executor を必須化する。 */
    val requireMultimodal: Boolean = false,
    // llama.cpp settings (最適化版 - Gallery 相準化)
 val llamaCppThreads: Int = 4, // スレッド数を 4 に変更
    val llamaCppGpuLayers: Int = 0,  // GPU 無効化（Tensor G3 は OpenCL 非対応）
 val llamaCppBatchSize: Int = 512, // バッチサイズをデフォルトに戻す：32 → 512
    val llamaCppUBatchSize: Int = 512,  // n_ubatch を独立制御できるように準備
    val llamaCppKvUnified: Boolean = true,  // KV 統合をデフォルトで有効化
    val llamaCppNKeep: Int = 0,  // KV キャッシュ無効化
    val llamaCppRopeFreqBase: Float = 500000.0f,  // 標準値
    val llamaCppRopeFreqScale: Float = 1.0f,  // 標準値
    /** モデルパスに紐付いたカスタムストップトークン（カンマ区切り）。空の場合はデフォルトのみ使用 */
    val customStopTokens: List<String> = emptyList(),
    /** ツール呼び出しを有効化（LiteRT-LM / GGUF） */
    val enableToolCalling: Boolean = false,
    // Performance optimization settings
    val mtpEnabled: Boolean = false,  // Multi-Token Prediction (投機的デコーディング)
    val mtpDraftTokens: Int = 5,  // MTP draft token count (1-16)
    val flashAttentionEnabled: Boolean = true,  // Flash Attention (自動検出)
    val dynamicBatchSizeEnabled: Boolean = true,  // 動的バッチサイズ調整
    val promptBatchSize: Int = 512,  // プロンプト処理用バッチサイズ
    val generationBatchSize: Int = 128,  // トークン生成用バッチサイズ
    val kvCacheOptimizationEnabled: Boolean = true,  // KVキャッシュ最適化
    val contextShiftEnabled: Boolean = true  // コンテキストシフト有効化
) {
    data class GgufConfig(
        val nThreads: Int = 4,
        val nBatch: Int = 512,
        val nUBatch: Int = 512,
        val nGpuLayers: Int = 0,
        val kvUnified: Boolean = true,
        val ropeFreqBase: Float = 500000.0f,
        val ropeFreqScale: Float = 1.0f
    )

    data class LiteRtConfig(
        val enableThinking: Boolean = false,
        val enableSpeculativeDecoding: Boolean = false,
        val requireMultimodal: Boolean = false
    )

    val ggufConfig: GgufConfig
        get() = GgufConfig(
            nThreads = llamaCppThreads,
            nBatch = llamaCppBatchSize,
            nUBatch = llamaCppUBatchSize,
            nGpuLayers = llamaCppGpuLayers,
            kvUnified = llamaCppKvUnified,
            ropeFreqBase = llamaCppRopeFreqBase,
            ropeFreqScale = llamaCppRopeFreqScale
        )

    val liteRtConfig: LiteRtConfig
        get() = LiteRtConfig(
            enableThinking = enableThinking,
            enableSpeculativeDecoding = enableSpeculativeDecoding,
            requireMultimodal = requireMultimodal
        )

    companion object {
        const val MIN_CONTEXT_WINDOW = 512
        const val MAX_CONTEXT_WINDOW = 131072
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
        const val MIN_BATCH_SIZE = 32
        const val MAX_BATCH_SIZE = 2048
        const val MIN_UBATCH_SIZE = 32
        const val MAX_UBATCH_SIZE = 2048

        fun getDefaultThreadCount(): Int {
            val availableCores = Runtime.getRuntime().availableProcessors()
            return availableCores.coerceIn(MIN_THREADS, MAX_THREADS)
        }
    }

    fun isContextCompressionEnabledForRuntime(): Boolean {
        return com.nezumi_ai.BuildConfig.CONTEXT_COMPRESSION_ENABLED && contextCompressionEnabled
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
        val normalizedBatchSize = llamaCppBatchSize.coerceIn(MIN_BATCH_SIZE, MAX_BATCH_SIZE)
        val normalizedUBatchSize = llamaCppUBatchSize.coerceIn(MIN_UBATCH_SIZE, MAX_UBATCH_SIZE)
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
            llamaCppBatchSize = normalizedBatchSize,
            llamaCppUBatchSize = normalizedUBatchSize,
            backendType = normalizedBackend,
            requireMultimodal = requireMultimodal,
            customStopTokens = customStopTokens.map { it.trim() }.filter { it.isNotEmpty() },
            enableToolCalling = enableToolCalling
        )
    }

    fun forModelLoad(): InferenceConfig {
        return InferenceConfig(
            contextWindow = contextWindow,
            backendType = backendType,
            requireMultimodal = requireMultimodal,
            llamaCppThreads = llamaCppThreads,
            llamaCppGpuLayers = llamaCppGpuLayers,
            llamaCppBatchSize = llamaCppBatchSize,
            llamaCppUBatchSize = llamaCppUBatchSize,
            llamaCppKvUnified = llamaCppKvUnified,
            llamaCppNKeep = llamaCppNKeep,
            llamaCppRopeFreqBase = llamaCppRopeFreqBase,
            llamaCppRopeFreqScale = llamaCppRopeFreqScale
        )
    }
}
