package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/** Bitmap→JPEG バイト列変換 (Android 側の責務)。旧 ImageEncoding.encodeJpegBase64 の前半と同一。 */
object BitmapJpegEncoder {
    private const val DEFAULT_QUALITY = 85
    fun encodeJpeg(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}
