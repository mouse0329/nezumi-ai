package com.nezumi_ai.data.inference.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GgufRenderer] の単体テスト (計画書 Phase 3)。
 *
 * 旧 `PromptBuilderTest` の GGUF 経路テストの移行先。GemmaChat / ChatMl /
 * PlainCompletion / Llama3 各書式と、thinking 制御 (Gemma4 / Qwen 世代別) の
 * 注入挙動が旧 `PromptBuilder.buildForGguf` と一致することを担保する。
 */
class GgufRendererTest {

    private fun inputOf(
        system: String = "",
        vararg turns: Pair<ConversationTurn.Role, String>,
        enableThinking: Boolean = false,
        toolsBlock: String? = null,
    ): ConversationInput = ConversationInput(
        systemInstruction = system,
        history = turns.mapIndexed { index, (role, content) ->
            ConversationTurn(id = (index + 1).toLong(), role = role, content = content)
        },
        enableThinking = enableThinking,
        toolsBlock = toolsBlock,
    )

    // ---- GemmaChat ----

    @Test
    fun gemmaChat_rendersGemmaTags() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
            ),
            format = PromptFormat.GemmaChat,
            modelPathOrName = "gemma-3-4b-it.gguf",
        )
        assertTrue(prompt.contains("<start_of_turn>user\nYou are helpful."))
        assertTrue(prompt.contains("<start_of_turn>user\nHello\n<end_of_turn>"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun gemmaChat_doesNotLeakRawUserAssistantPrefix() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi",
                ConversationTurn.Role.USER to "How are you?",
            ),
            format = PromptFormat.GemmaChat,
            modelPathOrName = "gemma-2-2b-it.gguf",
        )
        assertFalse(Regex("(?m)^user:\\s").containsMatchIn(prompt))
        assertFalse(Regex("(?m)^assistant:").containsMatchIn(prompt))
        assertTrue(prompt.contains("<start_of_turn>model\nHi\n<end_of_turn>"))
    }

    @Test
    fun gemmaChat_gemma4ThinkingEnabledInjectsTriggerAndPrefill() {
        // Bug fix(#45): Gemma 4 + Thinking ON で system ターン内に <|think|> と、
        // model ターン直後に <think>\n プレフィルが入る。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "こん",
                enableThinking = true,
            ),
            format = PromptFormat.GemmaChat,
            modelPathOrName = "gemma-4-e2b-it.gguf",
        )
        assertTrue(
            "Gemma 4 thinking ON must contain <|think|> in system turn",
            prompt.contains("<start_of_turn>user\n<|think|>")
        )
        assertTrue(
            "Gemma 4 thinking ON must end with <start_of_turn>model\\n<think>\\n prefill",
            prompt.endsWith("<start_of_turn>model\n<think>\n")
        )
    }

    @Test
    fun gemmaChat_gemma4ThinkingDisabledInjectsNothing() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "こん",
                enableThinking = false,
            ),
            format = PromptFormat.GemmaChat,
            modelPathOrName = "gemma-4-e2b-it.gguf",
        )
        assertFalse(prompt.contains("<|think|>"))
        assertFalse(prompt.contains("<think>"))
    }

    @Test
    fun gemmaChat_gemma3ThinkingEnabledUsesGlobalPrefixOnly() {
        // Gemma 3 (GEMMA_PREFIX): 先頭に <|think|>\n を1度だけ。system ターン内注入はしない。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "You are helpful.",
                ConversationTurn.Role.USER to "Hi",
                enableThinking = true,
            ),
            format = PromptFormat.GemmaChat,
            modelPathOrName = "gemma-3-4b-it.gguf",
        )
        assertTrue(prompt.startsWith("<|think|>\n"))
        assertFalse(prompt.contains("<start_of_turn>user\n<|think|>"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    // ---- ChatMl ----

    @Test
    fun chatMl_rendersChatMlTags() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi",
                ConversationTurn.Role.USER to "How are you?",
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "mistral-7b.gguf",
        )
        assertTrue(prompt.contains("<|im_start|>system\nYou are helpful.\n<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nHello\n<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\nHi\n<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertFalse(Regex("(?m)^user:\\s").containsMatchIn(prompt))
    }

    @Test
    fun chatMl_qwen3InjectsNoThinkCommandOnLastUserTurn() {
        // Qwen 3.0-3.4 (QWEN_COMMAND) + Thinking OFF → 直近 user ターン末尾に /no_think。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                enableThinking = false,
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "qwen3-14b-instruct.gguf",
        )
        assertTrue(prompt.contains("Hello\n/no_think"))
        // OFF 時は空 <think></think> プレフィルも入る (QWEN_COMMAND の OFF 分岐)
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun chatMl_qwen35DoesNotInjectThinkCommand() {
        // Qwen 3.5+ は /think・/no_think 廃止。誤って注入されないこと。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                enableThinking = false,
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "qwen3.5-2b-instruct.gguf",
        )
        assertFalse(prompt.lines().any { it == "/think" || it == "/no_think" })
        // OFF 時は空 <think></think> プレフィル (公式 non-thinking jinja 相当)
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun chatMl_qwen35ThinkingEnabledInjectsThinkPrefill() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "こん",
                enableThinking = true,
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "qwen3.5-2b-instruct.gguf",
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n"))
    }

    @Test
    fun chatMl_qwen25InjectsNoThinkingControl() {
        // Qwen 2.5 は thinking 非対応 (ASSISTANT_TAG)。/think も prefill も入らない。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                enableThinking = false,
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "qwen2.5-7b-instruct.gguf",
        )
        assertFalse(prompt.contains("/think"))
        assertFalse(prompt.contains("/no_think"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun chatMl_lastUserTurnAppearsExactlyOnce() {
        // Bug fix(#48) 回帰: 最後の user ターンが二重展開されないこと。
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "question-A",
                ConversationTurn.Role.ASSISTANT to "answer-A",
                ConversationTurn.Role.USER to "question-B",
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "generic.gguf",
        )
        assertEquals(1, Regex("question-B").findAll(prompt).count())
    }

    // ---- PlainCompletion ----

    @Test
    fun plainCompletion_usesTranscriptWithoutThinkTags() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                system = "You are concise.",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "Hi there",
                enableThinking = true,
            ),
            format = PromptFormat.PlainCompletion,
            modelPathOrName = "tiny-gpt2.gguf",
        )
        assertTrue(prompt.startsWith("You are concise.\n\nuser: Hello\n\nassistant: Hi there\n\nassistant:"))
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("<|im_start|>"))
        assertFalse(prompt.contains("<start_of_turn>"))
    }

    // ---- Llama3 ----

    @Test
    fun llama3_rendersHeaderIdFormat() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hello",
            ),
            format = PromptFormat.Llama3,
            modelPathOrName = "llama-3.2-3b-instruct.gguf",
        )
        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>\n\nYou are helpful.<|eot_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
        assertFalse(prompt.contains("<|im_start|>"))
    }

    // ---- CustomJinja フォールバック ----

    @Test
    fun customJinja_fallsBackToSpecifiedFormat() {
        val prompt = GgufRenderer.render(
            input = inputOf("", ConversationTurn.Role.USER to "Hello"),
            format = PromptFormat.CustomJinja(templateId = "chatml", fallback = PromptFormat.ChatMl),
            modelPathOrName = "mistral-7b.gguf",
        )
        assertTrue(prompt.contains("<|im_start|>user\nHello"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    // ---- ツールブロック / 画像説明 ----

    @Test
    fun toolsBlockIsAppendedToSystemTurn() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                system = "You are helpful.",
                ConversationTurn.Role.USER to "Hi",
                toolsBlock = "<tools>[{\"name\":\"web_search\"}]</tools>",
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "mistral-7b.gguf",
        )
        assertTrue(
            prompt.contains("<|im_start|>system\nYou are helpful.\n<tools>[{\"name\":\"web_search\"}]</tools>\n<|im_end|>")
        )
    }

    @Test
    fun blankTurnsAreSkipped() {
        val prompt = GgufRenderer.render(
            input = inputOf(
                "",
                ConversationTurn.Role.USER to "Hello",
                ConversationTurn.Role.ASSISTANT to "",
                ConversationTurn.Role.USER to "World",
            ),
            format = PromptFormat.ChatMl,
            modelPathOrName = "mistral-7b.gguf",
        )
        assertFalse(prompt.contains("<|im_start|>assistant\n\n<|im_end|>"))
        assertTrue(prompt.contains("World"))
    }
}
