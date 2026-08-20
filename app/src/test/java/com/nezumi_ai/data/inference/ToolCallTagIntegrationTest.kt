package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ツールコール tag literal を [ToolCallTags] に集約したリファクタで、
 * パーサ・サニタイザ・ビルダーが「同じ literal を見に行っている」ことを結合的に確認する。
 *
 * これがあると、たとえば `<tool_call>` を `<toolcall>` に typo で書き換えたときに、
 * GgufToolCallParser / Gemma4ThinkingParser / CloudToolCallParser のどれかが壊れても
 * ここで一気に検出できる。
 */
class ToolCallTagIntegrationTest {

    @Test
    fun ggufParser_closingTagFor_matchesToolCallTagsConstants() {
        // 未完タグ補完用の閉じタグが、集約定数と完全一致すること。
        assertEquals(ToolCallTags.TOOL_CALL_CLOSE, GgufToolCallParser.closingTagFor(isGemma4 = false))
        assertEquals(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE, GgufToolCallParser.closingTagFor(isGemma4 = true))
    }

    @Test
    fun ggufParser_formatToolResults_usesToolResponseTagsFromToolCallTags() {
        // formatToolResults の返す `<tool_response>` タグが、集約定数と一致すること。
        val out = GgufToolCallParser.formatTruncatedFailureResponse("web_search")
        assertTrue(out.contains(ToolCallTags.TOOL_RESPONSE_OPEN))
        assertTrue(out.contains(ToolCallTags.TOOL_RESPONSE_CLOSE))
        // 誤って `<tool_response|>` (Gemma4 非対称形式) を出さないこと (仕様: <tool_response> は対称)。
        assertFalse(out.contains("<|tool_response>"))
        assertFalse(out.contains("<tool_response|>"))
    }

    @Test
    fun cloudParser_formatToolResults_usesSameToolResponseTagsAsGgufParser() {
        // Cloud 版と GGUF 版が同じ `<tool_response>` タグで結果を戻していることを確認。
        // (旧: literal がバラバラで、大文字小文字違いや半角違いのバグが起きていた)
        val call = ParsedToolCall(name = "get_current_time", arguments = emptyMap())
        val result = CloudToolExecutionResult(
            success = true,
            payload = mapOf("time" to "07:10")
        )
        val cloudOut = CloudToolCallParser.formatToolResults(listOf(call to result))
        assertTrue(cloudOut.contains(ToolCallTags.TOOL_RESPONSE_OPEN))
        assertTrue(cloudOut.contains(ToolCallTags.TOOL_RESPONSE_CLOSE))
    }

    @Test
    fun sanitize_removesAllStripTokensDefinedInToolCallTags() {
        // ToolCallTags.STRIP_TOKEN_SEQUENCES の全タグが、実際にサニタイズで剥がれること。
        val raw = buildString {
            append("head ")
            for (tag in ToolCallTags.STRIP_TOKEN_SEQUENCES) {
                append(tag)
            }
            append(" tail")
        }

        val cleaned = Gemma4ThinkingParser.sanitizeVisibleText(raw)
        for (tag in ToolCallTags.STRIP_TOKEN_SEQUENCES) {
            assertFalse(
                "sanitize must strip $tag but got: $cleaned",
                cleaned.contains(tag)
            )
        }
        assertTrue(cleaned.contains("head"))
        assertTrue(cleaned.contains("tail"))
    }

    @Test
    fun sanitize_preservesToolCallTagsWhenAskedTo() {
        // preserveToolCallTags=true のとき、TOOL_CALL_TAG_TOKENS の集合は残る。
        val raw = "本文 <tool_call>{...}</tool_call> 続き <|tool_call>call:x{}<tool_call|>"
        val cleaned = Gemma4ThinkingParser.sanitizeVisibleText(raw, preserveToolCallTags = true)
        for (tag in ToolCallTags.TOOL_CALL_TAG_TOKENS) {
            // 出現しないタグは対象外なので、raw に含まれていた tag のみ検査する。
            if (tag in raw) {
                assertTrue(
                    "preserveToolCallTags=true must keep $tag but got: $cleaned",
                    cleaned.contains(tag)
                )
            }
        }
    }

    @Test
    fun parseSegments_forGenericFormat_indexesAndKeepsOrder() {
        // <tool_call> を含むテキスト → セグメントの順序と index が spec 通り。
        val raw = buildString {
            append("A")
            append(ToolCallTags.TOOL_CALL_OPEN)
            append("\n{\"name\":\"get_current_time\",\"arguments\":{}}\n")
            append(ToolCallTags.TOOL_CALL_CLOSE)
            append("B")
            append(ToolCallTags.TOOL_CALL_OPEN)
            append("\n{\"name\":\"get_battery_level\",\"arguments\":{}}\n")
            append(ToolCallTags.TOOL_CALL_CLOSE)
            append("C")
        }
        val segments = GgufToolCallParser.parseSegments(raw)
        val calls = segments.filterIsInstance<GgufToolCallParser.Segment.ToolCallSegment>()
        assertEquals(2, calls.size)
        assertEquals(listOf(0, 1), calls.map { it.index })
        assertEquals("get_current_time", calls[0].toolCall?.name)
        assertEquals("get_battery_level", calls[1].toolCall?.name)
    }

