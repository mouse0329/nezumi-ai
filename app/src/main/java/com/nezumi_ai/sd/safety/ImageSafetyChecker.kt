package com.nezumi_ai.sd.safety

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

/**
 * Yahoo Open NSFW (ResNet-50) モデルを使用した画像セーフティチェッカー
 * 入力: 224x224, NHWC (BGR)
 * 出力: [0: Safe, 1: NSFW]
 */
class ImageSafetyChecker(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        // モデルファイル名は適宜変更してください
        val modelFile = copyAssetToCache("open_nsfw.onnx")
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

    fun check(bitmap: Bitmap): FloatArray? {
        val sess = session ?: return null

        // Yahoo Open NSFW モデルの入力サイズは 224x224
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        // 入力形式は NHWC [1, 224, 224, 3]
        val input = FloatArray(1 * 224 * 224 * 3)

        // Yahoo Open NSFW (Caffe) の平均値 (BGR順)
        val meanB = 104.0f
        val meanG = 117.0f
        val meanR = 123.0f

        var i = 0
        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val px = resized.getPixel(x, y)

                val r = ((px shr 16) and 0xFF).toFloat()
                val g = ((px shr 8) and 0xFF).toFloat()
                val b = (px and 0xFF).toFloat()

                // 仕様: BGR順に並べ、各チャンネルから平均値を引く
                input[i++] = b - meanB
                input[i++] = g - meanG
                input[i++] = r - meanR
            }
        }

        resized.recycle()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 224, 224, 3)
        )

        val inputName = sess.inputNames.first()
        val result = sess.run(mapOf(inputName to tensor))

        val outputName = sess.outputNames.first()
        val outputTensor = result[outputName].orElse(null)

        // 出力は [Safe, NSFW] の確率
        val probs = (outputTensor?.value as? Array<FloatArray>)?.get(0)
        ?: return null

        result.close()
        outputTensor?.close()
        tensor.close()

        return probs
    }
}
