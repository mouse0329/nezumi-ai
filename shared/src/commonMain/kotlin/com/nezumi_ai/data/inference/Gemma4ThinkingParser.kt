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

    // タグ literal は [ToolCallTags] に集約済み。ここではエイリアスだけを持つ。
    private const val THINKING_START = ToolCallTags.CHANNEL_OPEN
    private const val THINKING_END = ToolCallTags.CHANNEL_CLOSE
    private const val THOUGHT_LABEL = ToolCallTags.THOUGHT_LABEL

    // llama.cpp (GGUF) 系のシンキングタグ
    private const val THINK_START = ToolCallTags.THINK_OPEN
    private const val THINK_END = ToolCallTags.THINK_CLOSE

    // Qwen 3.5+ の非対称シンキングタグ (`<|think|>...<|/think|>`)。
    // 開き側は Gemma 4 の thinking トリガと同一リテラル。
    private const val THINK_START_ALT = ToolCallTags.GEMMA_THINK_TRIGGER
    private const val THINK_END_ALT = ToolCallTags.THINK_CLOSE_ALT

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
        splitAtThinkTags(raw, streaming = false, preserveToolCallTags)?.let { return it }

        val deduped = dedupeDoubledFullText(raw)

        // Gemma 4 (<|channel>thought ... <channel|>) 形式
        splitAtChannelTags(deduped, streaming = false, preserveToolCallTags)?.let { return it }

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
        splitAtThinkTags(rawInput, streaming = true, preserveToolCallTags)?.let { return it }

        // Gemma 4 (<|channel>thought ... <channel|>) 形式
        splitAtChannelTags(rawInput, streaming = true, preserveToolCallTags)?.let { return it }

        val visible = sanitizeVisibleText(stripThoughtLabel(rawInput), preserveToolCallTags)
        return if (treatUnmarkedInputAsThinking) {
            Gemma4ThinkingParseResult(visible.ifBlank { null }, "")
        } else {
            Gemma4ThinkingParseResult(null, visible)
        }
    }

    /**
     * `<think>...</think>` 形式 (GGUF / Qwen 3 / DeepSeek-R1 / QwQ 共通) のスプリット。
     *
     * @return タグを検出して回答/思考に分割できた場合の結果。タグが無い場合は null。
     * @param streaming true のとき、開きタグしか無い状態でも thinking 側を確定して返す (streaming 挙動)。
     *   false のとき (parse 経路) は末尾から `</think>` を探し、開きタグは prefix として剥がすだけ。
     */
    private fun splitAtThinkTags(
        raw: String,
        streaming: Boolean,
        preserveToolCallTags: Boolean
    ): Gemma4ThinkingParseResult? {
        val trimmed = if (streaming) raw else raw.trim()
        if (THINK_END in trimmed) {
            return if (streaming) {
                val idx = trimmed.indexOf(THINK_END)
                val thinking = trimmed.substring(0, idx).removePrefix(THINK_START).trim()
                val answer = sanitizeVisibleText(
                    trimmed.substring(idx + THINK_END.length),
                    preserveToolCallTags
                )
                Gemma4ThinkingParseResult(thinking.ifBlank { null }, answer)
            } else {
                val parts = trimmed.split(THINK_END, limit = 2)
                val thinkingRaw = parts[0].removePrefix(THINK_START).trim()
                val (thinking, remainder) = splitThinkingBySpecialToken(thinkingRaw)
                val answer = sanitizeVisibleText(
                    (remainder + (parts.getOrNull(1) ?: "")).trim(),
                    preserveToolCallTags
                )
                Gemma4ThinkingParseResult(thinking.ifBlank { null }, answer)
            }
        }
        // Qwen 3.5+ の非対称閉じタグ `<|/think|>` で終わるブロック。
        // seeded `<think>\n...` (標準 prefill)、`<|think|>` (alt open)、および
        // prefill 自体が欠落したストリーミング中間状態 (`\n思考<|/think|>本文`) の全てを吸収するため、
        // 標準 open タグのチェックより先に、閉じタグの存在だけで判定する
        // (このタグが現れる = 未閉鎖シンキングが必ず先行する。本文側への取り込み漏れを防ぐ)。
        if (THINK_END_ALT in trimmed) {
            val idx = trimmed.indexOf(THINK_END_ALT)
            val thinking = trimmed.substring(0, idx)
                .removePrefix(THINK_START)
                .removePrefix(THINK_START_ALT)
                .trim()
            val answer = sanitizeVisibleText(
                trimmed.substring(idx + THINK_END_ALT.length),
                preserveToolCallTags
            )
            return Gemma4ThinkingParseResult(thinking.ifBlank { null }, answer)
        }
        if (trimmed.startsWith(THINK_START)) {
            val body = trimmed.removePrefix(THINK_START).trim()
            return if (streaming) {
                Gemma4ThinkingParseResult(body.ifBlank { null }, "")
            } else {
                val (thinking, remainder) = splitThinkingBySpecialToken(body)
                Gemma4ThinkingParseResult(
                    thinking = thinking.ifBlank { null },
                    answer = sanitizeVisibleText(remainder, preserveToolCallTags)
                )
            }
        }
        // Qwen 3.5+ の alt 開きタグのみ (閉じタグ未到達)。思考本文のみの場合。
        if (trimmed.startsWith(THINK_START_ALT)) {
            val body = trimmed.removePrefix(THINK_START_ALT).trim()
            return if (streaming) {
                Gemma4ThinkingParseResult(body.ifBlank { null }, "")
            } else {
                val (thinking, remainder) = splitThinkingBySpecialToken(body)
                Gemma4ThinkingParseResult(
                    thinking = thinking.ifBlank { null },
                    answer = sanitizeVisibleText(remainder, preserveToolCallTags)
                )
            }
        }
        return null
    }

    /**
     * `<|channel>thought\n...<channel|>` 形式 (Gemma 4 公式) のスプリット。
     *
     * @return タグを検出できた場合の結果。タグが無い場合は null。
     * @param streaming true のとき、開きタグ (`<|channel>`) だけで終端タグが未到達でも
     *   `thought\n` プレフィックスを検出できる限り thinking 側を返す。
     */
    private fun splitAtChannelTags(
        raw: String,
        streaming: Boolean,
        preserveToolCallTags: Boolean
    ): Gemma4ThinkingParseResult? {
        if (THINKING_END in raw) {
            val idx = raw.indexOf(THINKING_END)
            val thinkingBlock = raw.substring(0, idx)
            val afterEnd = raw.substring(idx + THINKING_END.length)
            var thinking = if (THINKING_START in thinkingBlock) {
                thinkingBlock.substringAfter(THINKING_START, "")
            } else {
                thinkingBlock
            }
            thinking = if (streaming) {
                stripThoughtLabelStreaming(thinking) ?: ""
            } else {
                sanitizeVisibleText(stripThoughtLabel(thinking.trim()).trim())
            }
            val (finalThinking, remainder) = splitThinkingBySpecialToken(thinking)
            return Gemma4ThinkingParseResult(
                thinking = sanitizeVisibleText(finalThinking).ifBlank { null },
                answer = sanitizeVisibleText((remainder + afterEnd).trim(), preserveToolCallTags)
            )
        }
        if (streaming) {
            val startIdx = raw.indexOf(THINKING_START)
            if (startIdx >= 0) {
                val afterChannel = raw.substring(startIdx + THINKING_START.length)
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
        }
        return null
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
        val splitIndex = ToolCallTags.THINKING_TERMINATOR_TOKENS
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
     *
     * `<tool_call>` / `<tool_response>` タグは意図的に保持する。会話履歴を次ターンのプロンプトへ
     * 再構築する際、タグごとそのままコンテキストに含めておかないと以下の 2 点が壊れるため：
     *   - モデル側がツール呼び出しと結果の対応関係を追えなくなる
     *   - UI 側 (GgufToolCallParser.parseSegments) が再表示時にインライン tool-call カードの
     *     位置を復元できず、履歴の該当メッセージでカードが消える
     * 思考タグ (`<think>` / `<|channel|>thought`) はここでも従来どおり除去される。
     */
    fun answerOnlyForModelContext(assistantContent: String): String {
        val t = assistantContent.trim()
        if (t.isEmpty()) return ""
        return sanitizeVisibleText(
            parse(t, preserveToolCallTags = true).answer,
            preserveToolCallTags = true
        ).trim()
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
        val stripSequences = if (preserveToolCallTags) {
            ToolCallTags.STRIP_TOKEN_SEQUENCES.filter { it !in ToolCallTags.TOOL_CALL_TAG_TOKENS }
        } else {
            ToolCallTags.STRIP_TOKEN_SEQUENCES
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
        return t.replace(Regex("\n{3,}"), "\n\n").trim()
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
                // Gemma 4 系の非対称タグも同様に先頭のみ除去する。
                t = Regex("(?is)^<\\|tool_call>\\s*").replace(t, "")
                t = Regex("(?is)^<tool_call\\|>\\s*").replace(t, "")
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
        // Gemma 4 の非対称タグ `<|tool_call>` に対応する未閉じ末尾 (`<tool_call|>` が未到達) を掃除する。
        // preserveToolCallTags=true のときはインライン tool-call カード描画のため触らない。
        if (!preserveToolCallTags) {
            val open = ToolCallTags.GEMMA4_TOOL_CALL_OPEN
            val close = ToolCallTags.GEMMA4_TOOL_CALL_CLOSE
            val start = t.lastIndexOf(open)
            if (start >= 0 && !t.substring(start).contains(close)) {
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
            // Gemma 4 (Google 公式) の非対称 tool-call タグ `<|tool_call>...<tool_call|>` も掃除対象に含める。
            patterns += Regex("(?is)<\\|tool_call>.*?<tool_call\\|>")
        }
        patterns += Regex("(?is)<tool_result>.*?</tool_result>")
        patterns += Regex("(?is)<tools>.*?</tools>")
        for (pattern in patterns) {
            t = t.replace(pattern, "")
        }
        return t
    }
}
