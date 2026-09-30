package com.nezumi_ai.data.inference

/**
 * Bug fix(#47):
 *   Thinking 途中に停止すると、ストリーミングの persist タイミングによっては
 *   DB 上の `content` に `<think>...` (未閉鎖) や `<think>...</think>` を含む
 *   テキストが残ってしまうことがある。このまま保存すると、次回 UI 再バインドで
 *   stripGemmaTokens() / sanitizeVisibleText() が「閉じタグの無い <think>」を
 *   除去しきれず、思考本文が本文欄にそのまま漏れて表示される。
 *
 *   このファイルの純粋関数群は、停止時に content を再解析して <think> ブロックを
 *   thinkingContent 側へ退避させるためのヘルパー。ViewModel から呼び出される。
 */
object ThinkingLeakSalvage {

    /** 対応する開き/閉じタグのペア。Qwen 3.5+ の非対称タグも含む。 */
    private val THINK_TAG_PAIRS: List<Pair<String, String>> = listOf(
        ToolCallTags.THINK_OPEN to ToolCallTags.THINK_CLOSE,
        ToolCallTags.GEMMA_THINK_TRIGGER to ToolCallTags.THINK_CLOSE_ALT
    )

    /**
     * `content` からシンキングブロックを剥がし、剥がしたテキストを Thinking 側へ退避する。
     *
     * 対応フォーマット:
     *   - `<think>...</think>` / 未閉鎖 `<think>...`
     *   - `<|think|>...<|/think|>` / 未閉鎖 `<|think|>...` (Qwen 3.5+ 非対称タグ)
     *   - `<|channel>thought\n...<channel|>` / 未閉鎖 `<|channel>thought\n...` (Gemma 4)
     *
     * @return Pair(content 側に残すテキスト, thinking 側へ退避したテキスト?)
     */
    fun extractThinkingFromPartialContent(content: String): Pair<String, String?> {
        if (content.isBlank()) return content to null
        val salvaged = StringBuilder()
        var remaining = content
        for ((open, close) in THINK_TAG_PAIRS) {
            // 閉鎖済みブロックを順に剥がす
            val closedPattern = Regex("(?is)" + Regex.escape(open) + "(.*?)" + Regex.escape(close))
            while (true) {
                val m = closedPattern.find(remaining) ?: break
                if (salvaged.isNotEmpty()) salvaged.append("\n")
                salvaged.append(m.groupValues[1].trim())
                remaining = remaining.removeRange(m.range)
            }
            // 未閉鎖 (末尾まで) を剥がす
            val openMatch = Regex("(?i)" + Regex.escape(open)).find(remaining)
            if (openMatch != null) {
                val openIdx = openMatch.range.first
                val tail = remaining.substring(openIdx)
                val body = Regex("(?i)" + Regex.escape(open)).replaceFirst(tail, "").trim()
                if (body.isNotEmpty()) {
                    if (salvaged.isNotEmpty()) salvaged.append("\n")
                    salvaged.append(body)
                }
                remaining = remaining.substring(0, openIdx)
            }
        }
        // Gemma 4 channel 形式: 閉鎖ブロックを順に剥がす (thought ラベル込みで判定)
        val channelOpen = ToolCallTags.CHANNEL_OPEN + ToolCallTags.THOUGHT_LABEL
        val channelClosedPattern =
            Regex("(?is)" + Regex.escape(channelOpen) + "(.*?)" + Regex.escape(ToolCallTags.CHANNEL_CLOSE))
        while (true) {
            val m = channelClosedPattern.find(remaining) ?: break
            if (salvaged.isNotEmpty()) salvaged.append("\n")
            salvaged.append(m.groupValues[1].trim())
            remaining = remaining.removeRange(m.range)
        }
        // Gemma 4 channel 形式: 未閉鎖 tail を剥がす
        val channelOpenMatch = Regex("(?i)" + Regex.escape(channelOpen)).find(remaining)
        if (channelOpenMatch != null) {
            val openIdx = channelOpenMatch.range.first
            val body = remaining.substring(openIdx + channelOpen.length).trim()
            if (body.isNotEmpty()) {
                if (salvaged.isNotEmpty()) salvaged.append("\n")
                salvaged.append(body)
            }
            remaining = remaining.substring(0, openIdx)
        }
        val salvagedText = salvaged.toString().trim().ifBlank { null }
        return remaining.trim() to salvagedText
    }

