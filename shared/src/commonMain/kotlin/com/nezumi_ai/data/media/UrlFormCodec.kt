package com.nezumi_ai.data.media

/** Minimal application/x-www-form-urlencoded codec for attachment markers. */
internal object UrlFormCodec {
    private val hex = "0123456789ABCDEF"

    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c in 'a'.code..'z'.code ||
                c in 'A'.code..'Z'.code ||
                c in '0'.code..'9'.code ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '*'.code
            ) {
                append(c.toChar())
            } else if (c == ' '.code) {
                append('+')
            } else {
                append('%').append(hex[c ushr 4]).append(hex[c and 0x0F])
            }
        }
    }

    fun decode(value: String): String {
        val bytes = ByteArray(value.length)
        var count = 0
        var i = 0
        while (i < value.length) {
            when {
                value[i] == '+' -> {
                    bytes[count++] = ' '.code.toByte()
                    i++
                }
                value[i] == '%' && i + 2 < value.length -> {
                    val high = hexValue(value[i + 1])
                    val low = hexValue(value[i + 2])
                    require(high >= 0 && low >= 0) { "Invalid URL encoding" }
                    bytes[count++] = ((high shl 4) or low).toByte()
                    i += 3
                }
                else -> {
                    val encoded = value[i].toString().encodeToByteArray()
                    encoded.copyInto(bytes, count)
                    count += encoded.size
                    i++
                }
            }
        }
        return bytes.copyOf(count).decodeToString()
    }

    private fun hexValue(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> -1
    }
}
