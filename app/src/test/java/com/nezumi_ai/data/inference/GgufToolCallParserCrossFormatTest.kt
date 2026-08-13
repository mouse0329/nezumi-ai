package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GgufToolCallParser.parse` のクロスフォーマット救済に関する regression テスト。
 *
 * モデルは学習データの影響で、指定された形式と逆のツールコール形式を出すことがある
 * (Gemma 4 が汎用 `<tool_call>` を出す / Qwen 等が `<|tool_call>` を出す)。
 * 優先形式で 1 件も確定できなかった場合に限り、もう一方の形式へ fallback する仕様を検証する。
 */
class GgufToolCallParserCrossFormatTest {

    @Test
    fun parse_gemma4Mode_fallsBackToGenericFormat() {
        val raw = """
            調べますね。
            <tool_call>
            {"name":"web_search","arguments":{"query":"東京 天気"}}
            </tool_call>
        """.trimIndent()
        val result = GgufToolCallParser.parse(raw, isGemma4 = true)
        assertEquals(1, result.toolCalls.size)
        assertEquals("web_search", result.toolCalls[0].name)
        assertTrue(result.fellBackToAlternateFormat)
    }

    @Test
    fun parse_genericMode_fallsBackToGemma4Format() {
        val raw = "<|tool_call>call:get_current_time{}<tool_call|>"
        val result = GgufToolCallParser.parse(raw, isGemma4 = false)
        assertEquals(1, result.toolCalls.size)
        assertEquals("get_current_time", result.toolCalls[0].name)
        assertTrue(result.fellBackToAlternateFormat)
    }

    @Test
    fun parse_gemma4Mode_prefersGemma4FormatWithoutFallback() {
        val raw = "<|tool_call>call:web_search{\"query\":\"test\"}<tool_call|>"
        val result = GgufToolCallParser.parse(raw, isGemma4 = true)
        assertEquals(1, result.toolCalls.size)
        assertFalse(result.fellBackToAlternateFormat)
    }

    @Test
    fun parse_noToolCall_returnsPrimaryResultWithoutFallback() {
        val raw = "ツールを使わない普通の回答です。"
        val r1 = GgufToolCallParser.parse(raw, isGemma4 = true)
        val r2 = GgufToolCallParser.parse(raw, isGemma4 = false)
        assertTrue(r1.toolCalls.isEmpty())
        assertFalse(r1.fellBackToAlternateFormat)
        assertTrue(r2.toolCalls.isEmpty())
        assertFalse(r2.fellBackToAlternateFormat)
    }

    @Test
    fun hasToolCalls_detectsBothFormatsRegardlessOfModelFlag() {
        val gemma4Style = "<|tool_call>call:web_search{\"query\":\"a\"}<tool_call|>"
        val genericStyle = "<tool_call>{\"name\":\"web_search\",\"arguments\":{}}</tool_call>"
        assertTrue(GgufToolCallParser.hasToolCalls(gemma4Style, isGemma4 = false))
        assertTrue(GgufToolCallParser.hasToolCalls(gemma4Style, isGemma4 = true))
        assertTrue(GgufToolCallParser.hasToolCalls(genericStyle, isGemma4 = true))
        assertTrue(GgufToolCallParser.hasToolCalls(genericStyle, isGemma4 = false))
    }
}
