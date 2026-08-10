package com.nezumi_ai.data.inference.cloud

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * クラウド API に画像を送るための共通エンコーディング。
 *
 * ## 選択のポイント
 * - Claude / Gemini / OpenAI いずれも JPEG / PNG の base64 を受け付ける。
 * - JPEG (品質 85) が一般に一番トラフィック効率が良く、視覚的劣化も許容範囲。
 * - Base64 は改行なし (Base64.NO_WRAP)。改行入りだと LM Studio 等の一部
 *   実装で JSON 埋め込み時にトラブルの原因になる。
 *
 * ## API ごとに違う「包み方」
 * - Claude:  `{"type":"image","source":{"type":"base64","media_type":"image/jpeg","data":"<b64>"}}`
 * - Gemini:  `{"inline_data":{"mime_type":"image/jpeg","data":"<b64>"}}`
 * - OpenAI:  `{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,<b64>"}}`
 * - Ollama:  message の `images: ["<b64>"]` (生 Base64)
 * - LM Studio (OpenAI 互換): OpenAI と同形式。ただしバージョンによって
 *   `image_url.url` を data URI で受け付けず生 Base64 のみを要求するケースが
 *   あるため、[LmStudioInferenceEngine] 側でリトライフォールバックする。
 *
 * この関数群は上記の共通材料となる「JPEG base64 文字列」を返す点までを共通化する。
 */
object ImageEncoding {

    const val DEFAULT_MIME = "image/jpeg"
    private const val DEFAULT_QUALITY = 85

    /** [Bitmap] を JPEG エンコードし、改行なしの Base64 文字列で返す。 */
    fun encodeJpegBase64(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): String {
        val out = ByteArrayOutputStream()
        // Bitmap.CompressFormat.JPEG は Android 15 (API 35) 以降で
        // WEBP と比べても十分にコンパクトで、全 API 互換なため既定として採用。
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        val bytes = out.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /** OpenAI / LM Studio 用の data URI 形式 (`data:image/jpeg;base64,...`)。 */
    fun encodeJpegDataUri(bitmap: Bitmap, quality: Int = DEFAULT_QUALITY): String {
        val b64 = encodeJpegBase64(bitmap, quality)
        return "data:$DEFAULT_MIME;base64,$b64"
    }
}
