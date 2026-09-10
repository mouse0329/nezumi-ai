package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.cloud.CloudChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CloudRenderer] の単体テスト (計画書 Phase 5)。
 *
 * 本質的な回帰防止目的: **クラウド経路に Gemma タグが混入しない**ことと、
 * **複数ターン履歴が role 配列として構造化されて送られる**こと
 * (旧経路では Gemma ChatML 込みの平文が CloudPromptSplitter で分解できず、
 *  全文が user ロール1個に潰されていた — 計画書 1.2)。
 */
class CloudRendererTest {

    private fun inputOf(
        system: String = "",
        vararg turns: Pair<ConversationTurn.Role, String>,
        toolsBlock: String? = null,
    ): ConversationInput = ConversationInput(
        systemInstruction = system,
        history = turns.mapIndexed { index, (role, content) ->
            ConversationTurn(id = (index + 1).toLong(), role = role, content = content)
        },
        toolsBlock = toolsBlock,
    )

    @Test
    fun render_producesSystemThenHistoryInOrder() {
        val messages = CloudRenderer.render(
            inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi there",
                ConversationTurn.Role.USER to "How are you?",
            )
        )
        assertEquals(4, messages.size)
        assertEquals(CloudChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals("You are helpful.", messages[0].text)
        assertEquals(CloudChatMessage.Role.USER, messages[1].role)
        assertEquals("Hello", messages[1].text)
        assertEquals(CloudChatMessage.Role.ASSISTANT, messages[2].role)
        assertEquals("Hi there", messages[2].text)
        assertEquals(CloudChatMessage.Role.USER, messages[3].role)
        assertEquals("How are you?", messages[3].text)
    }

    @Test
    fun render_appendsToolsBlockIntoSystemMessage() {
        val messages = CloudRenderer.render(
            inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hi",
                toolsBlock = "<tools>[{\"name\":\"web_search\"}]</tools>",
            )
        )
        assertEquals(CloudChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(
            "You are helpful.\n<tools>[{\"name\":\"web_search\"}]</tools>",
            messages[0].text
        )
    }

    @Test
    fun render_omitsSystemMessageWhenEmpty() {
        val messages = CloudRenderer.render(
            inputOf("", ConversationTurn.Role.USER to "Hello")
        )
        assertEquals(1, messages.size)
        assertEquals(CloudChatMessage.Role.USER, messages[0].role)
    }

    @Test
    fun render_skipsBlankTurns() {
        val messages = CloudRenderer.render(
            inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "",
                ConversationTurn.Role.USER to "World",
            )
        )
        assertEquals(2, messages.size)
        assertTrue(messages.none { it.text.isBlank() })
    }

    // ---- 回帰テスト (計画書 1.2 / Phase 7 の主要目的) ----

    @Test
    fun render_neverEmitsGemmaOrChatMlTags() {
        // クラウドモデル (Qwen / Llama 等、接続先任意) でも Gemma ChatML タグが
        // 一切出力に混入しない (テンプレートはクラウド API 側の責務)。
        val messages = CloudRenderer.render(
            inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi",
                ConversationTurn.Role.USER to "How are you?",
                toolsBlock = "<tools>[]</tools>",
            )
        )
        val all = messages.joinToString("") { it.text }
        assertFalse(all.contains("<start_of_turn>"))
        assertFalse(all.contains("<end_of_turn>"))
        assertFalse(all.contains("<|im_start|>"))
        assertFalse(all.contains("<|im_end|>"))
        assertFalse(all.contains("<|think|>"))
    }

    @Test
    fun render_multiTurnHistoryIsStructuredNotFlattened() {
        // 複数ターンが user ロール1個の平文に潰されず、各ターンが独立した
        // メッセージとして保持されること (旧クラウド経路の平文連結バグの根絶)。
        val messages = CloudRenderer.render(
            inputOf(
                "",
                ConversationTurn.Role.USER to "q1",
                ConversationTurn.Role.ASSISTANT to "a1",
                ConversationTurn.Role.USER to "q2",
                ConversationTurn.Role.ASSISTANT to "a2",
                ConversationTurn.Role.USER to "q3",
            )
        )
        assertEquals(5, messages.size)
        assertEquals(
            listOf(
                CloudChatMessage.Role.USER,
                CloudChatMessage.Role.ASSISTANT,
                CloudChatMessage.Role.USER,
                CloudChatMessage.Role.ASSISTANT,
                CloudChatMessage.Role.USER,
            ),
            messages.map { it.role }
        )
        assertEquals(listOf("q1", "a1", "q2", "a2", "q3"), messages.map { it.text })
    }

    @Test
    fun render_historySystemTurnsAreDemotedToUser() {
        // 履歴中の SYSTEM ターンは Claude 等で 400 になるため USER として送る。
        val messages = CloudRenderer.render(
            inputOf(
                system = "top",
                ConversationTurn.Role.SYSTEM to "mid-history system",
                ConversationTurn.Role.USER to "q",
            )
        )
        assertEquals(CloudChatMessage.Role.SYSTEM, messages[0].role)
        assertEquals(CloudChatMessage.Role.USER, messages[1].role)
        assertEquals("mid-history system", messages[1].text)
    }
}
