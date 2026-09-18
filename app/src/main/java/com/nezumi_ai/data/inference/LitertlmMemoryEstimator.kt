package com.nezumi_ai.data.inference

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

/**
 * .litertlm / .task モデルを LiteRT-LM Engine でロードする *前* に
 * 必要メモリ量を見積もるモジュール。
 *
 * [MemoryEstimator]（GGUF / llama.cpp 版）が `gguf_init_from_file(no_alloc=true)` を
 * ネイティブ JNI 経由で呼んで正確な値を得るのに対し、LiteRT-LM は
 * [com.google.ai.edge.litertlm.Engine] が Google 提供のクローズドな AAR であり、
 * "ロードせずにテンソルメタ情報だけ取る" ような API が公開されていない。
 *
 * そのため本モジュールは、[parseLitertlmSectionsFromFile] で .litertlm コンテナの
 * ヘッダー（FlatBuffers）だけを読み、セクションの実バイト長（begin/end）と
 * items メタ情報から重みサイズを実測し、そこに一般的な Transformer の
 * KV キャッシュ計算式を当てはめて近似する。GGUF 版と異なり n_layer / n_embd 等の
 * 正確な数値はヘッダーからは取得できない（LlmMetadataProto セクションの protobuf 本体を
 * デコードしないと分からない）ため、[RecommendedModelCatalog] 等の既知モデル情報で
 * 補完するフォールバック方式を取る。
 *
 * 用途:
 *   - モデル選択画面で「この端末で動くか」を事前判定する（GGUF と同じ UI 導線で使う）
 *   - OOM を未然に防ぐためのロード前ガード
 */
object LitertlmMemoryEstimator {

    private const val TAG = "LitertlmMemoryEstimator"

    /**
     * KV キャッシュ計算やオーバーヘッド見積もりに使う、既知アーキテクチャのパラメータ。
     * .litertlm ヘッダーには含まれないため、モデル名やファイルサイズから
     * [guessArchParams] で推測するか、呼び出し側が明示的に渡す。
     */
    data class ArchParams(
        val nLayer: Int,
        val nEmbd: Int,
        val nHeadKv: Int,
        /** head_dim。0 の場合 nEmbd / nHeadKv から概算する。 */
        val headDim: Int = 0,
        val architecture: String = "unknown",
    )

    /** KV キャッシュの型（バイト/要素）。LiteRT-LM は基本 F16 固定だが将来の拡張に備えて残す。 */
    enum class KvCacheType(val bytesPerElement: Int) {
        F16(2),
        F32(4),
        INT8(1),
    }

    /** 見積もり時のロードパラメータ。実際の EngineConfig と近い値を渡すことで精度が上がる。 */
    data class EstimateParams(
        val maxNumTokens: Int = 4096,
        val kvCacheType: KvCacheType = KvCacheType.F16,
        /** vision/audio エンコーダも初期化する想定か（マルチモーダル分の概算を上乗せする）。 */
        val withVisionAudio: Boolean = false,
    )

    /** 見積もり結果。単位はすべてバイト。UI 表示用の MB/GB 変換は [formatBytes] で行う。 */
    data class MemoryEstimate(
        val architecture: String,
        val fileSizeBytes: Long,
        /** TFLiteModel + TFLiteWeights セクションの実測合計バイト数（重み本体の近似値）。 */
        val weightsBytes: Long,
        /** GenericBinaryData / SP_Tokenizer / HF_Tokenizer_Zlib 等、重み以外のセクション合計。 */
        val auxSectionsBytes: Long,
        val contextUsed: Int,
        /** KV キャッシュの見積もり（アーキテクチャ推定が使えた場合のみ算出。不明時は null）。 */
        val kvCacheBytes: Long?,
        /** XNNPack 等の計算バッファ・実行時オーバーヘッドの概算。 */
        val computeBufferBytes: Long,
        /** この見積もりで n_layer 等が実測でなく推測値かどうか（UI 上での精度表示に使う）。 */
        val archParamsGuessed: Boolean,
        /** mmap 想定で実際にプロセスが確保する RAM の見積もり。 */
        val estimatedRamBytes: Long,
        /** セクション一覧（デバッグ・詳細表示用）。 */
        val sections: List<LitertlmSection>,
    )

    sealed class EstimateResult {
        data class Success(val estimate: MemoryEstimate) : EstimateResult()
        data class Failure(val reason: String) : EstimateResult()
    }

    /** [fitCheck] の判定結果。[MemoryEstimator.FitCheck] と同じ形にして UI 側を共通化できるようにする。 */
    data class FitCheck(
        val fits: Boolean,
        val availableRamBytes: Long,
        val requiredRamBytes: Long,
        /** 安全マージン込みで必要な RAM（requiredRamBytes * (1 + margin)）。 */
        val requiredRamBytesWithMargin: Long,
    )

