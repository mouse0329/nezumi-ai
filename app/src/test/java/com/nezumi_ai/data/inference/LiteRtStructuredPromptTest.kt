package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiteRtStructuredPrompt] のエンコード/デコードの往復整合と、
 * マーカーによる本流/内部推論の識別を検証する回帰テスト。
 */
class LiteRtStructuredPromptTest {

    @Test
    fun `encode then decode round-trips all fields`() {
        val history = listOf(
            LiteRtStructuredPrompt.HistoryTurn(id = 1L, role = "user", content = "質問A"),
            LiteRtStructuredPrompt.HistoryTurn(id = 2L, role = "model", content = "回答A")
        )
        val encoded = LiteRtStructuredPrompt.encode(
            systemInstruction = "You are helpful.",
            history = history,
            currentMessageId = 3L,
            currentText = "次の質問"
        )
        assertTrue(LiteRtStructuredPrompt.isStructured(encoded))

        val decoded = LiteRtStructuredPrompt.decode(encoded)
        requireNotNull(decoded)
        assertEquals("You are helpful.", decoded.systemInstruction)
        assertEquals(2, decoded.history.size)
        assertEquals(1L, decoded.history[0].id)
        assertEquals("user", decoded.history[0].role)
        assertEquals("回答A", decoded.history[1].content)
        assertEquals(3L, decoded.currentMessageId)
        assertEquals("次の質問", decoded.currentText)
        assertEquals(listOf(1L, 2L), decoded.historyIds)
    }

    @Test
    fun `decode returns null for plain prompt without marker`() {
        // クラウド / 内部推論 (圧縮・メモリ抽出) はマーカーなし平文を送る。
        assertNull(LiteRtStructuredPrompt.decode("通常の平文プロンプト"))
        assertNull(LiteRtStructuredPrompt.decode("<start_of_turn>user\nhello"))
        assertFalse(LiteRtStructuredPrompt.isStructured("plain text"))
    }

    @Test
    fun `decode handles empty history and null currentId`() {
        val encoded = LiteRtStructuredPrompt.encode(
            systemInstruction = "",
            history = emptyList(),
            currentMessageId = null,
            currentText = "only current"
        )
        val decoded = LiteRtStructuredPrompt.decode(encoded)
        requireNotNull(decoded)
        assertEquals("", decoded.systemInstruction)
        assertTrue(decoded.history.isEmpty())
        assertNull(decoded.currentMessageId)
        assertEquals("only current", decoded.currentText)
    }

    @Test
    fun `decode returns null for malformed payload after marker`() {
        val malformed = LiteRtStructuredPrompt.PREFIX + "{not valid json"
        assertNull(LiteRtStructuredPrompt.decode(malformed))
    }

    @Test
    fun `history with special characters survives round-trip`() {
        // 制御タグや JSON 特殊文字を含む履歴が壊れないこと。
        val tricky = "<|think|>\n改行と \"quote\" と {brace}"
        val encoded = LiteRtStructuredPrompt.encode(
            systemInstruction = tricky,
            history = listOf(LiteRtStructuredPrompt.HistoryTurn(9L, "model", tricky)),
            currentMessageId = 10L,
            currentText = tricky
        )
        val decoded = LiteRtStructuredPrompt.decode(encoded)
        requireNotNull(decoded)
        assertEquals(tricky, decoded.systemInstruction)
        assertEquals(tricky, decoded.history[0].content)
        assertEquals(tricky, decoded.currentText)
    }
}
