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
