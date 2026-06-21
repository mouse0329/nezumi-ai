package com.nezumi_ai.sd.safety

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.*
import java.io.File
import java.nio.FloatBuffer

class ImageSafetyChecker(private val context: Context) {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    init {
        val modelFile = copyAssetToCache("image-safety-classifier-xs.onnx")
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

        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)

        val input = FloatArray(1 * 3 * 224 * 224)

        val plane = 224 * 224
        var r = 0
        var g = plane
        var b = plane * 2

        for (y in 0 until 224) {
            for (x in 0 until 224) {
                val px = resized.getPixel(x, y)

                input[r++] = ((px shr 16) and 0xFF).toFloat()
                input[g++] = ((px shr 8) and 0xFF).toFloat()
                input[b++] = (px and 0xFF).toFloat()
            }
        }

        resized.recycle()

        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, 224, 224)
        )

        val inputName = sess.inputNames.first()
        val result = sess.run(mapOf(inputName to tensor))

        val outputName = sess.outputNames.first()
        val outputTensor = result[outputName].orElse(null)

        val probs = (outputTensor?.value as? Array<FloatArray>)?.get(0)
        ?: return null

        result.close()
        outputTensor?.close()
        tensor.close()

        return probs
    }
}
