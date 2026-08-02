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

    /**
     * `content` から `<think>...(</think> | 末尾)` を剥がし、剥がしたテキストを
     * Thinking 側へ退避する。
     *
     * @return Pair(content 側に残すテキスト, thinking 側へ退避したテキスト?)
     */
    fun extractThinkingFromPartialContent(content: String): Pair<String, String?> {
        if (content.isBlank()) return content to null
        val salvaged = StringBuilder()
        var remaining = content
        // 閉鎖済み <think>...</think> ブロックを順に剥がす
        val closedPattern = Regex("(?is)<think>(.*?)</think>")
        while (true) {
            val m = closedPattern.find(remaining) ?: break
            if (salvaged.isNotEmpty()) salvaged.append("\n")
            salvaged.append(m.groupValues[1].trim())
            remaining = remaining.removeRange(m.range)
        }
        // 未閉鎖 <think>... (末尾まで) を剥がす
        val openMatch = Regex("(?i)<think>").find(remaining)
        if (openMatch != null) {
            val openIdx = openMatch.range.first
            val tail = remaining.substring(openIdx)
            val body = Regex("(?i)<think>").replaceFirst(tail, "").trim()
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
