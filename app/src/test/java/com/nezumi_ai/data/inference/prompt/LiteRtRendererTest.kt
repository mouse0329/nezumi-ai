package com.nezumi_ai.data.inference.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LiteRtRenderer] の単体テスト (計画書 Phase 4)。
 *
 * 本質的な回帰防止目的: **LiteRT-LM 経路の出力に Gemma 専用タグ
 * (`<start_of_turn>` / `<end_of_turn>`) や ChatML タグが一切混入しない**こと。
 * 旧 `PromptBuilder.buildForLiteRt` はモデル名を見ず常に Gemma ChatML を手組みしており、
 * Qwen / Llama 系 LiteRT-LM モデルに Gemma タグが混入するバグ (計画書 1.1) があった。
 * このレンダラーはタグを一切書かず構造化ペイロードのみを返す。
 */
class LiteRtRendererTest {

    private fun inputOf(
        system: String = "",
        vararg turns: Pair<ConversationTurn.Role, String>,
        enableThinking: Boolean = false,
        toolsBlock: String? = null,
        currentTurnId: Long? = null,
    ): ConversationInput = ConversationInput(
        systemInstruction = system,
        history = turns.mapIndexed { index, (role, content) ->
            ConversationTurn(id = (index + 1).toLong(), role = role, content = content)
        },
        currentTurnId = currentTurnId,
        enableThinking = enableThinking,
        toolsBlock = toolsBlock,
    )

    @Test
    fun render_splitsIntoSystemHistoryCurrent() {
        val payload = LiteRtRenderer.render(
            inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi there",
                ConversationTurn.Role.USER to "How are you?",
            )
        )
        assertEquals("You are helpful.", payload.systemInstruction)
        assertEquals(2, payload.history.size)
        assertEquals(ConversationTurn.Role.USER, payload.history[0].role)
        assertEquals("Hello", payload.history[0].content)
        assertEquals(ConversationTurn.Role.ASSISTANT, payload.history[1].role)
        assertEquals("Hi there", payload.history[1].content)
        assertEquals("How are you?", payload.currentText)
        assertEquals(3L, payload.currentTurnId)
    }

    @Test
    fun render_appendsToolsBlockIntoSystemInstruction() {
        // ツール定義は system instruction 文字列内に埋め込む (モデル再ロード回避要件)。
        val payload = LiteRtRenderer.render(
            inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hi",
                toolsBlock = "<tools>[{\"name\":\"web_search\"}]</tools>",
            )
        )
        assertEquals(
            "You are helpful.\n<tools>[{\"name\":\"web_search\"}]</tools>",
            payload.systemInstruction
        )
    }

    @Test
    fun render_usesCurrentTurnIdWhenSpecified() {
        // currentTurnId が指すターンを現ターンとし、それ以外を履歴にする。
        val payload = LiteRtRenderer.render(
            inputOf(
                "",
                ConversationTurn.Role.USER to "first",
                ConversationTurn.Role.USER to "second",
                currentTurnId = 1L,
            )
        )
        assertEquals("first", payload.currentText)
        assertEquals(1L, payload.currentTurnId)
        assertEquals(listOf("second"), payload.history.map { it.content })
    }

    @Test
    fun render_skipsSystemRoleTurnsAndBlankContents() {
        val payload = LiteRtRenderer.render(
            inputOf(
                system = "sys",
                ConversationTurn.Role.SYSTEM to "should-not-appear-in-history",
                ConversationTurn.Role.USER to "",
                ConversationTurn.Role.USER to "real question",
            )
        )
        assertTrue(payload.history.none { it.content == "should-not-appear-in-history" })
        assertTrue(payload.history.none { it.content.isBlank() })
        assertEquals("real question", payload.currentText)
    }

    @Test
    fun render_emptyHistoryProducesEmptyPayload() {
        val payload = LiteRtRenderer.render(
            ConversationInput(systemInstruction = "", history = emptyList())
        )
        assertEquals("", payload.systemInstruction)
        assertTrue(payload.history.isEmpty())
        assertEquals("", payload.currentText)
        assertNull(payload.currentTurnId)
    }

    // ---- 回帰テスト (計画書 1.1 / Phase 7 の主要目的) ----

    @Test
    fun render_neverEmitsGemmaOrChatMlTags_regardlessOfModelName() {
        // Gemma / Qwen / Llama いずれのモデルでも、構造化ペイロードの各フィールドに
        // テンプレートタグが混入しない (タグはエンジン側がモデル同梱テンプレートで付ける)。
        listOf(
            "gemma-3n-e4b.litertlm",
            "qwen3-4b.litertlm",
            "llama-3.2-3b.litertlm",
        ).forEach { _ ->
            val payload = LiteRtRenderer.render(
                inputOf(
                    system = "You are helpful.",
                    ConversationTurn.Role.USER to "Hello",
                    ConversationTurn.Role.ASSISTANT to "Hi",
                    ConversationTurn.Role.USER to "How are you?",
                    enableThinking = true,
                    toolsBlock = "<tools>[]</tools>",
                )
            )
            val all = payload.systemInstruction +
                payload.history.joinToString("") { it.content } +
                payload.currentText
            assertFalse(all.contains("<start_of_turn>"))
            assertFalse(all.contains("<end_of_turn>"))
            assertFalse(all.contains("<|im_start|>"))
            assertFalse(all.contains("<|im_end|>"))
            assertFalse(all.contains("<|think|>"))
        }
    }

    @Test
    fun render_thinkingFlagDoesNotAffectPayloadShape() {
        // thinking 制御は ConversationConfig.thinkingConfig (正規 API) の責務であり、
        // このレンダラーの出力形状には影響しない (計画書 1.2b)。
        val base = inputOf(
            system = "s",
            ConversationTurn.Role.USER to "q",
            enableThinking = false,
        )
        val withThinking = base.copy(enableThinking = true)
        val a = LiteRtRenderer.render(base)
        val b = LiteRtRenderer.render(withThinking)
        assertEquals(a.systemInstruction, b.systemInstruction)
        assertEquals(a.history, b.history)
        assertEquals(a.currentText, b.currentText)
    }
}
