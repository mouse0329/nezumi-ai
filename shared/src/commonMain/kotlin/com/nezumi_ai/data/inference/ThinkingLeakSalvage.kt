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
