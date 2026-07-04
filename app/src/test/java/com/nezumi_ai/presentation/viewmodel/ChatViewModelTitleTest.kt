package com.nezumi_ai.presentation.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatViewModelTitleTest {
    @Test
    fun `buildSessionTitle prefers user message over AI response`() {
        val title = buildSessionTitle(
            userMessage = "こんにちは、今日の天気は？",
            aiResponse = "天気は晴れです。"
        )

        assertEquals("こんにちは、今日の天気は？", title)
    }

    @Test
    fun `buildSessionTitle falls back to AI response when user message is blank`() {
        val title = buildSessionTitle(
            userMessage = "",
            aiResponse = "天気は晴れです。"
        )

        assertEquals("天気は晴れです。", title)
    }

    @Test
    fun `buildSessionTitle returns default title when both messages are blank`() {
        val title = buildSessionTitle(
            userMessage = "",
            aiResponse = ""
        )

        assertEquals(DEFAULT_SESSION_TITLE, title)
    }
}
