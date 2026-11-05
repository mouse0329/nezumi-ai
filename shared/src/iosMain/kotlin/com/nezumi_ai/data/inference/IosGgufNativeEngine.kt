package com.nezumi_ai.data.inference

/**
 * iOS 向け GGUF エンジンの仮実装。
 *
 * 本番では nezumi_llama_cabi.h を Objective-C++ から呼び、
 * Metal ではなくまず CPU 推論を完成基準とする。
 * 現時点では未接続のため isAvailable は false。
 */
class IosGgufNativeEngine : GgufNativeEngine {

    override fun isAvailable(): Boolean = false

    override fun init(
        modelPath: String,
        nCtx: Int,
        nBatch: Int,
        nUbatch: Int,
        nThreads: Int,
        nThreadsBatch: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        ropeFreqBase: Float,
        ropeFreqScale: Float,
        mmprojPath: String?,
        flashAttentionEnabled: Boolean,
        contextShiftEnabled: Boolean,
        kvUnified: Boolean,
        seed: Int,
        repeatLastN: Int,
        offloadKqv: Boolean,
        cacheTypeK: String,
        cacheTypeV: String,
        gpuBackend: String,
        imageMaxTokens: Int,
        projectorUseGpu: Boolean
    ): Long = 0L

    override fun free(ctx: Long) = Unit

    override fun lastLoadError(): String = "iOS GGUF engine is not linked yet"

    override fun complete(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: List<String>,
        onToken: (String) -> Unit
    ): Int = -1

    override fun interrupt(ctx: Long) = Unit

    override fun getPastTokenCount(ctx: Long): Int = -1
}
