package com.nezumi_ai.data.inference

/**
 * Gemma 4 のシンキング出力を分解する。
 * vLLM / transformers 系のオフライン実装（[gemma4_utils](https://github.com/vllm-project/vllm/blob/main/vllm/reasoning/gemma4_utils.py)）と同じ区切りを想定。
 */
data class Gemma4ThinkingParseResult(
    val thinking: String?,
    val answer: String
)

object Gemma4ThinkingParser {

    private const val THINKING_START = "<|channel>"
    private const val THINKING_END = "<channel|>"
    private const val THOUGHT_LABEL = "thought\n"

    /** モデルが回答末尾〜文中に連打することがある（旧 cleanAnswer は末尾1回しか剥がさなかった） */
    private val STRIP_TOKEN_SEQUENCES = listOf(
        "<turn|>",
        "<eos>",
        "<|eos|>",
        "<|eot_id|>"
    )

    fun parse(rawInput: String): Gemma4ThinkingParseResult {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        val deduped = dedupeDoubledFullText(raw)

        if (THINKING_END in deduped) {
            val parts = deduped.split(THINKING_END, limit = 2)
            val thinkingBlock = parts[0]
            val answerPart = if (parts.size > 1) sanitizeVisibleText(parts[1]) else ""

            var thinking = if (THINKING_START in thinkingBlock) {
                thinkingBlock.substringAfter(THINKING_START, "")
            } else {
                thinkingBlock
            }
            thinking = sanitizeVisibleText(stripThoughtLabel(thinking.trim()).trim())
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answerPart
            )
        }

        var answer = stripThoughtLabel(deduped)
        answer = sanitizeVisibleText(answer)
        return Gemma4ThinkingParseResult(null, answer)
    }

    /**
     * ストリーミング中: 終了タグ未到達でも thought チャンネル内のテキストを返す。
     * 特殊トークンがデコードに含まれないバックエンドでは [thinking] も [answer] も生テキスト扱いになる。
     */
    fun parseStreaming(rawInput: String): Gemma4ThinkingParseResult {
        if (rawInput.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        if (THINKING_END in rawInput) {
            val idx = rawInput.indexOf(THINKING_END)
            val thinkingBlock = rawInput.substring(0, idx)
            val afterEnd = rawInput.substring(idx + THINKING_END.length)
            var thinking = if (THINKING_START in thinkingBlock) {
                thinkingBlock.substringAfter(THINKING_START, "")
            } else {
                thinkingBlock
            }
            thinking = stripThoughtLabelStreaming(thinking) ?: ""
            return Gemma4ThinkingParseResult(
                thinking = sanitizeVisibleText(thinking).ifBlank { null },
                answer = sanitizeVisibleText(afterEnd)
            )
        }

        val startIdx = rawInput.indexOf(THINKING_START)
        if (startIdx >= 0) {
            val afterChannel = rawInput.substring(startIdx + THINKING_START.length)
            val thinking = stripThoughtLabelStreaming(afterChannel)
            return if (thinking == null) {
                Gemma4ThinkingParseResult(thinking = null, answer = "")
            } else {
                Gemma4ThinkingParseResult(
                    thinking = sanitizeVisibleText(thinking).ifBlank { null },
                    answer = ""
                )
            }
        }

        return Gemma4ThinkingParseResult(null, sanitizeVisibleText(stripThoughtLabel(rawInput)))
    }

    private fun dedupeDoubledFullText(text: String): String {
        if (text.length % 2 != 0) return text
        val half = text.length / 2
        val first = text.substring(0, half)
        val second = text.substring(half)
        return if (first == second) first else text
    }

    private fun stripThoughtLabel(text: String): String {
        return if (text.startsWith(THOUGHT_LABEL)) {
            text.substring(THOUGHT_LABEL.length)
        } else {
            text
        }
    }

    /**
     * @return null のときはまだ `thought\n` の途中の可能性があるので思考本文を確定しない。
     */
    private fun stripThoughtLabelStreaming(afterChannel: String): String? {
        if (afterChannel.startsWith(THOUGHT_LABEL)) {
            return afterChannel.substring(THOUGHT_LABEL.length)
        }
        if (afterChannel.isNotEmpty() &&
            THOUGHT_LABEL.length > afterChannel.length &&
            THOUGHT_LABEL.startsWith(afterChannel)
        ) {
            return null
        }
        return afterChannel
    }

    /**
     * 表示用テキストから Gemma / トークナイザ由来の制御トークンをすべて除去する。
     */
    fun sanitizeVisibleText(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        for (i in 0 until 64) {
            val before = t
            for (seq in STRIP_TOKEN_SEQUENCES) {
                t = t.replace(seq, "")
            }
            t = t.replace(Regex("^[ \t]+$", RegexOption.MULTILINE), "")
            if (t == before) break
        }
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
