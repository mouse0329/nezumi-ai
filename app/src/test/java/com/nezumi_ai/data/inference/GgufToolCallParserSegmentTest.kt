package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GgufToolCallParser.parseSegments` の順序保存とインラインカード仕様に関する
 * regression テスト。
 *
 * UI 依頼書の要件:
 *   [本文テキスト] → [tool_call カード] → [本文テキスト] → [tool_call カード] → [本文テキスト]
 *   のシーケンスをそのままの順序で描画できること。
 */
class GgufToolCallParserSegmentTest {

    @Test
    fun parseSegments_preservesTextAndToolCallOrder_forTextToolTextToolText() {
        // モデル出力: テキスト → ツール → テキスト → ツール → テキスト
        val raw = """
            こんにちは！時間を確認します。
            <tool_call>
            {"name":"get_current_time","arguments":{}}
            </tool_call>
            続いてバッテリー残量も確認します。
            <tool_call>
            {"name":"get_battery_level","arguments":{}}
            </tool_call>
            結果をまとめてお伝えします。
        """.trimIndent()

        val segments = GgufToolCallParser.parseSegments(raw)

        // 期待: Text, ToolCall(0), Text, ToolCall(1), Text の 5 セグメント
        assertEquals(5, segments.size)
        assertTrue(segments[0] is GgufToolCallParser.Segment.TextSegment)
        assertTrue(segments[1] is GgufToolCallParser.Segment.ToolCallSegment)
        assertTrue(segments[2] is GgufToolCallParser.Segment.TextSegment)
        assertTrue(segments[3] is GgufToolCallParser.Segment.ToolCallSegment)
        assertTrue(segments[4] is GgufToolCallParser.Segment.TextSegment)

        val firstCall = segments[1] as GgufToolCallParser.Segment.ToolCallSegment
        val secondCall = segments[3] as GgufToolCallParser.Segment.ToolCallSegment
        assertEquals(0, firstCall.index)
        assertEquals(1, secondCall.index)
        assertTrue(firstCall.isComplete)
        assertTrue(secondCall.isComplete)
        assertNotNull(firstCall.toolCall)
        assertNotNull(secondCall.toolCall)
        assertEquals("get_current_time", firstCall.toolCall?.name)
        assertEquals("get_battery_level", secondCall.toolCall?.name)

        val textBetween = (segments[2] as GgufToolCallParser.Segment.TextSegment).text
        assertTrue(textBetween.contains("バッテリー残量"))
    }

    /**
     * ストリーミング途中で `</tool_call>` がまだ届いていないケース。
     * 末尾は未完タグとして Running カード相当のセグメントで残る。
     */
    @Test
    fun parseSegments_treatsUnclosedTailAsIncompleteToolCall() {
        val raw = "本文\n<tool_call>\n{\"name\":\"get_current_time\""

        val segments = GgufToolCallParser.parseSegments(raw)

        assertEquals(2, segments.size)
        assertTrue(segments[0] is GgufToolCallParser.Segment.TextSegment)
        val tail = segments[1] as GgufToolCallParser.Segment.ToolCallSegment
        assertFalse(tail.isComplete)
        assertEquals(0, tail.index)
    }

    /**
     * ツール呼び出しが 3 つ連続で、最後だけ結果が未着 (`toolResults.size == 2`) の場合でも、
     * セグメント側は 3 件のカード表示スロットを保持する必要がある。
     * (UI 側で index に基づき結果カードを引き当てるため)
     */
    @Test
    fun parseSegments_indexesEveryCallEvenWhenLaterResultsAreMissing() {
        val raw = buildString {
            append("A")
            append("<tool_call>\n{\"name\":\"get_current_time\",\"arguments\":{}}\n</tool_call>")
            append("B")
            append("<tool_call>\n{\"name\":\"get_battery_level\",\"arguments\":{}}\n</tool_call>")
            append("C")
            append("<tool_call>\n{\"name\":\"get_current_time\",\"arguments\":{}}\n</tool_call>")
            append("D")
        }

        val segments = GgufToolCallParser.parseSegments(raw)
        val calls = segments.filterIsInstance<GgufToolCallParser.Segment.ToolCallSegment>()
        assertEquals(3, calls.size)
        assertEquals(listOf(0, 1, 2), calls.map { it.index })
        calls.forEach { assertTrue(it.isComplete) }
    }
}
