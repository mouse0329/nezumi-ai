package com.nezumi_ai.data.inference

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * メモリ推定結果のプロセス内キャッシュ。
 *
 * ファイルサイズやアーキテクチャなど「設定に依存しない固定情報」と、
 * n_ctx / GPU layers などを含む「パラメータ付き見積もり」を分けて保持する。
 * セーフティモデル (safety.onnx 等) や非 LLM ファイルはキャッシュしない。
 *
 * 設定画面から [clear] で破棄できる。
 */
object MemoryEstimateCache {

    private const val TAG = "MemoryEstimateCache"

    data class FixedInfo(
        val architecture: String,
        val fileSizeBytes: Long,
        val paramCount: Long = 0,
        val layerTotal: Int = 0,
        val weightsBytes: Long = 0,
        val auxSectionsBytes: Long = 0,
    )

    private val fixedByPath = ConcurrentHashMap<String, FixedInfo>()
    private val ggufByKey = ConcurrentHashMap<String, MemoryEstimator.MemoryEstimate>()
    private val litertlmByKey = ConcurrentHashMap<String, LitertlmMemoryEstimator.MemoryEstimate>()

    /** セーフティ / 非 LLM パスは推定・キャッシュ対象外。 */
    fun isEligible(modelPath: String): Boolean {
        val name = modelPath.substringAfterLast('/').lowercase()
        if (name == "safety.onnx" || name.startsWith("safety.")) return false
        if (name.endsWith(".onnx") && "safety" in name) return false
        return name.endsWith(".gguf") ||
            name.endsWith(".litertlm") ||
            name.endsWith(".task")
    }

    fun getFixed(modelPath: String): FixedInfo? =
        if (isEligible(modelPath)) fixedByPath[modelPath] else null

    fun putFixed(modelPath: String, info: FixedInfo) {
        if (!isEligible(modelPath)) return
        fixedByPath[modelPath] = info
    }

    fun ggufKey(modelPath: String, params: MemoryEstimator.EstimateParams): String =
        "$modelPath|ctx=${params.nCtx}|gpu=${params.nGpuLayers}|batch=${params.nBatch}|kv=${params.kvCacheType.nativeValue}"

    fun getGguf(modelPath: String, params: MemoryEstimator.EstimateParams): MemoryEstimator.MemoryEstimate? {
        if (!isEligible(modelPath)) return null
        return ggufByKey[ggufKey(modelPath, params)]
    }

    fun putGguf(modelPath: String, params: MemoryEstimator.EstimateParams, estimate: MemoryEstimator.MemoryEstimate) {
        if (!isEligible(modelPath)) return
        putFixed(
            modelPath,
            FixedInfo(
                architecture = estimate.architecture,
                fileSizeBytes = estimate.fileSizeBytes,
                paramCount = estimate.paramCount,
                layerTotal = estimate.layerTotal,
                weightsBytes = estimate.weightsCpuBytes + estimate.weightsGpuBytes,
            )
        )
        ggufByKey[ggufKey(modelPath, params)] = estimate
    }

    fun litertlmKey(modelPath: String, params: LitertlmMemoryEstimator.EstimateParams): String =
        "$modelPath|tok=${params.maxNumTokens}|kv=${params.kvCacheType.name}|va=${params.withVisionAudio}"

    fun getLitertlm(modelPath: String, params: LitertlmMemoryEstimator.EstimateParams): LitertlmMemoryEstimator.MemoryEstimate? {
        if (!isEligible(modelPath)) return null
        return litertlmByKey[litertlmKey(modelPath, params)]
    }

    fun putLitertlm(
        modelPath: String,
        params: LitertlmMemoryEstimator.EstimateParams,
        estimate: LitertlmMemoryEstimator.MemoryEstimate,
    ) {
        if (!isEligible(modelPath)) return
        putFixed(
            modelPath,
            FixedInfo(
                architecture = estimate.architecture,
                fileSizeBytes = estimate.fileSizeBytes,
                weightsBytes = estimate.weightsBytes,
                auxSectionsBytes = estimate.auxSectionsBytes,
            )
        )
        litertlmByKey[litertlmKey(modelPath, params)] = estimate
    }

    fun clear() {
        val n = fixedByPath.size + ggufByKey.size + litertlmByKey.size
        fixedByPath.clear()
        ggufByKey.clear()
        litertlmByKey.clear()
        Log.i(TAG, "cleared $n cached entries")
    }

    fun size(): Int = fixedByPath.size + ggufByKey.size + litertlmByKey.size
}
