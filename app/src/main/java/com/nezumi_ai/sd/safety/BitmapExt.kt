package com.nezumi_ai.sd.safety

import android.graphics.Bitmap

/** Box blur — RenderScript 不使用、純粋 Kotlin 実装 */
fun Bitmap.toBlurred(radius: Int): Bitmap {
    val w = width; val h = height
    val pixels = IntArray(w * h)
    getPixels(pixels, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var r = 0; var g = 0; var b = 0; var count = 0
            for (dy in -radius..radius) {
                val ny = (y + dy).coerceIn(0, h - 1)
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val px = pixels[ny * w + nx]
                    r += (px shr 16) and 0xFF
                    g += (px shr 8)  and 0xFF
                    b +=  px         and 0xFF
                    count++
                }
            }
            out[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
        }
    }
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    result.setPixels(out, 0, w, 0, 0, w, h)
    return result
}
