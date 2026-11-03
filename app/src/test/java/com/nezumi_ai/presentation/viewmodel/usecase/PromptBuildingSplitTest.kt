package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1/4: PromptBuildingUseCase に寄せたマークアップ・停止カード・メモリクエリ。
 */
class PromptBuildingSplitTest {

    private val promptBuilding = PromptBuildingUseCase()

    private fun msg(role: String, content: String, id: Long = 1L) = MessageEntity(
        id = id,
        sessionId = 1L,
        role = role,
        content = content,
        timestamp = id
    )

    @Test
    fun hasStreamingToolCallMarkup_detectsOpenTagsOnly() {
        // hasStreamingToolCallMarkup は開始タグのみ:
        // TOOL_CALL_OPEN / GEMMA4_TOOL_CALL_OPEN / "<function"
        assertTrue(promptBuilding.hasStreamingToolCallMarkup("hello <tool_call>"))
        assertTrue(promptBuilding.hasStreamingToolCallMarkup("prefix <|tool_call>args"))
        assertTrue(promptBuilding.hasStreamingToolCallMarkup("x <function=search>"))
        // 閉じタグ単体は対象外
        assertFalse(promptBuilding.hasStreamingToolCallMarkup("x</tool_call>"))
        assertFalse(promptBuilding.hasStreamingToolCallMarkup("plain text"))
    }

    @Test
    fun withUserStopCard_appendsStopOnce() {
        val first = promptBuilding.withUserStopCard(null, "stopped")
        assertTrue(first.contains("user_stop"))
        val second = promptBuilding.withUserStopCard(first, "stopped again")
        // 二重付与しない
        assertEquals(1, "\"user_stop\"".toRegex().findAll(second).count())
    }

    @Test
    fun appendMemoryBlockToSystemPrompt() {
        assertEquals("sys", promptBuilding.appendMemoryBlockToSystemPrompt("sys", null))
        assertEquals(
            "sys\n\n関連メモリ:\n1. a",
            promptBuilding.appendMemoryBlockToSystemPrompt("sys", "関連メモリ:\n1. a")
        )
    }

    @Test
    fun buildMemorySearchQuery_takesLastUserAndAssistant() {
        val q = promptBuilding.buildMemorySearchQuery(
            listOf(
                msg("user", "古い", 1),
                msg("assistant", "古い返答", 2),
                msg("user", "最新の質問", 3),
                msg("assistant", "最新の答え", 4)
            )
        )
        assertTrue(q.contains("ユーザー: 最新の質問"))
        assertTrue(q.contains("AI: 最新の答え"))
    }

    @Test
    fun closePartialAssistantContent_closesOddCodeFence() {
        val closed = promptBuilding.closePartialAssistantContent("```kotlin\nfun x() {")
        assertTrue(closed.trimEnd().endsWith("```"))
    }
}
