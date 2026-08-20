package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 注: `toolCalls[0].arguments` の確認は litertlm.ToolCall のフィールド名に依存するため
// このテストファイルでは意図的に name のみを取る。arguments のキー/値射影の
// パーステストは Cloud 側 [ToolCallTagIntegrationTest.cloudParser_parsesGenericAndGemma4Formats] でカバーする。

/**
 * Hugging Face 公式 chat_template と各モデルの model card に載っている実出力例を
 * そのまま fixture としてパーサに食わせる回帰テスト。
 *
 * 参照:
 *   - Qwen3     : https://huggingface.co/blog/qwen-3-chat-template-deep-dive
 *                 https://huggingface.co/Qwen/Qwen3-235B-A22B?chat_template=default
 *   - Gemma 3n  : https://ai.google.dev/gemma/docs/capabilities/thinking
 *                 https://huggingface.co/google/gemma-4-31B-it (chat_template)
 *   - Hermes-Pro: https://huggingface.co/NousResearch/Hermes-2-Pro-Llama-3-8B/discussions/13
 *   - DeepSeek-R1: https://huggingface.co/deepseek-ai/DeepSeek-R1
 *
 * 目的:
 *   1. 実際にネットで確認できる生出力サンプルを増やしてカバレッジを底上げする。
 *   2. tag literal 集約や parse/parseStreaming 共通化のリファクタで挙動が変わっていないことを保証する。
 */
class RealWorldModelOutputTest {

    // ─── Qwen 3 : <think>...</think> + <tool_call>...</tool_call> ────────────

    @Test
    fun qwen3_thinkingBlockFollowedByAnswer_isSeparated() {
        // Qwen3 の thinking ON 時に想定される生出力: <think>...</think> の後ろに本文。
        // (blog の "chain-of-thought preservation through tool calls" セクションの形)。
        val raw = """
            <think>
            ユーザーは今日の日付を聞いている。get_current_time を呼ぶべきだ。
            </think>
            今日は 2026 年 8 月 20 日です。
        """.trimIndent()

        val result = Gemma4ThinkingParser.parse(raw)
        assertNotNull(result.thinking)
        assertTrue(result.thinking!!.contains("get_current_time"))
        assertEquals("今日は 2026 年 8 月 20 日です。", result.answer)
    }

    @Test
    fun qwen3_emptyThinkTagsFromNonThinkingJinja_areStripped() {
        // Qwen3 の non-thinking jinja が付ける空 <think>\n\n</think>\n\n が assistant 応答の
        // 先頭に混入したケース (エコーバック)。回答テキストのみが残ること。
        val raw = "<think>\n\n</think>\n\n2026 年 8 月 20 日（木）です。"

        val result = Gemma4ThinkingParser.parse(raw)
        assertNull(result.thinking)
        assertEquals("2026 年 8 月 20 日（木）です。", result.answer)
    }

    @Test
    fun qwen3_toolCallInAnswerBody_isParsedByGgufParser() {
        // Qwen3 chat_template が期待する tool_call フォーマット。
        val raw = """
            <think>
            天気を調べる必要がある。
            </think>
            調べます。
            <tool_call>
            {"name":"web_search","arguments":{"query":"東京 天気 2026年8月20日"}}
            </tool_call>
        """.trimIndent()

        val parsed = GgufToolCallParser.parse(raw, isGemma4 = false)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("web_search", parsed.toolCalls[0].name)
        assertFalse(parsed.hadTruncatedToolCall)
        assertFalse(parsed.fellBackToAlternateFormat)
    }

