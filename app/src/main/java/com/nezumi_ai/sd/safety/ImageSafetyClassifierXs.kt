package com.nezumi_ai.sd.safety

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

/**
 * OwenElliott/image-safety-classifier-xs (SwiftFormer, timm) を使用した
 * 補助セーフティチェッカー。Open NSFW ではカバーされない NSFL(暴力・グロ)の
 * 検出を補完する目的で並列導入する。
 *
 * モデルカード記載の仕様(公式必須):
 *   - 入力テンソル名: "image"
 *   - 入力shape: [batch, 3, 224, 224]  (NCHW, RGB順)
 *   - 値域: 0-255 のままでよい (正規化はONNXグラフ内に焼き込み済み)
 *   - 出力テンソル名: "probabilities"
 *   - 出力shape: [batch, 3]  (softmax済み確率, 合計1.0)
 *   - クラス順序: ["NSFL", "NSFW", "SFW"]
 *
 * 注意: Open NSFW (BGR, 平均値減算) とは前処理が全く異なる。
 * 過去に前処理を混同して誤判定が多発した経緯があるため、
 * このクラスは ImageSafetyChecker とは完全に独立させている。
 */
class ImageSafetyClassifierXs(private val context: Context) {

    companion object {
        private const val MODEL_FILE = "image-safety-classifier-xs.onnx"
        private const val INPUT_NAME = "image"
        // モデルカード記載の順序。インデックス取り違えを防ぐため定数化する。
        private const val IDX_NSFL = 0
        private const val IDX_NSFW = 1
        private const val IDX_SFW = 2
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        val modelFile = copyAssetToCache(MODEL_FILE)
        val opts = OrtSession.SessionOptions()
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelFile.absolutePath, opts)
    }

    private fun copyAssetToCache(fileName: String): File {
        val file = File(context.cacheDir, fileName)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(fileName).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file
    }

    /**
     * @return ImageSafetyClassifierResult、モデル未初期化時は null
     */
    fun check(bitmap: Bitmap): ImageSafetyClassifierResult? {
        val sess = session ?: return null

        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        // NCHW [1, 3, 224, 224]、RGB順、0-255の値をそのまま渡す。
        // 正規化(平均・標準偏差によるスケーリング)はONNXグラフ内で行われるため、
        // ここで平均値減算やスケーリングを行ってはならない(Open NSFWと混同しないこと)。
        val channelSize = 224 * 224
        val input = FloatArray(1 * 3 * channelSize)

        var idx = 0
        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val px = resized.getPixel(x, y)
                val r = ((px shr 16) and 0xFF).toFloat()
                val g = ((px shr 8) and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()

                // NCHW: チャンネルごとに連続配置 (R plane, G plane, B plane)
                input[idx] = r
                input[channelSize + idx] = g
                input[channelSize * 2 + idx] = b
                idx++
            }
        }

        resized.recycle()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, 224, 224)
        )

        val result = sess.run(mapOf(INPUT_NAME to tensor))

        val outputName = sess.outputNames.first()
        val outputTensor = result[outputName].orElse(null)

        val probs = (outputTensor?.value as? Array<FloatArray>)?.get(0)
            ?: run {
                result.close()
                tensor.close()
                return null
            }

        result.close()
        tensor.close()

        return ImageSafetyClassifierResult(
            nsflScore = probs.getOrElse(IDX_NSFL) { 0f },
            nsfwScore = probs.getOrElse(IDX_NSFW) { 0f },
            sfwScore  = probs.getOrElse(IDX_SFW) { 1f }
        )
    }

    fun close() {
        session?.close()
        session = null
    }
}
