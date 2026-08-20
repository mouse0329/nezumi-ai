package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ThinkingPromptStyleSpec] の分岐表を単独で検証する。
 *
 * PromptBuilder 側は Spec の戻り値を貼るだけの薄いラッパになったので、
 * Thinking ON/OFF の挙動が変わっていないことをここで押さえる。
 */
class ThinkingPromptStyleSpecTest {

    @Test
    fun assistantPrefill_qwenCommand_off_returnsEmptyThinkPrefill() {
        // Qwen 3.0-3.4 (QWEN_COMMAND) の non-thinking は
        //   Hugging Face 公式 chat_template と同じ空 <think>\n\n</think>\n\n を先入れ。
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND,
            enableThinking = false
        )
        assertEquals(ToolCallTags.QWEN_EMPTY_THINK_PREFILL, prefill)
    }

    @Test
    fun assistantPrefill_qwenCommand_on_returnsEmpty() {
        // QWEN_COMMAND + ON はソフトスイッチに任せて prefill は入れない。
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND,
            enableThinking = true
        )
        assertEquals("", prefill)
    }

    @Test
    fun assistantPrefill_qwenAssistantPrefill_off_returnsEmptyThinkPrefill() {
        // Qwen 3.5+ (QWEN_ASSISTANT_PREFILL) の OFF はソフトスイッチ廃止世代なので
        // 空 <think>\n\n</think>\n\n を確実に入れる。
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            enableThinking = false
        )
        assertEquals(ToolCallTags.QWEN_EMPTY_THINK_PREFILL, prefill)
    }

    @Test
    fun assistantPrefill_qwenAssistantPrefill_on_returnsThinkOpen() {
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            enableThinking = true
        )
        assertEquals(ToolCallTags.ASSISTANT_THINK_PREFILL, prefill)
    }

    @Test
    fun assistantPrefill_assistantTag_on_returnsThinkOpen() {
        // DeepSeek-R1 / QwQ 系はデフォルト thinking 常時 ON なので <think>\n を prefill する。
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
            enableThinking = true
        )
        assertEquals(ToolCallTags.ASSISTANT_THINK_PREFILL, prefill)
    }

    @Test
    fun assistantPrefill_assistantTag_off_returnsEmpty() {
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
            enableThinking = false
        )
        assertEquals("", prefill)
    }

    @Test
    fun assistantPrefill_gemma4Channel_on_returnsThinkOpen() {
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
            enableThinking = true
        )
        assertEquals(ToolCallTags.ASSISTANT_THINK_PREFILL, prefill)
    }

    @Test
    fun assistantPrefill_gemma4Channel_off_returnsEmpty() {
        // GEMMA4_CHANNEL は OFF のとき何も入れない (Gemma4 GGUF は Qwen と違って
        // 空 <think>\n\n</think>\n\n を prefill する必要はない仕様)。
        val prefill = ThinkingPromptStyleSpec.assistantPrefill(
            PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
            enableThinking = false
        )
        assertEquals("", prefill)
    }

    @Test
    fun assistantPrefill_gemmaPrefix_alwaysEmpty() {
        // GEMMA_PREFIX は globalPrefix で <|think|>\n を先頭に付ける方式なので、
        // assistant prefill は常に空。
        assertEquals(
            "",
            ThinkingPromptStyleSpec.assistantPrefill(
                PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
                enableThinking = true
            )
        )
        assertEquals(
            "",
            ThinkingPromptStyleSpec.assistantPrefill(
                PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
                enableThinking = false
            )
        )
    }

    @Test
    fun assistantPrefill_plainCompletion_alwaysEmpty() {
        // GPT-2 系の plain completion は thinking 制御タグを一切入れない。
        assertEquals(
            "",
            ThinkingPromptStyleSpec.assistantPrefill(
                PromptBuilder.ThinkingPromptStyle.PLAIN_COMPLETION,
                enableThinking = true
            )
        )
    }

    @Test
    fun globalPrefix_gemmaPrefix_on_returnsGemmaThinkPrefix() {
        val prefix = ThinkingPromptStyleSpec.globalPrefix(
            PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
            enableThinking = true
        )
        assertEquals(ToolCallTags.GEMMA_THINK_PREFIX, prefix)
    }

    @Test
    fun globalPrefix_gemmaPrefix_off_returnsEmpty() {
        assertEquals(
            "",
            ThinkingPromptStyleSpec.globalPrefix(
                PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
                enableThinking = false
            )
        )
    }

    @Test
    fun globalPrefix_gemma4Channel_alwaysEmpty() {
        // GEMMA4_CHANNEL はシステムターン内に <|think|> を埋めるため、globalPrefix は付けない。
        for (enable in listOf(true, false)) {
            assertEquals(
                "",
                ThinkingPromptStyleSpec.globalPrefix(
                    PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
                    enableThinking = enable
                )
            )
        }
    }

    @Test
    fun thinkingTemplateFlag_onlyForPrefillAndTagStyles() {
        // {{ if .Thinking }} テンプレ変数が ON になる style を過不足なく確認する。
        assertTrue(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
                enableThinking = true
            )
        )
        assertTrue(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
                enableThinking = true
            )
        )
        assertTrue(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
                enableThinking = true
            )
        )
        // QWEN_COMMAND は directive 差込で制御するのでテンプレフラグは立てない。
        assertFalse(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND,
                enableThinking = true
            )
        )
        // GEMMA_PREFIX は globalPrefix で制御するのでテンプレフラグは立てない。
        assertFalse(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
                enableThinking = true
            )
        )
        // enableThinking=false のときはどの style でも false。
        assertFalse(
            ThinkingPromptStyleSpec.thinkingTemplateFlag(
                PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
                enableThinking = false
            )
        )
    }

    @Test
    fun usesQwenSoftSwitch_onlyForQwenCommand() {
        assertTrue(
            ThinkingPromptStyleSpec.usesQwenSoftSwitch(PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND)
        )
        for (style in listOf(
            PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
            PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
            PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
            PromptBuilder.ThinkingPromptStyle.PLAIN_COMPLETION
        )) {
            assertFalse(ThinkingPromptStyleSpec.usesQwenSoftSwitch(style))
        }
    }

    @Test
    fun qwenSoftSwitchDirective_matchesOfficialChatTemplateCommands() {
        assertEquals(
            ToolCallTags.QWEN_THINK_COMMAND,
            ThinkingPromptStyleSpec.qwenSoftSwitchDirective(enableThinking = true)
        )
        assertEquals(
            ToolCallTags.QWEN_NO_THINK_COMMAND,
            ThinkingPromptStyleSpec.qwenSoftSwitchDirective(enableThinking = false)
        )
    }

    @Test
    fun injectsGemma4SystemThinkTrigger_onlyWhenGemma4ChannelAndOn() {
        assertTrue(
            ThinkingPromptStyleSpec.injectsGemma4SystemThinkTrigger(
                PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
                enableThinking = true
            )
        )
        assertFalse(
            ThinkingPromptStyleSpec.injectsGemma4SystemThinkTrigger(
                PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL,
                enableThinking = false
            )
        )
        assertFalse(
            ThinkingPromptStyleSpec.injectsGemma4SystemThinkTrigger(
                PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX,
                enableThinking = true
            )
        )
        assertFalse(
            ThinkingPromptStyleSpec.injectsGemma4SystemThinkTrigger(
                PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG,
                enableThinking = true
            )
        )
    }
}
