package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Bitmap → JPEG バイト列への変換 (Android 側の責務)。
 *
 * 旧 ImageEncoding.encodeJpegBase64 の前半部分 (JPEG エンコード) のみを担う。
 * 出力は Bitmap.compress(JPEG, quality) の生バイトであり、旧実装が Base64 化する
 * 手前の値とバイト単位で同一 (Base64 化は shared 側 ImageEncoding が行う)。
 */
object BitmapJpegEncoder {

    private const val DEFAULT_QUALITY = 85

    /** [Bitmap] を JPEG (品質 85) のバイト列にエンコードする。 */
    fun encodeJpeg(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        return out.toByteArray()
    }
}