    @Test
    fun parseSegments_forGemma4Format_indexesAndKeepsOrder() {
        val raw = buildString {
            append("A")
            append(ToolCallTags.GEMMA4_TOOL_CALL_OPEN)
            append("call:get_current_time{}")
            append(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE)
            append("B")
            append(ToolCallTags.GEMMA4_TOOL_CALL_OPEN)
            append("call:get_battery_level{}")
            append(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE)
            append("C")
        }
        val segments = GgufToolCallParser.parseSegments(raw)
        val calls = segments.filterIsInstance<GgufToolCallParser.Segment.ToolCallSegment>()
        assertEquals(2, calls.size)
        assertEquals(listOf(0, 1), calls.map { it.index })
    }

    @Test
    fun parseSegments_hidesToolResponseTagsFromVisibleText() {
        // <tool_response> の中身は本文に漏れないこと (spec: モデル向けメタ情報)。
        val raw = buildString {
            append("前文\n")
            append(ToolCallTags.TOOL_CALL_OPEN)
            append("\n{\"name\":\"get_current_time\",\"arguments\":{}}\n")
            append(ToolCallTags.TOOL_CALL_CLOSE)
            append("\n")
            append(ToolCallTags.TOOL_RESPONSE_OPEN)
            append("\n{\"name\":\"get_current_time\",\"content\":{\"time\":\"secret-07:10\"}}\n")
            append(ToolCallTags.TOOL_RESPONSE_CLOSE)
            append("\n後文")
        }

        val segments = GgufToolCallParser.parseSegments(raw)
        val visible = segments.filterIsInstance<GgufToolCallParser.Segment.TextSegment>()
            .joinToString(separator = "") { it.text }
        assertTrue(visible.contains("前文"))
        assertTrue(visible.contains("後文"))
        assertFalse("tool_response payload must not leak into visible text",
            visible.contains("secret-07:10"))
    }

    @Test
    fun parseToolResponseCards_parsesCardsInDeclarationOrder() {
        // <tool_response> ブロックが複数連続してもカード順が保存されること。
        val raw = buildString {
            append(ToolCallTags.TOOL_RESPONSE_OPEN)
            append("\n{\"name\":\"get_current_time\",\"content\":{\"time\":\"07:10\"}}\n")
            append(ToolCallTags.TOOL_RESPONSE_CLOSE)
            append("\n")
            append(ToolCallTags.TOOL_RESPONSE_OPEN)
            append("\n{\"name\":\"get_battery_level\",\"content\":{\"level\":\"85\"}}\n")
            append(ToolCallTags.TOOL_RESPONSE_CLOSE)
        }

        val cards = GgufToolCallParser.parseToolResponseCards(raw)
        assertEquals(2, cards.size)
        assertEquals("get_current_time", cards[0].toolName)
        assertEquals("get_battery_level", cards[1].toolName)
    }

    @Test
    fun crossFormatFallback_worksForBothGenericInGemma4ModeAndReverse() {
        // モデル判定と逆のフォーマットで来た tool_call を fallback で拾える。
        val generic = "${ToolCallTags.TOOL_CALL_OPEN}\n" +
            "{\"name\":\"web_search\",\"arguments\":{\"query\":\"x\"}}\n" +
            "${ToolCallTags.TOOL_CALL_CLOSE}"
        val gemma4 = "${ToolCallTags.GEMMA4_TOOL_CALL_OPEN}call:web_search{}${ToolCallTags.GEMMA4_TOOL_CALL_CLOSE}"

        // Gemma4 モードで generic 形式を受けても fallback で拾える。
        val r1 = GgufToolCallParser.parse(generic, isGemma4 = true)
        assertEquals(1, r1.toolCalls.size)
        assertTrue(r1.fellBackToAlternateFormat)

        // Generic モードで Gemma4 形式を受けても fallback で拾える。
        val r2 = GgufToolCallParser.parse(gemma4, isGemma4 = false)
        assertEquals(1, r2.toolCalls.size)
        assertTrue(r2.fellBackToAlternateFormat)
    }

    @Test
    fun ggufParser_hasToolCalls_detectsBothTagFamiliesRegardlessOfModelFlag() {
        // hasToolCalls は片方の tag family しか無くても true。
        assertTrue(GgufToolCallParser.hasToolCalls(
            "text ${ToolCallTags.TOOL_CALL_OPEN}{}${ToolCallTags.TOOL_CALL_CLOSE}",
            isGemma4 = false
        ))
        assertTrue(GgufToolCallParser.hasToolCalls(
            "text ${ToolCallTags.GEMMA4_TOOL_CALL_OPEN}call:x{}${ToolCallTags.GEMMA4_TOOL_CALL_CLOSE}",
            isGemma4 = false
        ))
        assertTrue(GgufToolCallParser.hasToolCalls(
            "text ${ToolCallTags.TOOL_CALL_OPEN}{}${ToolCallTags.TOOL_CALL_CLOSE}",
            isGemma4 = true
        ))
    }

    @Test
    fun cloudParser_parsesGenericAndGemma4Formats() {
        val generic = "${ToolCallTags.TOOL_CALL_OPEN}\n" +
            "{\"name\":\"web_search\",\"arguments\":{\"query\":\"x\"}}\n" +
            "${ToolCallTags.TOOL_CALL_CLOSE}"
        val gemma4 = "${ToolCallTags.GEMMA4_TOOL_CALL_OPEN}call:web_search{}${ToolCallTags.GEMMA4_TOOL_CALL_CLOSE}"

        val r1 = CloudToolCallParser.parse(generic, isGemma4 = false)
        assertEquals(1, r1.toolCalls.size)
        assertEquals("web_search", r1.toolCalls[0].name)

        val r2 = CloudToolCallParser.parse(gemma4, isGemma4 = true)
        assertEquals(1, r2.toolCalls.size)
        assertEquals("web_search", r2.toolCalls[0].name)
    }
}
