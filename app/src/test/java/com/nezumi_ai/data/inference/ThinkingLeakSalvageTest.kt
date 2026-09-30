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
    fun `extract strips closed alt think block and salvages body`() {
        // Qwen 3.5+ の非対称タグ `<|think|>...<|/think|>` 形式。
        val input = "<|think|>Qwen3.5の思考<|/think|>本文です"
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent(input)
        assertEquals("本文です", content)
        assertEquals("Qwen3.5の思考", salvaged)
    }

    @Test
    fun `extract strips unclosed alt think tail and salvages body`() {
        // Qwen 3.5+ の非対称開きタグのみ (閉じタグ未到達) の停止ケース。
        val input = "本文前<|think|>途中の思考"
        val (content, salvaged) =
            ThinkingLeakSalvage.extractThinkingFromPartialContent(input)
        assertEquals("本文前", content)
        assertEquals("途中の思考", salvaged)
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
    fun `restore keeps stream split when final parse swallows answer into thinking`() {
        val restored = ThinkingLeakSalvage.restoreSeparatedThinkingIfFinalMerged(
            previousThinking = "User said hello. Respond friendly.",
            previousContent = "こんにちは！何かお手伝いできることがありますか？",
            newThinking = "User said hello. Respond friendly.\nこんにちは！何かお手伝いできることがありますか？",
            newContent = ""
        )
        assertEquals("User said hello. Respond friendly.", restored.first)
        assertEquals("こんにちは！何かお手伝いできることがありますか？", restored.second)
    }

    @Test
    fun `restore keeps new split when final parse did not merge`() {
        val restored = ThinkingLeakSalvage.restoreSeparatedThinkingIfFinalMerged(
            previousThinking = "thinking",
            previousContent = "hello",
            newThinking = "thinking",
            newContent = "hello world"
        )
        assertEquals("thinking", restored.first)
        assertEquals("hello world", restored.second)
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

    @Test
    fun restoreUnmarkedAnswerIfNoThinkBoundary_promotesThinkingToAnswer() {
        val spec = ChatMarkupSpec.NONE
        val restored = ThinkingLeakSalvage.restoreUnmarkedAnswerIfNoThinkBoundary(
            thinking = "短い回答",
            content = "",
            raw = "短い回答",
            spec = spec,
            implicitPrefill = false,
        )
        assertNull(restored.first)
        assertEquals("短い回答", restored.second)
    }

    @Test
    fun restoreUnmarkedAnswerIfNoThinkBoundary_keepsThinkingWhenCloseTagPresent() {
        val spec = ChatMarkupSpec.DEFAULT
        val raw = "plan</think>answer"
        val restored = ThinkingLeakSalvage.restoreUnmarkedAnswerIfNoThinkBoundary(
            thinking = "plan",
            content = "answer",
            raw = raw,
            spec = spec,
            implicitPrefill = false,
        )
        assertEquals("plan", restored.first)
        assertEquals("answer", restored.second)
    }

    @Test
    fun restoreUnmarkedAnswerIfNoThinkBoundary_keepsThinkingWhenToggleOn() {
        val spec = ChatMarkupSpec.NONE
        val restored = ThinkingLeakSalvage.restoreUnmarkedAnswerIfNoThinkBoundary(
            thinking = "User asks what a cat is.",
            content = "",
            raw = "User asks what a cat is.",
            spec = spec,
            implicitPrefill = false,
            keepUnmarkedAsThinking = true,
        )
        assertEquals("User asks what a cat is.", restored.first)
        assertEquals("", restored.second)
    }

    @Test
    fun stripDuplicateThinkingFromContent_clearsIdenticalAnswer() {
        val text = "User asks in Japanese. 猫とは哺乳類です。"
        val restored = ThinkingLeakSalvage.stripDuplicateThinkingFromContent(
            thinking = text,
            content = text,
        )
        assertEquals(text, restored.first)
        assertEquals("", restored.second)
    }

    @Test
    fun stripDuplicateThinkingFromContent_keepsAnswerSuffix() {
        val restored = ThinkingLeakSalvage.stripDuplicateThinkingFromContent(
            thinking = "first thought",
            content = "first thought\n\nvisible answer",
        )
        assertEquals("first thought", restored.first)
        assertEquals("visible answer", restored.second)
    }

    @Test
    fun restoreSeparatedThinkingIfFinalMerged_doesNotRestoreDuplicateContent() {
        val text = "User asks in Japanese. 猫とは哺乳類です。"
        val restored = ThinkingLeakSalvage.restoreSeparatedThinkingIfFinalMerged(
            previousThinking = text,
            previousContent = text,
            newThinking = text,
            newContent = "",
        )
        assertEquals(text, restored.first)
        assertEquals("", restored.second)
    }

    @Test
    fun resolveStopWithoutThinkTags_movesLeakedContentWhenThinkingEmpty() {
        val restored = ThinkingLeakSalvage.resolveStopWithoutThinkTags(
            persistedContent = "User asks in Japanese",
            persistedThinking = null,
            enableThinking = true,
            spec = ChatMarkupSpec.NONE,
        )
        assertEquals("", restored.first)
        assertEquals("User asks in Japanese", restored.second)
    }

    @Test
    fun resolveStopWithoutThinkTags_doesNotDuplicateIntoThinking() {
        val restored = ThinkingLeakSalvage.resolveStopWithoutThinkTags(
            persistedContent = "猫とは哺乳類です。",
            persistedThinking = "User asks.\n猫とは哺乳類です。",
            enableThinking = true,
            spec = ChatMarkupSpec.NONE,
        )
        assertEquals("", restored.first)
        assertEquals("User asks.\n猫とは哺乳類です。", restored.second)
    }

    @Test
    fun resolveStopWithoutThinkTags_keepsDistinctAnswerOutOfThinking() {
        val restored = ThinkingLeakSalvage.resolveStopWithoutThinkTags(
            persistedContent = "本文です",
            persistedThinking = "思考だけ",
            enableThinking = true,
            spec = ChatMarkupSpec.NONE,
        )
        assertEquals("本文です", restored.first)
        assertEquals("思考だけ", restored.second)
    }
}
