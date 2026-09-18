package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * GGUF モデルを llama.cpp でロードする *前* に必要メモリ量を見積もるモジュール。
 *
 * 内部的には [LlamaBridge.nativeEstimateMemoryUsage] を呼び、
 * gguf_init_from_file(no_alloc=true) でテンソルデータを読み込まずに
 * ヘッダー/テンソルメタ情報だけを取得して計算する（モデル自体はロードしない）。
 *
 * 用途:
 *   - モデル選択画面で「この端末で動くか」を事前判定する
 *   - n_ctx / n_gpu_layers を変えたときの必要メモリの再計算（シミュレーション）
 *   - OOM を未然に防ぐためのロード前ガード
 */
object MemoryEstimator {

    private const val TAG = "MemoryEstimator"

    private val json = Json { ignoreUnknownKeys = true }

    /** ネイティブ層からの生の見積もり結果（バイト単位）。 */
    @Serializable
    data class RawEstimate(
        @SerialName("ok") val ok: Boolean = false,
        @SerialName("error") val error: String? = null,
        @SerialName("architecture") val architecture: String = "unknown",
        @SerialName("n_params") val nParams: Long = 0,
        @SerialName("file_size_bytes") val fileSizeBytes: Long = 0,
        @SerialName("n_layer_total") val nLayerTotal: Int = 0,
        @SerialName("n_layer_offloaded") val nLayerOffloaded: Int = 0,
        @SerialName("weights_cpu_bytes") val weightsCpuBytes: Long = 0,
        @SerialName("weights_gpu_bytes") val weightsGpuBytes: Long = 0,
        @SerialName("n_ctx_used") val nCtxUsed: Int = 0,
        @SerialName("kv_cache_bytes") val kvCacheBytes: Long = 0,
        @SerialName("compute_buffer_bytes") val computeBufferBytes: Long = 0,
        @SerialName("total_ram_bytes") val totalRamBytes: Long = 0,
        @SerialName("total_vram_bytes") val totalVramBytes: Long = 0,
        @SerialName("total_bytes_no_gpu") val totalBytesNoGpu: Long = 0,
    )

    /** KV キャッシュの量子化型。 */
    enum class KvCacheType(val nativeValue: String) {
        F16("f16"),
        F32("f32"),
        Q8_0("q8_0"),
        Q4_0("q4_0"),
    }

    /** 見積もり時のロードパラメータ。実際の llamaInit と同じ値を渡すことで精度が上がる。 */
    data class EstimateParams(
        val nCtx: Int = 4096,
        val nGpuLayers: Int = 0,
        val nBatch: Int = 512,
        val kvCacheType: KvCacheType = KvCacheType.F16,
    )

    /**
     * この端末で当該モデルをロードした場合に必要な RAM / VRAM の見積もり。
     * 単位はすべてバイト。UI 表示用に MB/GB へ変換するのは呼び出し側で行う。
     */
    data class MemoryEstimate(
        val architecture: String,
        val paramCount: Long,
        val fileSizeBytes: Long,
        val layerTotal: Int,
        val layerOffloaded: Int,
        val weightsCpuBytes: Long,
        val weightsGpuBytes: Long,
        val contextUsed: Int,
        val kvCacheBytes: Long,
        val computeBufferBytes: Long,
        /** mmap 想定で実際にプロセスが確保する RAM の見積もり（CPU 重み + KV + 計算バッファ + 固定オーバーヘッド）。 */
        val estimatedRamBytes: Long,
        /** GPU オフロード分の VRAM 見積もり（nGpuLayers=0 のときは 0）。 */
        val estimatedVramBytes: Long,
        /** GPU を一切使わない場合の参考値（file_size + kv + compute + overhead）。 */
        val estimatedRamBytesNoGpu: Long,
    )

    sealed class EstimateResult {
        data class Success(val estimate: MemoryEstimate) : EstimateResult()
        data class Failure(val reason: String) : EstimateResult()
    }

    /** [fitCheck] の判定結果。 */
    data class FitCheck(
        val fits: Boolean,
        val availableRamBytes: Long,
        val requiredRamBytes: Long,
        /** 安全マージン込みで必要な RAM（requiredRamBytes * (1 + margin)）。 */
        val requiredRamBytesWithMargin: Long,
    )

    /**
     * GGUF ファイルからメモリ使用量を見積もる。モデルはロードしない（高速・軽量）。
     *
     * @param modelPath gguf ファイルの絶対パス
     * @param params ロード想定パラメータ（n_ctx / n_gpu_layers など）
     */
    fun estimate(modelPath: String, params: EstimateParams = EstimateParams()): EstimateResult {
        if (!LlamaBridge.isLibraryLoaded()) {
            return EstimateResult.Failure("llama_bridge library not loaded")
        }
        if (!File(modelPath).exists()) {
            return EstimateResult.Failure("model file not found: $modelPath")
        }

        val rawJson = try {
            LlamaBridge.nativeEstimateMemoryUsage(
                modelPath,
                params.nCtx,
                params.nGpuLayers,
                params.nBatch,
                params.kvCacheType.nativeValue
            )
        } catch (e: Throwable) {
            Log.e(TAG, "nativeEstimateMemoryUsage threw", e)
            return EstimateResult.Failure("native call failed: ${e.message}")
        }

        val raw = try {
            json.decodeFromString<RawEstimate>(rawJson)
        } catch (e: Exception) {
            Log.e(TAG, "failed to parse estimate JSON: $rawJson", e)
            return EstimateResult.Failure("failed to parse native response")
        }

        if (!raw.ok) {
            return EstimateResult.Failure(raw.error ?: "unknown error")
        }

        return EstimateResult.Success(
            MemoryEstimate(
                architecture = raw.architecture,
                paramCount = raw.nParams,
                fileSizeBytes = raw.fileSizeBytes,
                layerTotal = raw.nLayerTotal,
                layerOffloaded = raw.nLayerOffloaded,
                weightsCpuBytes = raw.weightsCpuBytes,
                weightsGpuBytes = raw.weightsGpuBytes,
                contextUsed = raw.nCtxUsed,
                kvCacheBytes = raw.kvCacheBytes,
                computeBufferBytes = raw.computeBufferBytes,
                estimatedRamBytes = raw.totalRamBytes,
                estimatedVramBytes = raw.totalVramBytes,
                estimatedRamBytesNoGpu = raw.totalBytesNoGpu,
            )
        )
    }

    /**
     * 端末の空きメモリと比較し、ロード可能かを判定する。
     *
     * @param context ActivityManager 取得用
     * @param estimate [estimate] の結果
     * @param safetyMarginRatio 安全マージン（既定 15%）。他アプリや推論中の変動を考慮する。
     */
    fun fitCheck(
        context: Context,
        estimate: MemoryEstimate,
        safetyMarginRatio: Double = 0.15,
    ): FitCheck {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val required = estimate.estimatedRamBytes
        val requiredWithMargin = (required * (1.0 + safetyMarginRatio)).toLong()

        return FitCheck(
            fits = requiredWithMargin <= memInfo.availMem && !memInfo.lowMemory,
            availableRamBytes = memInfo.availMem,
            requiredRamBytes = required,
            requiredRamBytesWithMargin = requiredWithMargin,
        )
    }

    /** バイト数を "12.3 GB" のような人間可読な文字列に変換する。 */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return "%.2f %s".format(value, units[unitIndex])
    }
}
