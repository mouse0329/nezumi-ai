package com.nezumi_ai.data.inference

/**
 * 出力テキストのトークン数を文字種ごとの係数で概算する。
 *
 * - ASCII英数字: 4文字で1トークン（連続区間単位で切り上げ、最低1）
 * - 空白: 区切りとしてASCII区間をフラッシュ
 * - CJK / かな / 全角 / ハングル: 1文字1トークン
 * - 連結・ダッシュ・その他の句読点: 0.5トークン
 * - その他: 1文字1トークン
 *
 * JVM依存API（codePointAt / Character.getType / UnicodeBlock）を使わず、
 * 共通stdlibの公開API（Char.category など）のみで実装しているため commonMain に置ける。
 * 補助面（U+10000以降）の句読点区分は未対応（1文字1トークン扱い）。
 */
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
            val codePoint = codePointAt(text, index)
            val category = categoryOf(codePoint)
            val isAsciiLetterOrDigit =
                codePoint in 'A'.code..'Z'.code ||
                    codePoint in 'a'.code..'z'.code ||
                    codePoint in '0'.code..'9'.code

            when {
                isAsciiLetterOrDigit -> {
                    asciiRunLength++
                }
                isWhitespaceCodePoint(codePoint) -> {
                    flushAsciiRun()
                }
                isCjkLikeCodePoint(codePoint) -> {
                    flushAsciiRun()
                    tokens += 1f
                }
                category == CharCategory.CONNECTOR_PUNCTUATION ||
                    category == CharCategory.DASH_PUNCTUATION ||
                    category == CharCategory.OTHER_PUNCTUATION -> {
                    flushAsciiRun()
                    tokens += 0.5f
                }
                else -> {
                    flushAsciiRun()
                    tokens += 1f
                }
            }
            index += charCountOf(codePoint)
        }
        flushAsciiRun()
        return tokens
    }

    /** text[index] から始まるコードポイントを返す（サロゲートペア対応）。 */
    private fun codePointAt(text: String, index: Int): Int {
        val high = text[index]
        if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            val low = text[index + 1]
            return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
        }
        return high.code
    }

    private fun charCountOf(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

    /**
     * 公開API（Char.category）で文字カテゴリを返す。BMP外（補助面）は未対応のため null を返し、
     * 句読点扱いにはならず1文字1トークンとして数える。
     */
    private fun categoryOf(codePoint: Int): CharCategory? =
        if (codePoint < 0x10000) codePoint.toChar().category else null

    private fun isWhitespaceCodePoint(codePoint: Int): Boolean =
        codePoint < 0x10000 && codePoint.toChar().isWhitespace()

    /**
     * 旧実装の UnicodeBlock 判定と同じ範囲:
     * CJK統合漢字(本体/拡張A/拡張B)、CJK記号・句読点、ひらがな、カタカナ、
     * 半角・全角形、ハングル音節。
     */
    private fun isCjkLikeCodePoint(codePoint: Int): Boolean =
        codePoint in 0x4E00..0x9FFF ||      // CJK Unified Ideographs
            codePoint in 0x3400..0x4DBF ||  // CJK Unified Ideographs Extension A
            codePoint in 0x20000..0x2A6DF || // CJK Unified Ideographs Extension B
            codePoint in 0x3000..0x303F ||  // CJK Symbols and Punctuation
            codePoint in 0x3040..0x309F ||  // Hiragana
            codePoint in 0x30A0..0x30FF ||  // Katakana
            codePoint in 0xFF00..0xFFEF ||  // Halfwidth and Fullwidth Forms
            codePoint in 0xAC00..0xD7AF     // Hangul Syllables
}
