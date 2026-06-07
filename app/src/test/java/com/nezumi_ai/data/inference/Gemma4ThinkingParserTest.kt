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
}
