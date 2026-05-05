package com.nezumi_ai.sd

import android.graphics.Bitmap
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProgressData(
    val step: Int,
    val totalSteps: Int,
    val time: Float,
    val progress: Float = step.toFloat() / totalSteps.coerceAtLeast(1)
)

class SdEngine(private val modelPath: String) {

    private var ctxPtr: Long = 0
    var onProgressCallback: ((Int, Int, Float) -> Unit)? = null

    companion object {
        private const val TAG = "SdEngine"
        init {
            try {
                System.loadLibrary("nezumi_sd")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "nezumi_sd load failed", e)
            }
        }
    }

    // JNIから呼ばれるコールバック
    @Suppress("unused")
    private fun onProgress(step: Int, steps: Int, time: Float) {
        onProgressCallback?.invoke(step, steps, time)
    }

    private external fun nativeInit(modelPath: String, threads: Int): Long
    private external fun nativeGenerate(
        ctxPtr: Long,
        prompt: String,
        negPrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long
    ): ByteArray?

    private external fun nativeCancel(ctxPtr: Long)
    private external fun nativeGetProgress(ctxPtr: Long): Int
    private external fun nativeGetProgressTotalSteps(ctxPtr: Long): Int
    private external fun nativeGetProgressTime(ctxPtr: Long): Float
    private external fun nativeFree(ctxPtr: Long)

    fun load(threads: Int = 4) {
        release()
        Log.i(TAG, "[SD] load() starting, modelPath=$modelPath, threads=$threads")
        try {
            ctxPtr = nativeInit(modelPath, threads)
            Log.i(TAG, "[SD] nativeInit returned ctxPtr=$ctxPtr")
            if (ctxPtr == 0L) {
                Log.e(TAG, "[SD] nativeInit returned 0, initialization failed")
                error("SdEngine: nativeInit failed for $modelPath")
            }
            Log.i(TAG, "[SD] load() completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "[SD] load() exception", e)
            throw e
        }
    }

    fun cancel() {
        if (ctxPtr != 0L) {
            Log.i(TAG, "[SD] cancel() called, ctxPtr=$ctxPtr")
            nativeCancel(ctxPtr)
            Log.i(TAG, "[SD] cancel() completed")
        } else {
            Log.w(TAG, "[SD] cancel() called but ctxPtr=0")
        }
    }

    fun getCtxPtr(): Long = ctxPtr

    fun getProgress(): Int {
        if (ctxPtr == 0L) return 0
        return nativeGetProgress(ctxPtr)
    }

    fun getProgressData(): ProgressData {
        if (ctxPtr == 0L) return ProgressData(0, 0, 0.0f)
        return ProgressData(
            step = nativeGetProgress(ctxPtr),
            totalSteps = nativeGetProgressTotalSteps(ctxPtr),
            time = nativeGetProgressTime(ctxPtr)
        )
    }

    fun release() {
        if (ctxPtr != 0L) {
            nativeFree(ctxPtr)
            ctxPtr = 0L
        }
    }

    suspend fun generate(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long
    ): Bitmap? = withContext(Dispatchers.IO) {
        val p = ctxPtr
        if (p == 0L) return@withContext null
        val rgba = nativeGenerate(p, prompt, negativePrompt, width, height, steps, cfg, seed) ?: return@withContext null
        val expected = width * height * 4
        if (rgba.size < expected) {
            Log.e(TAG, "nativeGenerate: bad size ${rgba.size} expected $expected")
            return@withContext null
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bb = ByteBuffer.wrap(rgba).order(ByteOrder.nativeOrder())
        bmp.copyPixelsFromBuffer(bb)
        bmp
    }
}
