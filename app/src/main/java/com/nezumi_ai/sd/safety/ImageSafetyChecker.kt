package com.nezumi_ai.sd.safety

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.nezumi_ai.data.inference.ModelDownloadWorker
import java.io.Closeable
import java.nio.FloatBuffer

class ImageSafetyChecker(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "ImageSafetyChecker"
        private const val INPUT_SIZE = 384
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD  = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession?
    private val inputIsNhwc: Boolean  // true=NHWC, false=NCHW

    init {
        var sess: OrtSession? = null
        var nhwc = false
        runCatching {
            val file = ModelDownloadWorker.safetyModelFile(context)
            if (!file.exists() || file.length() == 0L) {
                Log.i(TAG, "safety.onnx not yet downloaded — safety checks skipped")
            } else {
                val opts = OrtSession.SessionOptions().apply {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    setIntraOpNumThreads(2)
                }
                val s = env.createSession(file.absolutePath, opts)
                // 入力形状を確認して NHWC / NCHW を自動判定
                val inputInfo = s.inputInfo.values.firstOrNull()
                val shape = (inputInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
                Log.i(TAG, "Model input shape: ${shape?.toList()}")
                // shape[1] == 3 → NCHW, shape[3] == 3 → NHWC
                nhwc = shape != null && shape.size == 4 && shape[3] == 3L
                Log.i(TAG, "Input format: ${if (nhwc) "NHWC" else "NCHW"}")
                sess = s
            }
        }.onFailure { e ->
            Log.w(TAG, "Failed to load safety.onnx: ${e.message}")
            runCatching { ModelDownloadWorker.safetyModelFile(context).delete() }
        }
        session = sess
        inputIsNhwc = nhwc
    }

    val isAvailable: Boolean get() = session != null

    fun check(bitmap: Bitmap): SafetyResult? {
        val sess = session ?: return null
        return try {
            val tensor = if (inputIsNhwc) preprocessNhwc(bitmap) else preprocessNchw(bitmap)
            val inputName = sess.inputNames.iterator().next()
            val results = sess.run(mapOf(inputName to tensor))
            val raw = results[0].value
            val scores: FloatArray = when (raw) {
                is Array<*> -> (raw[0] as? FloatArray) ?: FloatArray(0)
                is FloatArray -> raw
                else -> FloatArray(0)
            }
            tensor.close()
            results.close()
            Log.d(TAG, "Safety scores: ${scores.toList()}")
            SafetyPolicy.fromRawOutput(scores)
        } catch (e: Exception) {
            Log.e(TAG, "Safety inference failed", e)
            null
        }
    }

    /** NCHW: [1, 3, 224, 224] */
    private fun preprocessNchw(src: Bitmap): OnnxTensor {
        val pixels = getScaledPixels(src)
        val buf = FloatBuffer.allocate(1 * 3 * INPUT_SIZE * INPUT_SIZE)
        val rCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        val gCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        val bCh = FloatArray(INPUT_SIZE * INPUT_SIZE)
        for (i in pixels.indices) {
            val px = pixels[i]
            rCh[i] = (((px shr 16) and 0xFF) / 255f - MEAN[0]) / STD[0]
            gCh[i] = (((px shr 8)  and 0xFF) / 255f - MEAN[1]) / STD[1]
            bCh[i] = ((px          and 0xFF)  / 255f - MEAN[2]) / STD[2]
        }
        buf.put(rCh); buf.put(gCh); buf.put(bCh)
        buf.rewind()
        return OnnxTensor.createTensor(env, buf,
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
    }

    /** NHWC: [1, 224, 224, 3] */
    private fun preprocessNhwc(src: Bitmap): OnnxTensor {
        val pixels = getScaledPixels(src)
        val buf = FloatBuffer.allocate(1 * INPUT_SIZE * INPUT_SIZE * 3)
        for (px in pixels) {
            buf.put(((px shr 16) and 0xFF) / 255f - MEAN[0])
            buf.put(((px shr 8)  and 0xFF) / 255f - MEAN[1])
            buf.put( (px         and 0xFF)  / 255f - MEAN[2])
        }
        buf.rewind()
        return OnnxTensor.createTensor(env, buf,
            longArrayOf(1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong(), 3))
    }

    private fun getScaledPixels(src: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(src, INPUT_SIZE, INPUT_SIZE, true)
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        scaled.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        if (scaled != src) scaled.recycle()
        return pixels
    }

    override fun close() {
        runCatching { session?.close() }
        runCatching { env.close() }
    }
}
