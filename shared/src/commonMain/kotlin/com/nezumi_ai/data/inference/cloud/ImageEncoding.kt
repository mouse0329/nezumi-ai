package com.nezumi_ai.data.inference.cloud

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** JPEG バイト列の Base64 化 (commonMain 版)。Bitmap 変換は app 側。NO_WRAP 相当。 */
@OptIn(ExperimentalEncodingApi::class)
object ImageEncoding {
    const val DEFAULT_MIME = "image/jpeg"

    fun encodeJpegBase64(jpegBytes: ByteArray): String = Base64.encode(jpegBytes)

    fun encodeJpegDataUri(jpegBytes: ByteArray): String =
        "data:$DEFAULT_MIME;base64,${encodeJpegBase64(jpegBytes)}"
}
