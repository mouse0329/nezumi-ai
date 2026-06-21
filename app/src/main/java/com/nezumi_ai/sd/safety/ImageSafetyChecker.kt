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

class ImageSafetyChecker private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession?,
    private val inputIsNhwc: Boolean,
    private val inputSize: Int
) : Closeable {

    companion object {
        private const val TAG = "ImageSafetyChecker"
        private const val DEFAULT_INPUT_SIZE = 384
        // AdamCodd/vit-base-nsfw-detector preprocessor_config.json
        private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        fun canLoad(context: Context): Boolean {
            if (!ModelDownloadWorker.isSafetyModelReady(context)) return false
            return runCatching {
                create(context).use { it.isAvailable }
            }.getOrDefault(false)
        }

        fun create(context: Context): ImageSafetyChecker {
            val env = OrtEnvironment.getEnvironment()
            var sess: OrtSession? = null
            var nhwc = false
            var inputSize = DEFAULT_INPUT_SIZE
            val file = ModelDownloadWorker.safetyModelFile(context)
            if (!file.exists() || file.length() == 0L) {
                Log.i(TAG, "safety.onnx not yet downloaded — safety checks skipped")
            } else {
                runCatching {
                    val opts = OrtSession.SessionOptions().apply {
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                        setIntraOpNumThreads(2)
                    }
                    val s = env.createSession(file.absolutePath, opts)
                    val inputInfo = s.inputInfo.values.firstOrNull()
                    val shape = (inputInfo?.info as? ai.onnxruntime.TensorInfo)?.shape
                    Log.i(TAG, "Model input shape: ${shape?.toList()}")
                    if (shape != null && shape.size == 4) {
                        nhwc = shape[3] == 3L
                        inputSize = if (nhwc) {
                            shape[1].toInt().coerceAtLeast(1)
                        } else {
                            shape[2].toInt().coerceAtLeast(1)
                        }
                    }
                    Log.i(TAG, "Input format: ${if (nhwc) "NHWC" else "NCHW"}, size=$inputSize")
                    sess = s
                }.onFailure { e ->
                    Log.w(TAG, "Failed to load safety.onnx: ${e.message}")
                    runCatching { ModelDownloadWorker.safetyModelFile(context).delete() }
                }
            }
            return ImageSafetyChecker(env, sess, nhwc, inputSize)
        }
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

    /** NCHW: [1, 3, H, W] */
    private fun preprocessNchw(src: Bitmap): OnnxTensor {
        val pixels = getScaledPixels(src)
        val buf = FloatBuffer.allocate(1 * 3 * inputSize * inputSize)
        val rCh = FloatArray(inputSize * inputSize)
        val gCh = FloatArray(inputSize * inputSize)
        val bCh = FloatArray(inputSize * inputSize)
        for (i in pixels.indices) {
            val px = pixels[i]
            rCh[i] = normalizeChannel((px shr 16) and 0xFF, 0)
            gCh[i] = normalizeChannel((px shr 8) and 0xFF, 1)
            bCh[i] = normalizeChannel(px and 0xFF, 2)
        }
        buf.put(rCh); buf.put(gCh); buf.put(bCh)
        buf.rewind()
        return OnnxTensor.createTensor(
            env,
            buf,
            longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        )
    }

    /** NHWC: [1, H, W, 3] */
    private fun preprocessNhwc(src: Bitmap): OnnxTensor {
        val pixels = getScaledPixels(src)
        val buf = FloatBuffer.allocate(1 * inputSize * inputSize * 3)
        for (px in pixels) {
            buf.put(normalizeChannel((px shr 16) and 0xFF, 0))
            buf.put(normalizeChannel((px shr 8) and 0xFF, 1))
            buf.put(normalizeChannel(px and 0xFF, 2))
        }
        buf.rewind()
        return OnnxTensor.createTensor(
            env,
            buf,
            longArrayOf(1, inputSize.toLong(), inputSize.toLong(), 3)
        )
    }

    private fun normalizeChannel(value: Int, channel: Int): Float {
        return ((value / 255f) - MEAN[channel]) / STD[channel]
    }

    private fun getScaledPixels(src: Bitmap): IntArray {
        val scaled = Bitmap.createScaledBitmap(src, inputSize, inputSize, true)
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled != src) scaled.recycle()
        return pixels
    }

    override fun close() {
        runCatching { session?.close() }
    }
}
