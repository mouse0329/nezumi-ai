package com.nezumi_ai.data.inference

import com.nezumi_ai.BuildConfig

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

    // llama.cpp (GGUF) 系のシンキングタグ
    private const val THINK_START = "<think>"
    private const val THINK_END = "</think>"

    /** モデルが回答末尾〜文中に連打することがある（旧 cleanAnswer は末尾1回しか剥がさなかった） */
    private val STRIP_TOKEN_SEQUENCES = listOf(
        "<end_of_turn>",
        "<turn|>",
        "<eos>",
        "<|eos|>",
        "<|eot_id|>",
        "<think>",
        "</think>",
        "<tool_call>",
        "</tool_call>",
        "<tool_result>",
        "</tool_result>",
        "<tools>",
        "</tools>",
        "<tool_response>",
        "</tool_response>"
    )

    fun parse(
        rawInput: String,
        treatUnmarkedInputAsThinking: Boolean = false
    ): Gemma4ThinkingParseResult {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        // GGUF (<think>...</think>) 形式を優先チェック
        if (THINK_END in raw) {
            val parts = raw.split(THINK_END, limit = 2)
            val thinkingRaw = parts[0].removePrefix(THINK_START).trim()
            val (thinking, remainder) = splitThinkingBySpecialToken(thinkingRaw)
            val answer = sanitizeVisibleText((remainder + (parts.getOrNull(1) ?: "")).trim())
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answer
            )
        }
        if (raw.startsWith(THINK_START)) {
            val thinkingRaw = raw.removePrefix(THINK_START).trim()
            val (thinking, remainder) = splitThinkingBySpecialToken(thinkingRaw)
            val answer = sanitizeVisibleText(remainder)
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answer
            )
        }

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
            val (finalThinking, remainder) = splitThinkingBySpecialToken(thinking)
            return Gemma4ThinkingParseResult(
                thinking = finalThinking.ifBlank { null },
                answer = sanitizeVisibleText((remainder + answerPart).trim())
            )
        }

        var answer = stripThoughtLabel(deduped)
        answer = sanitizeVisibleText(answer)
        return if (treatUnmarkedInputAsThinking) {
            Gemma4ThinkingParseResult(answer.ifBlank { null }, "")
        } else {
            Gemma4ThinkingParseResult(null, answer)
        }
    }

    /**
     * ストリーミング中: 終了タグ未到達でも thought チャンネル内のテキストを返す。
     * 特殊トークンがデコードに含まれないバックエンドでは [thinking] も [answer] も生テキスト扱いになる。
     */
    fun parseStreaming(
        rawInput: String,
        treatUnmarkedInputAsThinking: Boolean = false
    ): Gemma4ThinkingParseResult {
        if (rawInput.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        // GGUF (<think>...</think>) 形式を優先チェック
        if (THINK_END in rawInput) {
            val idx = rawInput.indexOf(THINK_END)
            val thinking = rawInput.substring(0, idx).removePrefix(THINK_START).trim()
            val answer = sanitizeVisibleText(rawInput.substring(idx + THINK_END.length))
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answer
            )
        }
        if (rawInput.startsWith(THINK_START)) {
            val thinking = rawInput.removePrefix(THINK_START).trim()
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = ""
            )
        }

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
            val (finalThinking, remainder) = splitThinkingBySpecialToken(thinking)
            return Gemma4ThinkingParseResult(
                thinking = sanitizeVisibleText(finalThinking).ifBlank { null },
                answer = sanitizeVisibleText((remainder + afterEnd).trim())
            )
        }

        val startIdx = rawInput.indexOf(THINKING_START)
        if (startIdx >= 0) {
            val afterChannel = rawInput.substring(startIdx + THINKING_START.length)
            val thinking = stripThoughtLabelStreaming(afterChannel)
            return if (thinking == null) {
                Gemma4ThinkingParseResult(thinking = null, answer = "")
            } else {
                val (finalThinking, remainder) = splitThinkingBySpecialToken(thinking)
                Gemma4ThinkingParseResult(
                    thinking = sanitizeVisibleText(finalThinking).ifBlank { null },
                    answer = sanitizeVisibleText(remainder)
                )
            }
        }

        val visible = sanitizeVisibleText(stripThoughtLabel(rawInput))
        return if (treatUnmarkedInputAsThinking) {
            Gemma4ThinkingParseResult(visible.ifBlank { null }, "")
        } else {
            Gemma4ThinkingParseResult(null, visible)
        }
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

    private fun splitThinkingBySpecialToken(thinking: String): Pair<String, String> {
        val splitIndex = listOf(
            "```",
            "`",
            "{",
            "}",
            "<tool_call>",
            "</tool_call>",
            "<tool_result>",
            "</tool_result>",
            "<tools>",
            "</tools>",
            "<tool_response>",
            "</tool_response>"
        )
            .mapNotNull { token -> thinking.indexOf(token).takeIf { it >= 0 } }
            .minOrNull() ?: -1
        return if (splitIndex >= 0) {
            thinking.substring(0, splitIndex).trim() to thinking.substring(splitIndex)
        } else {
            thinking.trim() to ""
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
     * モデルコンテキスト（プロンプト・圧縮入力）用。
     * DB の assistant [content] に思考タグやチャネルマーカーが残っていても、可視回答のみを返す。
     */
    fun answerOnlyForModelContext(assistantContent: String): String {
        val t = assistantContent.trim()
        if (t.isEmpty()) return ""
        return sanitizeVisibleText(parse(t).answer).trim()
    }

    /**
     * ツールラウンド継続用プロンプトからシンキングブロックのみ除去する。
     * `<tool_call>` 等のツール呼び出し記録はモデル文脈として残す。
     */
    fun stripThinkingForModelPrompt(text: String): String {
        var t = text
        while (true) {
            val start = t.indexOf(THINK_START)
            if (start < 0) break
            val end = t.indexOf(THINK_END, start)
            if (end >= 0) {
                t = t.removeRange(start, end + THINK_END.length)
            } else {
                t = t.removeRange(start, t.length)
                break
            }
        }
        if (THINKING_END in t) {
            val parts = t.split(THINKING_END, limit = 2)
            t = parts.getOrNull(1).orEmpty()
        }
        val channelStart = t.indexOf(THINKING_START)
        if (channelStart >= 0) {
            val afterChannel = t.substring(channelStart + THINKING_START.length)
            val thoughtIdx = afterChannel.indexOf(THOUGHT_LABEL)
            if (thoughtIdx >= 0) {
                val afterThought = afterChannel.substring(thoughtIdx + THOUGHT_LABEL.length)
                val endIdx = afterThought.indexOf(THINKING_END)
                t = if (endIdx >= 0) {
                    afterThought.substring(endIdx + THINKING_END.length)
                } else {
                    ""
                }
            }
        }
        return t.trim()
    }

    /**
     * 表示用テキストから Gemma / トークナイザ由来の制御トークンをすべて除去する。
     */
    fun sanitizeVisibleText(text: String): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        t = removeToolTagSegments(t)
        t = removeRedactedThinkingBlocks(t)
        t = stripLeadingControlPrefix(t)
        t = removeTrailingIncompleteTags(t)
        val original = t
        for (i in 0 until 64) {
            val before = t
            for (seq in STRIP_TOKEN_SEQUENCES) {
                t = t.replace(seq, "")
            }
            t = t.replace(Regex("^[ \t]+$", RegexOption.MULTILINE), "")
            t = t.replace(Regex("[ \t]{2,}"), " ")
            t = t.replace(Regex("[ \t]+(?=\\n)|(?<=\\n)[ \t]+"), "")
            if (t == before) break
        }
        val final = t.replace(Regex("\n{3,}"), "\n\n").trim()
        if (BuildConfig.DEBUG && original.length != final.length) {
            logDebug("Gemma4ThinkingParser", "SANITIZE: ${original.length} -> ${final.length} chars removed")
        }
        return final
    }

    private fun logDebug(tag: String, message: String) {
        try {
            android.util.Log.d(tag, message)
        } catch (_: Throwable) {
            // Android unit tests use a JVM environment where android.util.Log may not be mocked.
        }
    }

    private fun removeRedactedThinkingBlocks(text: String): String {
        var t = text
        val closed = Regex("(?is)<think>.*?</think>")
        while (closed.containsMatchIn(t)) {
            t = t.replace(closed, "")
        }
        return t
    }

    /** 先頭に付いた tool / thinking 制御ブロックを除去し、後続の回答本文は残す。 */
    private fun stripLeadingControlPrefix(text: String): String {
        var t = text.trimStart()
        repeat(16) {
            val before = t
            t = Regex("(?is)^<tool_call>\\s*").replace(t, "")
            t = Regex("(?is)^</tool_call>\\s*").replace(t, "")
            t = Regex("(?is)^<think>\\s*</think>\\s*").replace(t, "")
            t = Regex("(?is)^<think>.*?</think>\\s*").replace(t, "")
            t = Regex("(?is)^<\\|channel>thought\\n.*?(?:<channel\\|>\\s*)").replaceFirst(t, "")
            if (t == before) return@repeat
        }
        return t
    }

    /** ストリーミング末尾の未閉じタグのみ除去（本文の後ろに付いた断片用）。 */
    private fun removeTrailingIncompleteTags(text: String): String {
        var t = text
        for (tag in listOf("tool_call", "tool_result", "tool_response", "tools", "redacted_thinking")) {
            val open = "<$tag>"
            val close = "</$tag>"
            val start = t.lastIndexOf(open)
            if (start < 0) continue
            if (!t.substring(start).contains(close)) {
                t = t.removeRange(start, t.length).trimEnd()
            }
        }
        return t
    }

    private fun removeToolTagSegments(text: String): String {
        var t = text
        val patterns = listOf(
            Regex("(?is)<tool_call>.*?</tool_call>"),
            Regex("(?is)<tool_result>.*?</tool_result>"),
            Regex("(?is)<tool_response>.*?</tool_response>"),
            Regex("(?is)<tools>.*?</tools>")
        )
        for (pattern in patterns) {
            t = t.replace(pattern, "")
        }
        return t
    }
}