    @Test
    fun qwen3_toolResponseRoundTrip_isHiddenFromVisibleTextSegments() {
        // Qwen3: <tool_call> と <tool_response> の対を含む会話履歴の可視本文サニタイズ。
        val raw = buildString {
            append("<think>\n必要なツールを呼ぶ。\n</think>\n")
            append("時間を調べます。")
            append("<tool_call>\n{\"name\":\"get_current_time\",\"arguments\":{}}\n</tool_call>\n")
            append("<tool_response>\n{\"name\":\"get_current_time\",\"content\":{\"time\":\"07:10\"}}\n</tool_response>\n")
            append("2026 年 8 月 20 日 07:10 (JST) です。")
        }

        val segments = GgufToolCallParser.parseSegments(raw)
        val visible = segments.filterIsInstance<GgufToolCallParser.Segment.TextSegment>()
            .joinToString(separator = "") { it.text }
        assertTrue(visible.contains("時間を調べます"))
        assertTrue(visible.contains("2026 年 8 月 20 日 07:10"))
        assertFalse(visible.contains("tool_response"))
        assertFalse(visible.contains("<tool_call>"))
    }

    // ─── Gemma 3n / Gemma 4 : <|channel>thought ... <channel|> + <|tool_call> ─

    @Test
    fun gemma4_thoughtChannelFollowedByAnswer_isSeparated() {
        // Gemma 4 (Google AI for Developers "Thinking mode in Gemma") の想定生出力。
        val raw = "<|channel>thought\nユーザーは今日の日付を聞いている。<channel|>2026 年 8 月 20 日です。"

        val result = Gemma4ThinkingParser.parse(raw)
        assertEquals("ユーザーは今日の日付を聞いている。", result.thinking)
        assertEquals("2026 年 8 月 20 日です。", result.answer)
    }

