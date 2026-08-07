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
        "<|think|>",            // Gemma4 の thinking トリガが可視出力に漏れた場合の防護
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

    /**
     * インライン tool-call カード表示用に、tool-call/tool-response タグは保持したい経路がある。
     * その場合に限り STRIP_TOKEN_SEQUENCES / removeToolTagSegments から除外するタグの集合。
     */
    private val TOOL_CALL_TAG_TOKENS = setOf(
        "<tool_call>",
        "</tool_call>",
        "<tool_response>",
        "</tool_response>"
    )

    /** llama.cpp の Gemma4 F16 バグで flood する <unusedNN> を一括除去するためのパターン。 */
    private val UNUSED_TOKEN_REGEX: Regex = Regex("<unused\\d+>")

    /**
     * @param preserveToolCallTags true のとき、返却する [Gemma4ThinkingParseResult.answer] 内の
     *   `<tool_call>`/`</tool_call>`・`<tool_response>`/`</tool_response>` タグを保持する。
     *   インライン tool-call カード表示のため、本文中のタグ出現位置を保つ必要がある呼び出し元で使う。
     *   [Gemma4ThinkingParseResult.thinking] 側は常に非保持 (thinking はタグ付きで見せる要件がないため)。
     */
    fun parse(
        rawInput: String,
        treatUnmarkedInputAsThinking: Boolean = false,
        preserveToolCallTags: Boolean = false
    ): Gemma4ThinkingParseResult {
        val raw = rawInput.trim()
        if (raw.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        // GGUF (<think>...</think>) 形式を優先チェック
        if (THINK_END in raw) {
            val parts = raw.split(THINK_END, limit = 2)
            val thinkingRaw = parts[0].removePrefix(THINK_START).trim()
            val (thinking, remainder) = splitThinkingBySpecialToken(thinkingRaw)
            val answer = sanitizeVisibleText((remainder + (parts.getOrNull(1) ?: "")).trim(), preserveToolCallTags)
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answer
            )
        }
        if (raw.startsWith(THINK_START)) {
            val thinkingRaw = raw.removePrefix(THINK_START).trim()
            val (thinking, remainder) = splitThinkingBySpecialToken(thinkingRaw)
            val answer = sanitizeVisibleText(remainder, preserveToolCallTags)
            return Gemma4ThinkingParseResult(
                thinking = thinking.ifBlank { null },
                answer = answer
            )
        }

        val deduped = dedupeDoubledFullText(raw)

        if (THINKING_END in deduped) {
            val parts = deduped.split(THINKING_END, limit = 2)
            val thinkingBlock = parts[0]
            val answerPart = if (parts.size > 1) sanitizeVisibleText(parts[1], preserveToolCallTags) else ""

            var thinking = if (THINKING_START in thinkingBlock) {
                thinkingBlock.substringAfter(THINKING_START, "")
            } else {
                thinkingBlock
            }
            thinking = sanitizeVisibleText(stripThoughtLabel(thinking.trim()).trim())
            val (finalThinking, remainder) = splitThinkingBySpecialToken(thinking)
            return Gemma4ThinkingParseResult(
                thinking = finalThinking.ifBlank { null },
                answer = sanitizeVisibleText((remainder + answerPart).trim(), preserveToolCallTags)
            )
        }

        var answer = stripThoughtLabel(deduped)
        answer = sanitizeVisibleText(answer, preserveToolCallTags)
        return if (treatUnmarkedInputAsThinking) {
            Gemma4ThinkingParseResult(answer.ifBlank { null }, "")
        } else {
            Gemma4ThinkingParseResult(null, answer)
        }
    }

    /**
     * ストリーミング中: 終了タグ未到達でも thought チャンネル内のテキストを返す。
     * 特殊トークンがデコードに含まれないバックエンドでは [thinking] も [answer] も生テキスト扱いになる。
     *
     * @param preserveToolCallTags [parse] と同様。answer 側の tool-call タグ保持有無を制御する。
     */
    fun parseStreaming(
        rawInput: String,
        treatUnmarkedInputAsThinking: Boolean = false,
        preserveToolCallTags: Boolean = false
    ): Gemma4ThinkingParseResult {
        if (rawInput.isEmpty()) return Gemma4ThinkingParseResult(null, "")

        // GGUF (<think>...</think>) 形式を優先チェック
        if (THINK_END in rawInput) {
            val idx = rawInput.indexOf(THINK_END)
            val thinking = rawInput.substring(0, idx).removePrefix(THINK_START).trim()
            val answer = sanitizeVisibleText(rawInput.substring(idx + THINK_END.length), preserveToolCallTags)
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
                answer = sanitizeVisibleText((remainder + afterEnd).trim(), preserveToolCallTags)
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
                    answer = sanitizeVisibleText(remainder, preserveToolCallTags)
                )
            }
        }

        val visible = sanitizeVisibleText(stripThoughtLabel(rawInput), preserveToolCallTags)
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
     *
     * @param preserveToolCallTags true のとき `<tool_call>`/`</tool_call>` および
     *   `<tool_response>`/`</tool_response>` の中身（およびタグ自体）を保持する。
     *   インライン tool-call カード描画のために本文中の出現位置を保存する保存パスで使う。
     *   既存の呼び出しは false のままで挙動が変わらない。
     */
    fun sanitizeVisibleText(text: String, preserveToolCallTags: Boolean = false): String {
        var t = text.trim()
        if (t.isEmpty()) return ""
        // Gemma4 GGUF (F16) で thinking ON 時に連打される <unusedNN> トークンを一括削除。
        if (UNUSED_TOKEN_REGEX.containsMatchIn(t)) {
            t = UNUSED_TOKEN_REGEX.replace(t, "").trim()
            if (t.isEmpty()) return ""
        }
        t = removeToolTagSegments(t, preserveToolCallTags)
        t = removeRedactedThinkingBlocks(t)
        t = stripLeadingControlPrefix(t, preserveToolCallTags)
        t = removeTrailingIncompleteTags(t, preserveToolCallTags)
        val original = t
        val stripSequences = if (preserveToolCallTags) {
            STRIP_TOKEN_SEQUENCES.filter { it !in TOOL_CALL_TAG_TOKENS }
        } else {
            STRIP_TOKEN_SEQUENCES
        }
        for (i in 0 until 64) {
            val before = t
            for (seq in stripSequences) {
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
    private fun stripLeadingControlPrefix(text: String, preserveToolCallTags: Boolean = false): String {
        var t = text.trimStart()
        repeat(16) {
            val before = t
            if (!preserveToolCallTags) {
                t = Regex("(?is)^<tool_call>\\s*").replace(t, "")
                t = Regex("(?is)^</tool_call>\\s*").replace(t, "")
            }
            t = Regex("(?is)^<think>\\s*</think>\\s*").replace(t, "")
            t = Regex("(?is)^<think>.*?</think>\\s*").replace(t, "")
            t = Regex("(?is)^<\\|channel>thought\\n.*?(?:<channel\\|>\\s*)").replaceFirst(t, "")
            if (t == before) return@repeat
        }
        return t
    }

    /** ストリーミング末尾の未閉じタグのみ除去（本文の後ろに付いた断片用）。 */
    private fun removeTrailingIncompleteTags(text: String, preserveToolCallTags: Boolean = false): String {
        var t = text
        val skipTags = if (preserveToolCallTags) setOf("tool_call", "tool_response") else emptySet()
        for (tag in listOf("tool_call", "tool_result", "tool_response", "tools", "redacted_thinking")) {
            if (tag in skipTags) continue
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

    private fun removeToolTagSegments(text: String, preserveToolCallTags: Boolean = false): String {
        var t = text
        val patterns = mutableListOf<Regex>()
        if (!preserveToolCallTags) {
            patterns += Regex("(?is)<tool_call>.*?</tool_call>")
            patterns += Regex("(?is)<tool_response>.*?</tool_response>")
        }
        patterns += Regex("(?is)<tool_result>.*?</tool_result>")
        patterns += Regex("(?is)<tools>.*?</tools>")
        for (pattern in patterns) {
            t = t.replace(pattern, "")
        }
        return t
    }
}
