package com.nezumi_ai.data.inference

import android.util.Log

/**
 * Android 向け GGUF エンジン。既存の LlamaBridge (JNI) に委譲する。
 *
 * iOS 側は IosGgufNativeEngine が nezumi_llama_cabi.h 経由で同じ契約を満たす。
 */
class AndroidGgufNativeEngine : GgufNativeEngine {

    override fun isAvailable(): Boolean = LlamaBridge.isLibraryLoaded()

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
    ): Long = LlamaBridge.llamaInit(
        modelPath = modelPath,
        nCtx = nCtx,
        nBatch = nBatch,
        nUbatch = nUbatch,
        nThreads = nThreads,
        nThreadsBatch = nThreadsBatch,
        nGpuLayers = nGpuLayers,
        useMmap = useMmap,
        useMlock = useMlock,
        ropeFreqBase = ropeFreqBase,
        ropeFreqScale = ropeFreqScale,
        mmprojPath = mmprojPath,
        flashAttentionEnabled = flashAttentionEnabled,
        contextShiftEnabled = contextShiftEnabled,
        kvUnified = kvUnified,
        seed = seed,
        repeatLastN = repeatLastN,
        offloadKqv = offloadKqv,
        cacheTypeK = cacheTypeK,
        cacheTypeV = cacheTypeV,
        gpuBackend = gpuBackend,
        imageMaxTokens = imageMaxTokens,
        projectorUseGpu = projectorUseGpu
    )

    override fun free(ctx: Long) {
        if (ctx != 0L) LlamaBridge.llamaFree(ctx)
    }

    override fun lastLoadError(): String = LlamaBridge.nativeGetLastLoadError()

    override fun complete(
        ctx: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        stopWords: List<String>,
        onToken: (String) -> Unit
    ): Int {
        if (ctx == 0L) return -1
        val callback = object : LlamaBridge.TokenCallback {
            override fun onToken(token: String) = onToken(token)
        }
        LlamaBridge.nativeSetTokenCallback(ctx, callback)
        return try {
            // 戻り値は生成テキスト。エラー時は空文字を負値扱いにする。
            val text = LlamaBridge.nativeComplete(
                ctx = ctx,
                prompt = prompt,
                nPredict = maxTokens,
                temperature = temperature,
                topP = topP,
                topK = topK,
                repeatPenalty = 1.1f,
                stopWords = stopWords.toTypedArray()
            )
            if (text.isEmpty()) -1 else 0
        } catch (t: Throwable) {
            Log.e("AndroidGgufNativeEngine", "complete failed", t)
            -1
        } finally {
            LlamaBridge.nativeSetTokenCallback(ctx, null)
        }
    }

    override fun interrupt(ctx: Long) {
        if (ctx != 0L) LlamaBridge.nativeInterrupt(ctx)
    }

    override fun getPastTokenCount(ctx: Long): Int =
        if (ctx == 0L) -1 else LlamaBridge.nativeGetPastTokenCount(ctx)
}