    @Test
    fun gemma4_officialToolCallFormat_isParsed() {
        // Gemma 4 公式ツールコール形式: <|tool_call>call:name{args}<tool_call|>
        val raw = "<|tool_call>call:get_current_time{}<tool_call|>"

        val parsed = GgufToolCallParser.parse(raw, isGemma4 = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("get_current_time", parsed.toolCalls[0].name)
        assertFalse(parsed.fellBackToAlternateFormat)
    }

    @Test
    fun gemma4_officialToolCallWithQuoteToken_isParsed() {
        // Gemma 4 の <|"|>...<|"|> 文字列トークン付き引数。
        val raw = "<|tool_call>call:web_search{query:<|\"|>東京 天気<|\"|>}<tool_call|>"

        val parsed = GgufToolCallParser.parse(raw, isGemma4 = true)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("web_search", parsed.toolCalls[0].name)
    }

    @Test
    fun gemma4_channelThinkingThenToolCall_answerIsClean() {
        // Gemma 4 想定: thinking → 説明 → tool_call の順で並ぶ生出力。
        val raw = buildString {
            append("<|channel>thought\n天気を調べる。<channel|>")
            append("調べます。")
            append("<|tool_call>call:web_search{query:<|\"|>tokyo weather<|\"|>}<tool_call|>")
        }

        val parsed = Gemma4ThinkingParser.parse(raw, preserveToolCallTags = false)
        assertEquals("天気を調べる。", parsed.thinking)
        // sanitize は tool_call タグを剥がすので、モデルへの前段 answer は説明文だけになる。
        assertTrue(parsed.answer.contains("調べます。"))
        assertFalse(parsed.answer.contains("<|tool_call>"))
        assertFalse(parsed.answer.contains("<tool_call|>"))
    }

    @Test
    fun gemma4_channelThinkingPreserveToolCallTags_keepsInlineCardTags() {
        // インライン tool-call カード描画経路 (preserveToolCallTags=true) では、
        // 本文中の <|tool_call>...<tool_call|> 対はそのまま残ること。
        val raw = "<|channel>thought\n天気を調べる。<channel|>調べます。<|tool_call>call:web_search{}<tool_call|>"

        val parsed = Gemma4ThinkingParser.parse(raw, preserveToolCallTags = true)
        assertEquals("天気を調べる。", parsed.thinking)
        assertTrue(parsed.answer.contains("<|tool_call>call:web_search{}<tool_call|>"))
    }

    // ─── Hermes-Pro : <tool_call>{"arguments":...,"name":...}</tool_call> ────

    @Test
    fun hermesPro_toolCallExample_isParsedByGenericBranch() {
        // Hermes-2-Pro-Llama-3-8B の discussion #13 で示された想定フォーマット。
        // 汎用 <tool_call> XML の中に arguments/name の順で JSON が来る。
        val raw = """
            <tool_call>
            {"arguments": {"symbol": "TSLA"}, "name": "get_stock_price"}
            </tool_call>
        """.trimIndent()

        val parsed = GgufToolCallParser.parse(raw, isGemma4 = false)
        assertEquals(1, parsed.toolCalls.size)
        assertEquals("get_stock_price", parsed.toolCalls[0].name)
    }

    @Test
    fun hermesPro_toolCallThenPlainAnswer_orderIsPreserved() {
        val raw = buildString {
            append("Let me check the price.\n")
            append("<tool_call>\n{\"name\":\"get_stock_price\",\"arguments\":{\"symbol\":\"TSLA\"}}\n</tool_call>\n")
            append("The price is $350.")
        }

        val segments = GgufToolCallParser.parseSegments(raw)
        assertTrue(segments.size >= 3)
        assertTrue(segments[0] is GgufToolCallParser.Segment.TextSegment)
        assertTrue(segments.any { it is GgufToolCallParser.Segment.ToolCallSegment })
        val visible = segments.filterIsInstance<GgufToolCallParser.Segment.TextSegment>()
            .joinToString(separator = "") { it.text }
        assertTrue(visible.contains("Let me check the price."))
        assertTrue(visible.contains("The price is \$350."))
    }

    // ─── DeepSeek-R1 : <think>...</think> only (tool call は非対象) ─────────

    @Test
    fun deepseekR1_thinkingBlockPrecedesAnswer_isSeparated() {
        // DeepSeek-R1 公式ドキュメントの推奨: 応答は常に <think>\n から始まり、
        // </think> で締めた後に最終回答が来る。
        val raw = "<think>\nStep 1: analyze the query.\nStep 2: reply.\n</think>\n\nThe answer is 42."

        val result = Gemma4ThinkingParser.parse(raw)
        assertNotNull(result.thinking)
        assertTrue(result.thinking!!.contains("Step 1"))
        assertTrue(result.thinking!!.contains("Step 2"))
        assertEquals("The answer is 42.", result.answer)
    }

    @Test
    fun deepseekR1_bypassThinkingPatternWithEmptyThink_returnsOnlyAnswer() {
        // 公式 model card 記載: DeepSeek-R1 系は稀に <think>\n\n</think> だけを吐いてから
        // 回答本文を書くことがある。この場合 thinking は空、answer は本文のみ。
        val raw = "<think>\n\n</think>\n\nThe answer is 42."

        val result = Gemma4ThinkingParser.parse(raw)
        assertNull(result.thinking)
        assertEquals("The answer is 42.", result.answer)
    }

    @Test
    fun deepseekR1_thinkingInProgress_isReportedAsStreamingThinking() {
        // </think> 未到達のストリーミング途中: parseStreaming は thinking 側に生テキストを流す。
        val raw = "<think>\nStep 1: analyze"

        val result = Gemma4ThinkingParser.parseStreaming(raw)
        assertEquals("Step 1: analyze", result.thinking)
        assertEquals("", result.answer)
    }

    // ─── 共通: 未閉じ / 断片的なタグの取り扱い ───────────────────────────────

    @Test
    fun anyModel_orphanTrailingToolCallTag_isRemovedFromVisibleAnswer() {
        // 生成が途中で切れて <tool_call>{... が残ったケース。可視本文はタグごと消える。
        val raw = "本文\n<tool_call>{\"name\":\"foo\""

        val cleaned = Gemma4ThinkingParser.sanitizeVisibleText(raw)
        assertEquals("本文", cleaned)
    }

    @Test
    fun anyModel_orphanTrailingGemma4ToolCallTag_isRemovedFromVisibleAnswer() {
        // Gemma 4 の非対称タグの未閉じ末尾も同様に掃除される。
        val raw = "本文\n<|tool_call>call:foo{"

        val cleaned = Gemma4ThinkingParser.sanitizeVisibleText(raw)
        assertEquals("本文", cleaned)
    }
}
