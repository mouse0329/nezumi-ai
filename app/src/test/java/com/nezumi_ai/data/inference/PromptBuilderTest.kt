package com.nezumi_ai.data.inference

import com.nezumi_ai.data.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    @Test
    fun detectGgufFormat_usesPlainCompletionForGpt2Name() {
        val format = PromptBuilder.detectGgufFormat("/models/tiny-gpt2.gguf")

        assertEquals(PromptBuilder.GgufPromptFormat.PLAIN_COMPLETION, format)
    }

    @Test
    fun usesAssistantThinkingPrefill_isDisabledForGpt2() {
        assertFalse(PromptBuilder.usesAssistantThinkingPrefill("/models/tiny-gpt2.gguf"))
    }

    // ─── Bug fix(#43): Gemma 4 の Thinking ルーティング ─────────────────────────

    @Test
    fun detectGgufFormat_gemma4UsesGemmaChat() {
        // Bug fix(#45): Gemma 4 の Thinking は GEMMA4_CHANNEL 専用のビルド経路
        // (buildForGgufGemma) でのみ発火するので、フォーマットは GEMMA_CHAT のままにする。
        val format = PromptBuilder.detectGgufFormat("/models/gemma-4-e4b-it.gguf")
        assertEquals(PromptBuilder.GgufPromptFormat.GEMMA_CHAT, format)
    }

    @Test
    fun detectGgufFormat_gemma3UsesLegacyGemmaChat() {
        // Gemma 3 以前は従来どおり <start_of_turn> ベースの GEMMA_CHAT を維持。
        val format = PromptBuilder.detectGgufFormat("/models/gemma-3-9b-it.gguf")
        assertEquals(PromptBuilder.GgufPromptFormat.GEMMA_CHAT, format)
    }

    @Test
    fun buildForGguf_gemma4ThinkingEnabledInjectsThinkTriggerAndPrefill() {
        // Bug fix(#45): Gemma 4 + Thinking ON で system ターン内に <|think|> と、
        // model ターン直後に <think>\n プレフィルが入ることを保証する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "こん", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/gemma-4-e2b-it.gguf"),
            enableThinking = true,
            modelPath = "/models/gemma-4-e2b-it.gguf",
            sanitizeMessageContent = { it.content }
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
    fun buildForGguf_gemma4ThinkingDisabledDoesNotInjectThinkTrigger() {
        // Gemma 4 + Thinking OFF で <|think|> と <think> prefill が一切入らないことを保証する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "こん", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/gemma-4-e2b-it.gguf"),
            enableThinking = false,
            modelPath = "/models/gemma-4-e2b-it.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertFalse("Gemma 4 thinking OFF must not include <|think|>", prompt.contains("<|think|>"))
        assertFalse("Gemma 4 thinking OFF must not include <think> prefill", prompt.contains("<think>"))
    }

    @Test
    fun resolveThinkingPromptStyle_gemma4NamingVariants() {
        // "gemma4", "gemma-4", e4b, 12b-a4b, 単独 4b 表記の全てが Gemma 4 判定に入る。
        val paths = listOf(
            "/models/gemma4-9b.gguf",
            "/models/gemma-4-27b-it.gguf",
            "/models/gemma-e4b-preview.gguf",
            "/models/gemma-12b-a4b.gguf",
            "/models/gemma4b.gguf"
        )
        for (p in paths) {
            assertTrue(
                "expected Gemma4 style for $p",
                PromptBuilder.isGemma4Model(p)
            )
        }
    }

    // ─── Bug fix(#44): Qwen ソフトスイッチ判定 ───────────────────────────────

    @Test
    fun buildForGguf_qwen3_5DoesNotInjectThinkCommand() {
        // Qwen 3.5 以降は /think・/no_think 廃止。誤って注入されないことを保証する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/qwen3.5-2b-instruct.gguf"),
            enableThinking = false,
            modelPath = "/models/qwen3.5-2b-instruct.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertFalse(
            "Qwen 3.5 should not receive /think or /no_think soft-switch",
            prompt.contains("/think") || prompt.contains("/no_think")
        )
    }

    @Test
    fun buildForGguf_qwen3_5ThinkingDisabledInjectsEmptyThinkPrefill() {
        // Bug fix(#46): Qwen 3.5 + Thinking OFF では必ず空 <think>\n\n</think>\n\n を
        // assistant ターン直後に入れて chat_template の <think> 暴発を封じる。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "こん", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/unsloth_Qwen3.5-2B-GGUF__Qwen3.5-2B-IQ4_XS.gguf"),
            enableThinking = false,
            modelPath = "/models/unsloth_Qwen3.5-2B-GGUF__Qwen3.5-2B-IQ4_XS.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertTrue(
            "Qwen 3.5 OFF must end with empty <think></think> prefill",
            prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n")
        )
    }

    @Test
    fun buildForGguf_qwen3_5ThinkingEnabledInjectsThinkPrefill() {
        // Qwen 3.5 + Thinking ON で <think>\n プレフィルが入ることを保証する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "こん", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/qwen3.5-2b-instruct.gguf"),
            enableThinking = true,
            modelPath = "/models/qwen3.5-2b-instruct.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertTrue(
            "Qwen 3.5 ON must end with <think>\\n prefill",
            prompt.endsWith("<|im_start|>assistant\n<think>\n")
        )
    }

    @Test
    fun buildForGguf_qwen2_5DoesNotInjectThinkCommand() {
        // Qwen 2.5 は thinking 非対応。/think が注入されて出力が壊れないことを保証する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/qwen2.5-7b-instruct.gguf"),
            enableThinking = false,
            modelPath = "/models/qwen2.5-7b-instruct.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertFalse(
            "Qwen 2.5 should not receive /think or /no_think soft-switch",
            prompt.contains("/think") || prompt.contains("/no_think")
        )
    }

    @Test
    fun buildForGguf_qwen3InjectsThinkCommandOnlyForCompatibleGeneration() {
        // Qwen 3.0 系 (3.0〜3.4) では従来どおり /no_think を注入する。
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/qwen3-14b-instruct.gguf"),
            enableThinking = false,
            modelPath = "/models/qwen3-14b-instruct.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertTrue(
            "Qwen 3.x should receive /no_think for compatible generations",
            prompt.contains("/no_think")
        )
    }

    @Test
    fun buildForGguf_plainCompletionUsesTranscriptWithoutThinkTags() {
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L),
            MessageEntity(sessionId = 1L, role = "assistant", content = "Hi there", timestamp = 2L)
        )

        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "You are concise.",
            compressedSummary = null,
            format = PromptBuilder.detectGgufFormat("/models/tiny-gpt2.gguf"),
            enableThinking = true,
            modelPath = "/models/tiny-gpt2.gguf",
            sanitizeMessageContent = { it.content }
        )

        assertTrue(prompt.startsWith("You are concise.\n\nuser: Hello\n\nassistant: Hi there\n\nassistant:"))
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("<|im_start|>"))
    }
}