    /**
     * ファイル名 / アーキテクチャ名からおおまかな Transformer パラメータを推測する。
     * 既知モデルのみサポートし、不明な場合は null（KV キャッシュ計算をスキップする）。
     *
     * 値は各モデルの公開設定（config.json 相当）に基づくおおよその値。
     * 将来的には LlmMetadataProto セクションを protobuf デコードして実測値に置き換えるのが望ましい。
     */
    private fun guessArchParams(modelPath: String): ArchParams? {
        val name = File(modelPath).name.lowercase()
        return when {
            "gemma-4-2b" in name || "gemma4-2b" in name ->
                ArchParams(nLayer = 26, nEmbd = 2304, nHeadKv = 4, headDim = 256, architecture = "gemma-4-2b")
            "gemma-4-4b" in name || "gemma4-4b" in name ->
                ArchParams(nLayer = 34, nEmbd = 2560, nHeadKv = 8, headDim = 256, architecture = "gemma-4-4b")
            "gemma-3n-2b" in name || "gemma3n-2b" in name ->
                ArchParams(nLayer = 30, nEmbd = 2048, nHeadKv = 4, headDim = 256, architecture = "gemma-3n-2b")
            "gemma-3n-4b" in name || "gemma3n-4b" in name ->
                ArchParams(nLayer = 35, nEmbd = 2560, nHeadKv = 8, headDim = 256, architecture = "gemma-3n-4b")
            else -> null
        }
    }

    /**
     * .litertlm / .task ファイルからメモリ使用量を見積もる。モデルはロードしない（ヘッダーのみ読む・高速軽量）。
     *
     * @param modelPath .litertlm / .task ファイルの絶対パス
     * @param params ロード想定パラメータ（maxNumTokens など）
     */
    fun estimate(modelPath: String, params: EstimateParams = EstimateParams()): EstimateResult {
        val file = File(modelPath)
        if (!file.exists()) {
            return EstimateResult.Failure("model file not found: $modelPath")
        }

        val sections = try {
            parseLitertlmSectionsFromFile(modelPath)
        } catch (e: LitertlmParseException) {
            Log.e(TAG, "failed to parse .litertlm header: ${e.message}")
            return EstimateResult.Failure("header parse failed: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "unexpected error reading .litertlm header", e)
            return EstimateResult.Failure("failed to read model file: ${e.message}")
        }

        if (sections.isEmpty()) {
            return EstimateResult.Failure("no sections found in .litertlm header")
        }

        val weightsBytes = sections
            .filter { it.type == LitertlmSectionType.TFLITE_MODEL || it.type == LitertlmSectionType.TFLITE_WEIGHTS }
            .sumOf { it.sizeBytes }

        val auxSectionsBytes = sections
            .filter { it.type != LitertlmSectionType.TFLITE_MODEL && it.type != LitertlmSectionType.TFLITE_WEIGHTS }
            .sumOf { it.sizeBytes }

        // ヘッダーに model_type ヒントがあれば architecture 表示に使う。無ければファイル名から推測。
        val hintedType = sections.firstNotNullOfOrNull { it.items[HINT_MODEL_TYPE] }
        val archParams = guessArchParams(modelPath)
        val architecture = hintedType ?: archParams?.architecture ?: "unknown"

        val contextUsed = params.maxNumTokens.coerceAtLeast(1)

        val kvCacheBytes = archParams?.let { arch ->
            val headDim = if (arch.headDim > 0) arch.headDim else (arch.nEmbd / arch.nHeadKv.coerceAtLeast(1))
            // KV cache = 2 (K+V) * n_layer * n_head_kv * head_dim * n_ctx * bytes_per_element
            2L * arch.nLayer * arch.nHeadKv * headDim * contextUsed * params.kvCacheType.bytesPerElement
        }

        // XNNPack 実行バッファ・アクティベーション用の概算オーバーヘッド。
        // GGUF 版の compute_buffer と同様、ファイルサイズに対する比率 + 固定値で近似する。
        val baseComputeOverhead = 128L * 1024 * 1024 // 128MB 固定オーバーヘッド（XNNPack ワークスペース等）
        val visionAudioOverhead = if (params.withVisionAudio) 256L * 1024 * 1024 else 0L
        val computeBufferBytes = baseComputeOverhead + visionAudioOverhead

        val estimatedRamBytes = weightsBytes +
            auxSectionsBytes +
            (kvCacheBytes ?: 0L) +
            computeBufferBytes

        return EstimateResult.Success(
            MemoryEstimate(
                architecture = architecture,
                fileSizeBytes = file.length(),
                weightsBytes = weightsBytes,
                auxSectionsBytes = auxSectionsBytes,
                contextUsed = contextUsed,
                kvCacheBytes = kvCacheBytes,
                computeBufferBytes = computeBufferBytes,
                archParamsGuessed = archParams != null,
                estimatedRamBytes = estimatedRamBytes,
                sections = sections,
            )
        )
    }

    /**
     * 端末の空きメモリと比較し、ロード可能かを判定する。
     *
     * @param context ActivityManager 取得用
     * @param estimate [estimate] の結果
     * @param safetyMarginRatio 安全マージン（既定 20%）。
     *        KV キャッシュがアーキテクチャ推測に依存し GGUF 版より誤差が大きいため、
     *        [MemoryEstimator.fitCheck] の 15% よりやや広めに取る。
     */
    fun fitCheck(
        context: Context,
        estimate: MemoryEstimate,
        safetyMarginRatio: Double = 0.20,
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
