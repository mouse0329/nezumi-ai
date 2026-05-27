package com.nezumi_ai.data.inference

object TextTokenEstimator {
    fun estimateOutputTokens(text: String): Float {
        if (text.isBlank()) return 0f

        var tokens = 0f
        var asciiRunLength = 0

        fun flushAsciiRun() {
            if (asciiRunLength > 0) {
                tokens += kotlin.math.ceil(asciiRunLength / 4.0).toFloat().coerceAtLeast(1f)
                asciiRunLength = 0
            }
        }

        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val type = Character.getType(codePoint)
            val isAsciiLetterOrDigit =
                codePoint in 'A'.code..'Z'.code ||
                    codePoint in 'a'.code..'z'.code ||
                    codePoint in '0'.code..'9'.code

            when {
                isAsciiLetterOrDigit -> {
                    asciiRunLength++
                }
                Character.isWhitespace(codePoint) -> {
                    flushAsciiRun()
                }
                isCjkLikeCodePoint(codePoint) -> {
                    flushAsciiRun()
                    tokens += 1f
                }
                type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                    type == Character.DASH_PUNCTUATION.toInt() ||
                    type == Character.OTHER_PUNCTUATION.toInt() -> {
                    flushAsciiRun()
                    tokens += 0.5f
                }
                else -> {
                    flushAsciiRun()
                    tokens += 1f
                }
            }
            index += Character.charCount(codePoint)
        }
        flushAsciiRun()
        return tokens
    }

    private fun isCjkLikeCodePoint(codePoint: Int): Boolean {
        val block = Character.UnicodeBlock.of(codePoint)
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}
