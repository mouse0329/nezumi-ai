package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * クラウド経路のツールタグ抽出と、全角デリミタ正規化の regression。
 */
class CloudToolCallParserTest {

    @Test
    fun parse_detectsGenericToolCalls() {
        val raw = """
            調べます。
            <tool_call>
            {"name":"web_search","arguments":{"query":"東京 天気"}}
            </tool_call>
        """.trimIndent()
        val parsed = CloudToolCallParser.parse(raw, isGemma4 = false)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("web_search", parsed.toolCalls[0].name)
    }

    @Test
    fun parse_normalizesFullwidthToolTagsBeforeDetecting() {
        val raw = "調べます。\n＜tool_call＞\n{\"name\":\"web_search\",\"arguments\":{\"query\":\"news\"}}\n＜/tool_call＞"
        val parsed = CloudToolCallParser.parse(raw, isGemma4 = false)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("web_search", parsed.toolCalls[0].name)
    }

    @Test
    fun parseSegments_hidesToolResponseAndKeepsOrder() {
        val raw = buildString {
            append("前文\n")
            append("<tool_call>\n{\"name\":\"get_current_time\",\"arguments\":{}}\n</tool_call>\n")
            append("<tool_response>\n{\"name\":\"get_current_time\",\"content\":{\"time\":\"10:00\"}}\n</tool_response>\n")
            append("後文")
        }
        val segments = CloudToolCallParser.parseSegments(raw)
        assertEquals(3, segments.size)
        assertTrue(segments[0] is CloudToolCallParser.Segment.TextSegment)
        assertTrue(segments[1] is CloudToolCallParser.Segment.ToolCallSegment)
        assertTrue(segments[2] is CloudToolCallParser.Segment.TextSegment)
        val visible = segments
            .filterIsInstance<CloudToolCallParser.Segment.TextSegment>()
            .joinToString("") { it.text }
        assertTrue(visible.contains("前文"))
        assertTrue(visible.contains("後文"))
        assertFalse(visible.contains("tool_response"))
        assertFalse(visible.contains("10:00"))
    }

    @Test
    fun parseSegments_acceptsFullwidthDelimiters() {
        val raw = "本文\n＜tool_call＞\n{\"name\":\"get_news\",\"arguments\":{}}\n＜/tool_call＞\n以上"
        val segments = CloudToolCallParser.parseSegments(raw)
        val calls = segments.filterIsInstance<CloudToolCallParser.Segment.ToolCallSegment>()
        assertEquals(1, calls.size)
        assertTrue(calls[0].isComplete)
        assertEquals("get_news", calls[0].toolCall?.name)
    }

    @Test
    fun normalize_doesNotRewriteArbitraryFullwidthBrackets() {
        val raw = "温度は＜0℃です＞"
        assertEquals(raw, ToolCallTags.normalizeFullwidthToolTagDelimiters(raw))
    }
}
