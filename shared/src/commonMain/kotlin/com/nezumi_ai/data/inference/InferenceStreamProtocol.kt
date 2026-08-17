package com.nezumi_ai.data.inference

object InferenceStreamProtocol {
    private const val FINAL_PREFIX = "\u0000__FINAL__\u0000"
    private const val THINK_PREFIX = "\u0000__THINK__\u0000"
    private const val TOOL_CALL_PREFIX = "\u0000__TOOL_CALL__\u0000"
    private const val TOOL_RESULT_PREFIX = "\u0000__TOOL_RESULT__\u0000"
    private const val TOOL_RESULTS_JSON_PREFIX = "\u0000__TOOL_RESULTS_JSON__\u0000"
    private const val EXECUTED_TOOLS_LIST_PREFIX = "\u0000__EXECUTED_TOOLS_LIST__\u0000"
    private const val TPS_PREFIX = "\u0000__TPS__\u0000"

    fun encodeFinal(fullText: String): String = FINAL_PREFIX + fullText

    fun decodeFinal(chunk: String): String? {
        if (!chunk.startsWith(FINAL_PREFIX)) return null
        return chunk.removePrefix(FINAL_PREFIX)
    }

    /** LiteRT-LM の thought チャンネル用デルタ（ChatViewModel で思考バッファへマージ） */
    fun encodeThinkChunk(delta: String): String = THINK_PREFIX + delta

    fun decodeThinkChunk(chunk: String): String? {
        if (!chunk.startsWith(THINK_PREFIX)) return null
        return chunk.removePrefix(THINK_PREFIX)
    }

    fun encodeToolCallChunk(toolNames: List<String>): String {
        return TOOL_CALL_PREFIX + toolNames.joinToString(",")
    }

    fun decodeToolCallChunk(chunk: String): String? {
        if (!chunk.startsWith(TOOL_CALL_PREFIX)) return null
        return chunk.removePrefix(TOOL_CALL_PREFIX)
    }

    fun encodeToolResultChunk(toolName: String, status: String): String {
        return TOOL_RESULT_PREFIX + "$toolName:$status"
    }

    fun decodeToolResultChunk(chunk: String): String? {
        if (!chunk.startsWith(TOOL_RESULT_PREFIX)) return null
        return chunk.removePrefix(TOOL_RESULT_PREFIX)
    }

    /** ツール実行結果JSON（テーブル保存用） */
    fun encodeToolResults(toolResultsJson: String?): String {
        return TOOL_RESULTS_JSON_PREFIX + (toolResultsJson ?: "[]")
    }

    fun decodeToolResults(chunk: String): String? {
        if (!chunk.startsWith(TOOL_RESULTS_JSON_PREFIX)) return null
        return chunk.removePrefix(TOOL_RESULTS_JSON_PREFIX)
    }

    /** 実行されたツール一覧（推論完了時にまとめて送出） */
    fun encodeExecutedToolsList(toolNames: List<String>): String {
        return EXECUTED_TOOLS_LIST_PREFIX + toolNames.joinToString(",")
    }

    fun decodeExecutedToolsList(chunk: String): List<String>? {
        if (!chunk.startsWith(EXECUTED_TOOLS_LIST_PREFIX)) return null
        val csv = chunk.removePrefix(EXECUTED_TOOLS_LIST_PREFIX)
        return if (csv.isBlank()) emptyList() else csv.split(",").map { it.trim() }
    }

    /** TPS情報（推論中に定期的に送出） */
    fun encodeTps(tps: Float): String {
        return TPS_PREFIX + tps.toString()
    }

    fun decodeTps(chunk: String): Float? {
        if (!chunk.startsWith(TPS_PREFIX)) return null
        return chunk.removePrefix(TPS_PREFIX).toFloatOrNull()
    }

    /**
     * Split an incoming raw chunk into ordered segments where control markers
     * (FINAL/THINK/TOOL/TPS etc.) are preserved as the start of a segment.
     * This lets callers correctly handle embedded markers that may appear
     * inside a single delivered chunk.
     */
    fun splitStreamChunks(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        val markerPattern = Regex("(\u0000__FINAL__\u0000|\u0000__THINK__\u0000|\u0000__TOOL_CALL__\u0000|\u0000__TOOL_RESULT__\u0000|\u0000__TOOL_RESULTS_JSON__\u0000|\u0000__EXECUTED_TOOLS_LIST__\u0000|\u0000__TPS__\u0000)")
        val parts = mutableListOf<String>()
        var lastIndex = 0
        val matches = markerPattern.findAll(chunk).toList()
        if (matches.isEmpty()) {
            parts.add(chunk)
            return parts
        }
        for (m in matches) {
            val start = m.range.first
            if (start > lastIndex) {
                parts.add(chunk.substring(lastIndex, start))
            }
            // Find payload region after marker: from marker start to next marker or end
            val nextStart = matches.firstOrNull { it.range.first > start }?.range?.first ?: chunk.length
            parts.add(chunk.substring(start, nextStart))
            lastIndex = nextStart
        }
        if (lastIndex < chunk.length) {
            parts.add(chunk.substring(lastIndex))
        }
        return parts
    }
}
