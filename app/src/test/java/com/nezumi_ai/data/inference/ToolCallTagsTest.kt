package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ToolCallTags] の各定数が想定される literal をそのまま持ち、パーサと Prompt ビルダーが
 * 同じ文字列を参照していることを保証する回帰テスト。
 *
 * literal がバラバラに散らばっていた頃は、片方のファイルだけを直したときに tag drift で
 * サニタイズが片側だけ通る/通らないというバグを起こしていた。
 */
class ToolCallTagsTest {

    @Test
    fun genericToolCallTags_matchHuggingFaceQwen3AndHermesProSpec() {
        assertEquals("<tool_call>", ToolCallTags.TOOL_CALL_OPEN)
        assertEquals("</tool_call>", ToolCallTags.TOOL_CALL_CLOSE)
        assertEquals("<tool_response>", ToolCallTags.TOOL_RESPONSE_OPEN)
        assertEquals("</tool_response>", ToolCallTags.TOOL_RESPONSE_CLOSE)
    }

    @Test
    fun gemma4AsymmetricTags_matchGoogleAiForDevelopersSpec() {
        // Gemma 4 (Google 公式) は開き/閉じで `|` の位置が入れ替わる非対称タグ。
        assertEquals("<|tool_call>", ToolCallTags.GEMMA4_TOOL_CALL_OPEN)
        assertEquals("<tool_call|>", ToolCallTags.GEMMA4_TOOL_CALL_CLOSE)
        assertEquals("<|channel>", ToolCallTags.CHANNEL_OPEN)
        assertEquals("<channel|>", ToolCallTags.CHANNEL_CLOSE)
        assertEquals("thought\n", ToolCallTags.THOUGHT_LABEL)
    }

    @Test
    fun thinkingTags_matchQwen3AndDeepSeekR1Spec() {
        assertEquals("<think>", ToolCallTags.THINK_OPEN)
        assertEquals("</think>", ToolCallTags.THINK_CLOSE)
        // Qwen 3.5+ の非対称シンキング閉じタグ。
        assertEquals("<|/think|>", ToolCallTags.THINK_CLOSE_ALT)
        // Qwen3 公式 non-thinking jinja の empty prefill と完全一致すること。
        assertEquals("<think>\n\n</think>\n\n", ToolCallTags.QWEN_EMPTY_THINK_PREFILL)
        // DeepSeek-R1 公式ドキュメント推奨の thinking prefill と完全一致すること。
        assertEquals("<think>\n", ToolCallTags.ASSISTANT_THINK_PREFILL)
    }

    @Test
    fun toolCallTagTokens_containBothGenericAndGemma4Pairs() {
        // 両フォーマット共に「保持対象」であること。preserveToolCallTags=true のとき
        // Sanitize がこの集合のタグを剥がさない前提のカバレッジ。
        assertTrue(ToolCallTags.TOOL_CALL_OPEN in ToolCallTags.TOOL_CALL_TAG_TOKENS)
        assertTrue(ToolCallTags.TOOL_CALL_CLOSE in ToolCallTags.TOOL_CALL_TAG_TOKENS)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_OPEN in ToolCallTags.TOOL_CALL_TAG_TOKENS)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE in ToolCallTags.TOOL_CALL_TAG_TOKENS)
        assertTrue(ToolCallTags.TOOL_RESPONSE_OPEN in ToolCallTags.TOOL_CALL_TAG_TOKENS)
        assertTrue(ToolCallTags.TOOL_RESPONSE_CLOSE in ToolCallTags.TOOL_CALL_TAG_TOKENS)
    }

    @Test
    fun stripTokenSequences_containAllToolAndThinkTags() {
        // Sanitize が制御タグを一括で剥がせるように、集約タグが全て STRIP 対象に含まれること。
        val strip = ToolCallTags.STRIP_TOKEN_SEQUENCES
        assertTrue(ToolCallTags.THINK_OPEN in strip)
        assertTrue(ToolCallTags.THINK_CLOSE in strip)
        assertTrue(ToolCallTags.THINK_CLOSE_ALT in strip)
        assertTrue(ToolCallTags.TOOL_CALL_OPEN in strip)
        assertTrue(ToolCallTags.TOOL_CALL_CLOSE in strip)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_OPEN in strip)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE in strip)
        assertTrue(ToolCallTags.TOOL_RESPONSE_OPEN in strip)
        assertTrue(ToolCallTags.TOOL_RESPONSE_CLOSE in strip)
        assertTrue(ToolCallTags.TOOLS_OPEN in strip)
        assertTrue(ToolCallTags.TOOLS_CLOSE in strip)
    }

    @Test
    fun stripTokenSequences_doesNotContainAsymmetricChannelTags() {
        // <|channel>/<channel|> はサニタイズ regex 経由で剥がす方針で、単純 replace 対象には
        // 含めない。これを含めると thought 本文の一部を誤って削ってしまうことがある。
        val strip = ToolCallTags.STRIP_TOKEN_SEQUENCES
        assertFalse(ToolCallTags.CHANNEL_OPEN in strip)
        assertFalse(ToolCallTags.CHANNEL_CLOSE in strip)
    }

    @Test
    fun thinkingTerminatorTokens_containsBothBraceAndToolTagBoundaries() {
        // 思考本文を強制終端させる境界トークンとして、`{` `}` などの汎用境界と
        // ツール系タグの両方を持つこと。片方だけだと thinking が tool_call JSON に食い込む。
        val terminators = ToolCallTags.THINKING_TERMINATOR_TOKENS
        assertTrue("{" in terminators)
        assertTrue("}" in terminators)
        assertTrue("`" in terminators)
        assertTrue("```" in terminators)
        assertTrue(ToolCallTags.TOOL_CALL_OPEN in terminators)
        assertTrue(ToolCallTags.TOOL_CALL_CLOSE in terminators)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_OPEN in terminators)
        assertTrue(ToolCallTags.GEMMA4_TOOL_CALL_CLOSE in terminators)
        assertTrue(ToolCallTags.TOOL_RESPONSE_OPEN in terminators)
        assertTrue(ToolCallTags.TOOL_RESPONSE_CLOSE in terminators)
    }

    @Test
    fun qwenSoftSwitchDirectives_matchOfficialQwen3TemplateCommand() {
        // Qwen 3.0-3.4 の /think・/no_think ソフトスイッチ (Hugging Face 公式 chat_template)。
        assertEquals("/think", ToolCallTags.QWEN_THINK_COMMAND)
        assertEquals("/no_think", ToolCallTags.QWEN_NO_THINK_COMMAND)
    }

    @Test
    fun gemma4ThinkTrigger_matchesGoogleOfficialSpec() {
        // Gemma 4 (Google AI for Developers) のシステム内トリガと prompt-prefix。
        assertEquals("<|think|>", ToolCallTags.GEMMA_THINK_TRIGGER)
        assertEquals("<|think|>\n", ToolCallTags.GEMMA_THINK_PREFIX)
    }
}