    /**
     * 完了 FINAL の再解析で、ストリーミング中に分離できていた本文が
     * Thinking 欄へ混入した場合は、分離済みペアを優先して戻す。
     */
    fun restoreSeparatedThinkingIfFinalMerged(
        previousThinking: String?,
        previousContent: String,
        newThinking: String?,
        newContent: String
    ): Pair<String?, String> {
        val prevT = previousThinking?.trim().orEmpty()
        val prevC = previousContent.trim()
        val newT = newThinking?.trim().orEmpty()
        if (prevT.isEmpty()) return newThinking to newContent

        // ストリーミング中にツール用フォールバックで思考全文が content に
        // 入っていた場合、prevC == prevT になる。これを「本文が思考へ飲み込まれた」
        // とみなして previousContent を戻すと、停止・完了時に本文が Thinking へ残る。
        if (prevC.isNotEmpty() && prevC == prevT) {
            return newThinking to newContent
        }
        val answerSwallowedIntoThinking =
            prevC.isNotEmpty() && newContent.trim().isEmpty() && newT.contains(prevC)
        val thinkingGrewByAnswer =
            prevC.isNotEmpty() &&
                newT.contains(prevT) &&
                newT.contains(prevC) &&
                newT.length > prevT.length
        return if (answerSwallowedIntoThinking || thinkingGrewByAnswer) {
            previousThinking to previousContent
        } else {
            newThinking to newContent
        }
    }

    /**
     * 閉じタグも開きタグもない最終出力を thinking に入れたままにしない。
     * Thinking OFF、またはタグ無し短答を本文へ戻したい場合の保険。
     *
     * [keepUnmarkedAsThinking] が true（Thinking トグル ON）のときは、
     * タグが無くても先頭出力を思考として残す。Granite など閉じタグを
     * 吐かないモデルで本文へ丸ごと再注入されると、思考と本文が同一になる。
     */
    fun restoreUnmarkedAnswerIfNoThinkBoundary(
        thinking: String?,
        content: String,
        raw: String,
        spec: ChatMarkupSpec,
        implicitPrefill: Boolean,
        keepUnmarkedAsThinking: Boolean = false,
    ): Pair<String?, String> {
        if (content.isNotBlank() || thinking.isNullOrBlank()) return thinking to content
        if (keepUnmarkedAsThinking ||
            implicitPrefill ||
            spec.containsThinkingOpen(raw) ||
            spec.containsThinkingClose(raw)
        ) {
            return thinking to content
        }
        return null to thinking
    }

    /**
     * 思考本文と可視本文の重複を取り除く。
     *
     * Granite 等は閉じタグを出さず、ネイティブパーサーや完了時の raw 埋め戻しが
     * 同じ全文を content と thinking の両方へ載せることがある。
     */
    fun stripDuplicateThinkingFromContent(
        thinking: String?,
        content: String,
    ): Pair<String?, String> {
        val t = thinking?.trim().orEmpty()
        val c = content.trim()
        if (t.isEmpty() || c.isEmpty()) return thinking to content
        if (c == t) return thinking to ""
        if (c.startsWith(t)) return thinking to c.removePrefix(t).trim()
        if (t.contains(c) && t.length > c.length) return thinking to ""
        return thinking to content
    }

    /**
     * 停止時: タグ無しで content に残った思考漏れを Thinking 側へ一度だけ移す。
     * すでに Thinking にある本文は二重に足さない。
     *
     * @return Pair(content に残すテキスト, thinking)
     */
    fun resolveStopWithoutThinkTags(
        persistedContent: String,
        persistedThinking: String?,
        enableThinking: Boolean,
        spec: ChatMarkupSpec,
    ): Pair<String, String?> {
        val content = persistedContent.trim()
        val thinking = persistedThinking?.trim().orEmpty()
        if (!enableThinking) return content to persistedThinking
        if (spec.containsThinkingOpen(content) || spec.containsThinkingClose(content)) {
            return extractThinkingFromPartialContent(persistedContent).let { (c, s) ->
                c to mergeThinkingSalvage(persistedThinking, s)
            }
        }
        if (content.isEmpty()) return "" to persistedThinking
        if (thinking.isEmpty()) {
            return "" to content
        }
        if (content == thinking || thinking.contains(content) || content.contains(thinking)) {
            val keptThinking = if (content.contains(thinking) && content.length > thinking.length) content else thinking
            return "" to keptThinking
        }
        // 思考と本文が明確に別物なら混ぜない
        return content to persistedThinking
    }

    /**
     * 既存 thinkingContent と content から救出した思考本文をマージする。
     * 重複している場合は既存側を優先する。
     */
    fun mergeThinkingSalvage(existing: String?, salvaged: String?): String? {
        val e = existing?.trim().orEmpty()
        val s = salvaged?.trim().orEmpty()
        return when {
            e.isEmpty() && s.isEmpty() -> null
            e.isEmpty() -> s
            s.isEmpty() -> e
            e.contains(s) -> e
            s.contains(e) -> s
            else -> "$e\n$s"
        }
    }
}
