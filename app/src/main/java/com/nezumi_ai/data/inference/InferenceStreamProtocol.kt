package com.nezumi_ai.data.inference

object InferenceStreamProtocol {
    private const val FINAL_PREFIX = "\u0000__FINAL__\u0000"
    private const val THINK_PREFIX = "\u0000__THINK__\u0000"
    private const val TOOL_CALL_PREFIX = "\u0000__TOOL_CALL__\u0000"
    private const val TOOL_RESULT_PREFIX = "\u0000__TOOL_RESULT__\u0000"

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
}
