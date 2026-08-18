package com.nezumi_ai.data.inference.cloud

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * クラウド API に画像を送るための共通エンコーディング (commonMain 版)。
 *
 * JPEG バイト列 (既にエンコード済み) を Base64 文字列へ変換する責務のみを持つ。
 * Bitmap → JPEG への変換は Android 依存のため app 側 (ImageEncodingAndroid) が担う。
 * Base64 は改行なし。kotlin.io.encoding.Base64 は NO_WRAP と同じ出力になる。
 */
@OptIn(ExperimentalEncodingApi::class)
object ImageEncoding {

    const val DEFAULT_MIME = "image/jpeg"

    /** JPEG バイト列を改行なしの Base64 文字列で返す。 */
    fun encodeJpegBase64(jpegBytes: ByteArray): String {
        return Base64.encode(jpegBytes)
    }

    /** OpenAI / LM Studio 用の data URI 形式 (`data:image/jpeg;base64,...`)。 */
    fun encodeJpegDataUri(jpegBytes: ByteArray): String {
        return "data:$DEFAULT_MIME;base64,${encodeJpegBase64(jpegBytes)}"
    }
}
