package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Gemma4ThinkingParserTest {

    @Test
    fun parseStreaming_keepsUnmarkedTextAsAnswerByDefault() {
        val result = Gemma4ThinkingParser.parseStreaming("plain answer")

        assertNull(result.thinking)
        assertEquals("plain answer", result.answer)
    }

    @Test
    fun parseStreaming_treatsUnmarkedTextAsThinkingWhenRequested() {
        val result = Gemma4ThinkingParser.parseStreaming(
            rawInput = "first thought",
            treatUnmarkedInputAsThinking = true
        )

        assertEquals("first thought", result.thinking)
        assertEquals("", result.answer)
    }

    @Test
    fun parseStreaming_prefersExplicitThinkingBoundary() {
        val result = Gemma4ThinkingParser.parseStreaming(
            rawInput = "<|channel>thought\nthinking body<channel|>answer body",
            treatUnmarkedInputAsThinking = true
        )

        assertEquals("thinking body", result.thinking)
        assertEquals("answer body", result.answer)
    }

    @Test
    fun parseStreaming_truncatesThoughtOnBacktickAndKeepsAnswer() {
        val result = Gemma4ThinkingParser.parseStreaming(
            rawInput = "<|channel>thought\nfirst line`tool start{\"name\":\"foo\"}<channel|>final answer",
            treatUnmarkedInputAsThinking = true
        )

        assertEquals("first line", result.thinking)
        assertEquals("`tool start{\"name\":\"foo\"}final answer", result.answer)
    }

    @Test
    fun parseStreaming_truncatesThoughtOnBraceAndKeepsAnswer() {
        val result = Gemma4ThinkingParser.parseStreaming(
            rawInput = "<|channel>thought\nfirst line {tool} <channel|>answer",
            treatUnmarkedInputAsThinking = true
        )

        assertEquals("first line", result.thinking)
        assertEquals("{tool} answer", result.answer)
    }

    @Test
    fun sanitizeVisibleText_stripsToolCallTags() {
        val input = "Some text <tool_call>{\"name\":\"search\"}</tool_call> after"
        val output = Gemma4ThinkingParser.sanitizeVisibleText(input)

        assertEquals("Some text after", output)
    }

    @Test
    fun sanitizeVisibleText_keepsAnswerAfterLeadingToolAndThinkingTags() {
        val input = "<tool_call><think>\n\n</think>\n\n2026年 6月 20日 7時 10分 34秒（日本標準時間）です。"
        val output = Gemma4ThinkingParser.sanitizeVisibleText(input)

        assertEquals("2026年 6月 20日 7時 10分 34秒（日本標準時間）です。", output)
    }

    @Test
    fun sanitizeVisibleText_stripsPartialToolCallTags() {
        val input = "Some text <tool_call>{\"name\":\"search\""
        val output = Gemma4ThinkingParser.sanitizeVisibleText(input)

        assertEquals("Some text", output)
    }

    @Test
    fun stripThinkingForModelPrompt_removesRedactedThinkingButKeepsToolCall() {
        val input = "<think>secret</think><tool_call>{\"name\":\"search\"}</tool_call>"
        val output = Gemma4ThinkingParser.stripThinkingForModelPrompt(input)

        assertEquals("<tool_call>{\"name\":\"search\"}</tool_call>", output)
    }

    @Test
    fun stripThinkingForModelPrompt_removesUnclosedRedactedThinking() {
        val input = "<think>still thinking"
        val output = Gemma4ThinkingParser.stripThinkingForModelPrompt(input)

        assertEquals("", output)
    }
}
