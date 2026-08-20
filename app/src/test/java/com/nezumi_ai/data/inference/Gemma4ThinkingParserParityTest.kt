package com.nezumi_ai.data.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * parse / parseStreaming の共通ヘルパ (splitAtThinkTags / splitAtChannelTags) を導入した
 * リファクタで、両関数の挙動が仕様どおり保たれていることを保証する。
 *
 * 主な検証観点:
 *   - `<think>...</think>` (Qwen3 / DeepSeek-R1 / GGUF) の解釈が parse/parseStreaming で一致
 *   - `<|channel>thought\n...<channel|>` (Gemma 4) の解釈が一致
 *   - streaming 中の末端未到達 (`</think>` 未着) だけ挙動が異なることが明示的に確認できる
 */
class Gemma4ThinkingParserParityTest {

    @Test
    fun closedThinkBlock_isSplitIdenticallyByParseAndParseStreaming() {
        val raw = "<think>reasoning body</think>final answer"

        val parsed = Gemma4ThinkingParser.parse(raw)
        val streamed = Gemma4ThinkingParser.parseStreaming(raw)

        assertEquals(parsed.thinking, streamed.thinking)
        assertEquals(parsed.answer, streamed.answer)
        assertEquals("reasoning body", parsed.thinking)
        assertEquals("final answer", parsed.answer)
    }

    @Test
    fun openThinkStreaming_returnsThinkingOnly_parseIntoAnswer() {
        // 開きタグしか無い場合の意図的な差異:
        //   - parseStreaming: 思考が途中まで出ている前提で thinking 側へ流す。
        //   - parse: 思考本文を JSON 境界などで切って answer 側へも流す (thinking 単独固定はしない)。
        val raw = "<think>partial reasoning"

        val streamed = Gemma4ThinkingParser.parseStreaming(raw)
        assertEquals("partial reasoning", streamed.thinking)
        assertEquals("", streamed.answer)

        val parsed = Gemma4ThinkingParser.parse(raw)
        // parse 経路は splitThinkingBySpecialToken を通すが、境界文字が無ければ thinking 側に残る。
        assertEquals("partial reasoning", parsed.thinking)
        assertEquals("", parsed.answer)
    }

    @Test
    fun channelThoughtBlock_isSplitIdenticallyByParseAndParseStreaming() {
        val raw = "<|channel>thought\nreasoning body<channel|>final answer"

        val parsed = Gemma4ThinkingParser.parse(raw)
        val streamed = Gemma4ThinkingParser.parseStreaming(raw)

        assertEquals(parsed.thinking, streamed.thinking)
        assertEquals(parsed.answer, streamed.answer)
        assertEquals("reasoning body", parsed.thinking)
        assertEquals("final answer", parsed.answer)
    }

    @Test
    fun channelThoughtOpenOnlyStreaming_returnsInProgressThinking() {
        // Gemma 4 のストリーミング中: `<channel|>` 未到達でも `<|channel>thought\n` を検出したら
        // thinking 側に「思考中の生テキスト」を流す。
        val raw = "<|channel>thought\nlooking up the timezone"

        val streamed = Gemma4ThinkingParser.parseStreaming(raw)
        assertEquals("looking up the timezone", streamed.thinking)
        assertEquals("", streamed.answer)
    }

    @Test
    fun channelThoughtLabelIncomplete_returnsNoThinkingUntilLabelResolved() {
        // `<|channel>` 直後にまだ `thought\n` のプレフィックスが揃っていないとき、
        // 思考本文としての確定を保留する。
        val raw = "<|channel>tho"

        val streamed = Gemma4ThinkingParser.parseStreaming(raw)
        assertNull(streamed.thinking)
        assertEquals("", streamed.answer)
    }

    @Test
    fun thinkTagInsideAnswer_takesPrecedenceOverChannelTag() {
        // `<think>` と `<|channel>` が両方あるときは `<think>` 優先 (GGUF 経路のほうが多いため)。
        val raw = "<think>gguf-style</think>answer<|channel>thought\nchannel-style<channel|>after"

        val parsed = Gemma4ThinkingParser.parse(raw)
        assertNotNull(parsed.thinking)
        assertTrue(parsed.thinking!!.contains("gguf-style"))
        // `<|channel>` は sanitize regex で剥がされ、`answer` と `after` が結合される。
        assertTrue(parsed.answer.contains("answer"))
        assertTrue(parsed.answer.contains("after"))
    }

    @Test
    fun sanitizeVisibleText_isIdempotentAcrossCalls() {
        // 同じ入力を 2 度サニタイズしても結果が変わらないこと (循環削除の停止性)。
        val raw = "<think>x</think><tool_call>{...}</tool_call>本文<tool_response>{}</tool_response>"

        val once = Gemma4ThinkingParser.sanitizeVisibleText(raw)
        val twice = Gemma4ThinkingParser.sanitizeVisibleText(once)
        assertEquals(once, twice)
        assertEquals("本文", once)
    }

    @Test
    fun answerOnlyForModelContext_stripsThinkingButKeepsToolCallForContext() {
        // 会話履歴を次ターンに戻すための answerOnlyForModelContext は thinking だけ剥がし、
        // <tool_call> は残す (モデルがツールコールと結果の対応を追えるようにするため)。
        val raw = "<think>reason</think>本文\n<tool_call>{\"name\":\"foo\",\"arguments\":{}}</tool_call>"

        val out = Gemma4ThinkingParser.answerOnlyForModelContext(raw)
        assertTrue("thinking must be stripped", !out.contains("reason"))
        assertTrue("tool_call must be preserved", out.contains("<tool_call>"))
        assertTrue("tool_call payload must be preserved", out.contains("\"name\":\"foo\""))
        assertTrue("visible answer must be preserved", out.contains("本文"))
    }

    @Test
    fun stripThinkingForModelPrompt_removesChannelBlocksTooForGemma4() {
        // ツールラウンド継続時、Gemma 4 の <|channel>thought ... <channel|> も剥がす。
        val raw = "<|channel>thought\nsecret<channel|>本文\n<|tool_call>call:foo{}<tool_call|>"

        val out = Gemma4ThinkingParser.stripThinkingForModelPrompt(raw)
        assertTrue("channel thought must be stripped", !out.contains("secret"))
        assertTrue("tool_call must be preserved", out.contains("<|tool_call>"))
        assertTrue("visible body must be preserved", out.contains("本文"))
    }
}
