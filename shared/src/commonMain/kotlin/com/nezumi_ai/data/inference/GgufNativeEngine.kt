package com.nezumi_ai.data.inference

/**
 * GGUF (llama.cpp) ネイティブエンジンの共通インターフェース。
 *
 * Android は既存の LlamaBridge (JNI) をラップする。
 * iOS は nezumi_llama_cabi.h を Objective-C++ から呼ぶ実装を後で差し込む。
 *
 * Phase 3 の完成基準は CPU 推論。Metal / OpenCL / Vulkan は後段。
 */
interface GgufNativeEngine {

    fun isAvailable(): Boolean

    /**
     * モデルをロードしてコンテキストハンドルを返す。0 は失敗。
     * 引数の意味は LlamaBridge.llamaInit と揃える。
     */
    fun init(
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
        repeatLastN: Int = 64,
        offloadKqv: Boolean = true,
        cacheTypeK: String = "f16",
        cacheTypeV: String = "f16",
        gpuBackend: String,
        imageMaxTokens: Int,
        projectorUseGpu: Boolean
    ): Long

    fun free(ctx: Long)

    fun lastLoadError(): String

    /** トークンストリーミング。callback は各トークンで呼ばれる。 */
    fun complete(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: List<String>,
        onToken: (String) -> Unit
    ): Int

    fun interrupt(ctx: Long)

    fun getPastTokenCount(ctx: Long): Int
}
