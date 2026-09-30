package com.nezumi_ai.data.inference

import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ThinkingPromptStyle
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ToolCallFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkupSpecTest {

    @Test
    fun extract_qwenSoftSwitchTemplate_usesCommandStyle() {
        val template = """
            {% if enable_thinking %}
            {{ message.content }} /think
            {% else %}
            {{ message.content }} /no_think
            {% endif %}
            <think>
            </think>
        """.trimIndent()

        val spec = ChatMarkupSpec.fromChatTemplate(template)
        assertEquals(ThinkingPromptStyle.QWEN_COMMAND, spec.thinkingStyle)
        assertTrue(spec.supportsThinking)
        assertTrue(spec.containsThinkingOpen("<think>abc"))
        assertTrue(spec.containsThinkingClose("abc</think>answer"))
    }

    @Test
    fun extract_gemma4Template_usesChannelStyleAndGemmaToolTags() {
        val template = """
            {% if enable_thinking %}<|think|>{% endif %}
            <|channel>thought
            reasoning
            <channel|>
            <|tool_call>call:search{"q":"x"}<tool_call|>
        """.trimIndent()

        val spec = ChatMarkupSpec.fromChatTemplate(template)
        assertEquals(ThinkingPromptStyle.GEMMA4_CHANNEL, spec.thinkingStyle)
        assertEquals(ToolCallFormat.GEMMA4, spec.toolCallFormat)
        assertTrue(spec.supportsTools)
    }

    @Test
    fun extract_genericToolCallTemplate_usesGenericFormat() {
        val template = """
            {% if tools is defined %}
            <tools>{{ tools }}</tools>
            {% endif %}
            <tool_call>{"name":"search"}</tool_call>
        """.trimIndent()

        val spec = ChatMarkupSpec.fromChatTemplate(template)
        assertEquals(ToolCallFormat.GENERIC, spec.toolCallFormat)
        assertTrue(spec.supportsTools)
    }

    @Test
    fun parse_and_parseStreaming_agree_on_closed_block() {
        val raw = "<think>plan</think>hello"
        val a = Gemma4ThinkingParser.parse(raw)
        val b = Gemma4ThinkingParser.parseStreaming(raw)
        assertEquals(a.thinking, b.thinking)
        assertEquals(a.answer, b.answer)
        assertEquals("plan", a.thinking)
        assertEquals("hello", a.answer)
    }

    @Test
    fun sanitized_text_cannot_be_split_again_so_raw_must_be_kept() {
        val raw = "<think>secret plan</think>visible answer"
        val sanitized = Gemma4ThinkingParser.sanitizeVisibleText(raw)
        assertEquals("visible answer", sanitized)
        val wronglyGuessed = Gemma4ThinkingParser.parseStreaming(
            rawInput = sanitized,
            treatUnmarkedInputAsThinking = true,
        )
        assertEquals("visible answer", wronglyGuessed.thinking)
        assertEquals("", wronglyGuessed.answer)

        val fromRaw = Gemma4ThinkingParser.parse(raw)
        assertEquals("secret plan", fromRaw.thinking)
        assertEquals("visible answer", fromRaw.answer)
    }

    @Test
    fun resolveThinkingPromptStyle_prefers_template_over_model_name() {
        val qwenNamed = "Qwen3-8B-Q4_K_M.gguf"
        val gemmaTemplate = "{% if enable_thinking %}<|think|>{% endif %}<|channel>thought\n<channel|>"
        val style = ModelNameHeuristics.resolveThinkingPromptStyle(
            modelPathOrName = qwenNamed,
            chatTemplate = gemmaTemplate,
        )
        assertEquals(ThinkingPromptStyle.GEMMA4_CHANNEL, style)
    }

    @Test
    fun resolveToolCallFormat_prefers_template_over_model_name() {
        val format = ModelNameHeuristics.resolveToolCallFormat(
            modelPathOrName = "gemma-4-4b.gguf",
            chatTemplate = "<tool_call>{\"name\":\"x\"}</tool_call>",
        )
        assertEquals(ToolCallFormat.GENERIC, format)
        assertFalse(format == ToolCallFormat.GEMMA4)
    }
}
