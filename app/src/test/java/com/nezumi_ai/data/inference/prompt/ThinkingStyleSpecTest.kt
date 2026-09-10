package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ThinkingPromptStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThinkingStyleSpec] の単体テスト (計画書 Phase 7)。
 *
 * 旧 `ThinkingPromptStyleSpecTest` (app 側、PromptBuilder.ThinkingPromptStyle 参照) の
 * shared 移行版。ThinkingPromptStyle は ModelNameHeuristics に統合されたため、
 * こちらは shared の型だけを参照する。
 */
class ThinkingStyleSpecTest {

    @Test
    fun assistantPrefill_qwenCommand_off_returnsEmptyThinkPrefill() {
        // Qwen 3.0-3.4 (QWEN_COMMAND) の non-thinking は空 <think></think> を先入れ。
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.QWEN_COMMAND, enableThinking = false)
        assertEquals("<think>\n\n</think>\n\n", prefill)
    }

    @Test
    fun assistantPrefill_qwenCommand_on_returnsEmpty() {
        // ON 時はソフトスイッチ (/think) に任せるため prefill なし。
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.QWEN_COMMAND, enableThinking = true)
        assertEquals("", prefill)
    }

    @Test
    fun assistantPrefill_qwenAssistantPrefill_off_returnsEmptyThinkPrefill() {
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL, enableThinking = false)
        assertEquals("<think>\n\n</think>\n\n", prefill)
    }

    @Test
    fun assistantPrefill_qwenAssistantPrefill_on_returnsThinkPrefill() {
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL, enableThinking = true)
        assertEquals("<think>\n", prefill)
    }

    @Test
    fun assistantPrefill_assistantTag_on_returnsThinkPrefill() {
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.ASSISTANT_TAG, enableThinking = true)
        assertEquals("<think>\n", prefill)
    }

    @Test
    fun assistantPrefill_gemma4Channel_on_returnsThinkPrefill() {
        val prefill = ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.GEMMA4_CHANNEL, enableThinking = true)
        assertEquals("<think>\n", prefill)
    }

    @Test
    fun assistantPrefill_gemmaPrefix_returnsEmptyRegardless() {
        // GEMMA_PREFIX はグローバルプレフィックス方式のため assistant prefill は空。
        assertEquals("", ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.GEMMA_PREFIX, enableThinking = true))
        assertEquals("", ThinkingStyleSpec.assistantPrefill(ThinkingPromptStyle.GEMMA_PREFIX, enableThinking = false))
    }

    @Test
    fun globalPrefix_gemmaPrefixOn_returnsThinkPrefix() {
        assertEquals("<|think|>\n", ThinkingStyleSpec.globalPrefix(ThinkingPromptStyle.GEMMA_PREFIX, enableThinking = true))
    }

    @Test
    fun globalPrefix_otherStyles_returnEmpty() {
        listOf(
            ThinkingPromptStyle.QWEN_COMMAND,
            ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            ThinkingPromptStyle.ASSISTANT_TAG,
            ThinkingPromptStyle.GEMMA4_CHANNEL,
            ThinkingPromptStyle.PLAIN_COMPLETION,
        ).forEach { style ->
            assertEquals("", ThinkingStyleSpec.globalPrefix(style, enableThinking = true))
        }
        // GEMMA_PREFIX も OFF なら空
        assertEquals("", ThinkingStyleSpec.globalPrefix(ThinkingPromptStyle.GEMMA_PREFIX, enableThinking = false))
    }

    @Test
    fun usesQwenSoftSwitch_onlyQwenCommand() {
        assertTrue(ThinkingStyleSpec.usesQwenSoftSwitch(ThinkingPromptStyle.QWEN_COMMAND))
        listOf(
            ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            ThinkingPromptStyle.ASSISTANT_TAG,
            ThinkingPromptStyle.GEMMA4_CHANNEL,
            ThinkingPromptStyle.GEMMA_PREFIX,
            ThinkingPromptStyle.PLAIN_COMPLETION,
        ).forEach { assertFalse(ThinkingStyleSpec.usesQwenSoftSwitch(it)) }
    }

    @Test
    fun qwenSoftSwitchDirective() {
        assertEquals("/think", ThinkingStyleSpec.qwenSoftSwitchDirective(enableThinking = true))
        assertEquals("/no_think", ThinkingStyleSpec.qwenSoftSwitchDirective(enableThinking = false))
    }

    @Test
    fun injectsGemma4SystemThinkTrigger_onlyGemma4ChannelOn() {
        assertTrue(ThinkingStyleSpec.injectsGemma4SystemThinkTrigger(ThinkingPromptStyle.GEMMA4_CHANNEL, true))
        assertFalse(ThinkingStyleSpec.injectsGemma4SystemThinkTrigger(ThinkingPromptStyle.GEMMA4_CHANNEL, false))
        assertFalse(ThinkingStyleSpec.injectsGemma4SystemThinkTrigger(ThinkingPromptStyle.GEMMA_PREFIX, true))
    }
}
