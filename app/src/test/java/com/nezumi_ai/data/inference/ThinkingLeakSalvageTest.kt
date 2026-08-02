package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Bug fix(#47) の回帰テスト。
 *   Thinking 途中に停止したとき、content に混入した <think>...</think> ないし
 *   未閉鎖 <think>... を thinkingContent 側へ退避できることを確認する。
 */
class ThinkingLeakSalvageTest {

    @Test
    fun `extract returns content unchanged when no think tag`() {
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent("こんにちは")
        assertEquals("こんにちは", content)
        assertNull(salvaged)
    }

    @Test
    fun `extract strips closed think block and salvages body`() {
        val input = "<think>ここは思考</think>本文です"
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent(input)
        assertEquals("本文です", content)
        assertEquals("ここは思考", salvaged)
    }

    @Test
    fun `extract strips unclosed think tail and salvages body`() {
        // Thinking 途中で停止したケースを再現。<think> は開いているが </think> はない。
        val input = "はじめの本文<think>途中で止められた思考"
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent(input)
        assertEquals("はじめの本文", content)
        assertEquals("途中で止められた思考", salvaged)
    }

    @Test
    fun `extract handles only unclosed think from start`() {
        // 停止時、本文がまだ何も出ておらず <think> のまま止まった状況。
        // Bug fix(#47) 仕様に沿って、content 側は空にして thinking 側へ全部退避する。
        val input = "<think>思考だけ"
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent(input)
        assertEquals("", content)
        assertEquals("思考だけ", salvaged)
    }

    @Test
    fun `merge prefers existing when it already contains salvaged`() {
        val merged = ThinkingLeakSalvage.mergeThinkingSalvage(
            existing = "既存の思考 ここに追加あり",
            salvaged = "既存の思考"
        )
        assertEquals("既存の思考 ここに追加あり", merged)
    }

    @Test
    fun `merge concatenates when both differ`() {
        val merged = ThinkingLeakSalvage.mergeThinkingSalvage(
            existing = "A",
            salvaged = "B"
        )
        assertEquals("A\nB", merged)
    }

    @Test
    fun `merge returns null when both blank`() {
        assertNull(ThinkingLeakSalvage.mergeThinkingSalvage(null, null))
        assertNull(ThinkingLeakSalvage.mergeThinkingSalvage("", "   "))
    }
}
