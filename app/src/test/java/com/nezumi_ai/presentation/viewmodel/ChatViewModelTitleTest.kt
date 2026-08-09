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

    @Test
    fun `buildSessionTitle strips txtfile blocks so attachment body is not used as title`() {
        val title = buildSessionTitle(
            userMessage = "<txtfile>{name:\"report.pdf\",body:\"第1章 事業計画の概要…\"}</txtfile>\n要約して",
            aiResponse = ""
        )

        assertEquals("要約して", title)
    }

    @Test
    fun `buildSessionTitle strips video blocks so video metadata is not used as title`() {
        val title = buildSessionTitle(
            userMessage = "<video>Video frames sampled at 1 fps, 12 frames in total:\nimg_a.jpg: 0s\n</video>\nこの動画の内容は？",
            aiResponse = ""
        )

        assertEquals("この動画の内容は？", title)
    }

    @Test
    fun `buildSessionTitle falls back to AI response when user message is only injection blocks`() {
        val title = buildSessionTitle(
            userMessage = "<txtfile>{name:\"a.txt\",body:\"hello\"}</txtfile>",
            aiResponse = "ファイルの内容を確認しました。"
        )

        assertEquals("ファイルの内容を確認しました。", title)
    }
}
