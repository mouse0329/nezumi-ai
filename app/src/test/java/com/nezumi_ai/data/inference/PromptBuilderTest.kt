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
            prompt.lines().any { it == "/think" || it == "/no_think" }
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

    // ─── Bug fix(#47): テンプレ適用時の `user:` / `assistant:` 混入回帰テスト ─────────

    /**
     * ChatML チャットテンプレを適用したときに、フォーマットタグ内に生の `user:` / `assistant:`
     * プレフィックスが含まれないことを確認する。
     */
    @Test
    fun buildForGgufChatMl_doesNotLeakRawUserAssistantPrefix() {
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L),
            MessageEntity(sessionId = 1L, role = "assistant", content = "Hi", timestamp = 2L),
            MessageEntity(sessionId = 1L, role = "user", content = "How are you?", timestamp = 3L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "You are helpful.",
            compressedSummary = null,
            format = PromptBuilder.GgufPromptFormat.CHATML,
            enableThinking = false,
            modelPath = "/models/mistral-7b.gguf",
            sanitizeMessageContent = { it.content }
        )
        // Bug fix(#47): user:\n / assistant:\n の生プレフィックスは一切含まない
        assertFalse("CHATML output must not leak raw 'user:\\n' prefix", Regex("(?m)^user:\\s").containsMatchIn(prompt))
        assertFalse("CHATML output must not leak raw 'assistant:\\n' prefix", Regex("(?m)^assistant:\\s").containsMatchIn(prompt))
        assertTrue(prompt.contains("<|im_start|>user\nHello"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    /**
     * Gemma チャットテンプレを適用したときに、フォーマットタグ内に生の `user:` / `assistant:`
     * プレフィックスが含まれないことを確認する (Gemma は role 名が "model" なので "assistant" も吐かない)。
     */
    @Test
    fun buildForGgufGemma_doesNotLeakRawUserAssistantPrefix() {
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L)
        )
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.GgufPromptFormat.GEMMA_CHAT,
            enableThinking = false,
            modelPath = "/models/gemma-2-2b-it.gguf",
            sanitizeMessageContent = { it.content }
        )
        assertFalse("Gemma output must not leak raw 'user:' prefix", Regex("(?m)^user:\\s").containsMatchIn(prompt))
        assertFalse("Gemma output must not leak raw 'assistant:' prefix", Regex("(?m)^assistant:").containsMatchIn(prompt))
        assertTrue(prompt.contains("<start_of_turn>user\nHello"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    /**
     * Bug fix(#47): buildForLiteRt のカスタムテンプレ未設定フォールバックが、
     * `User: xxx\nAssistant: yyy` という生の role prefix を吐かないことを確認する。
     * 以前はこのフォールバックが LiteRT ランタイムの chat_template と衝突し、
     * モデル実行時に `User:` / `Assistant:` リテラルがトークンとして混入するバグの主因だった。
     */
    @Test
    fun buildForLiteRt_doesNotLeakLegacyUserAssistantPrefix() {
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "Hello", timestamp = 1L),
            MessageEntity(sessionId = 1L, role = "assistant", content = "Hi", timestamp = 2L),
            MessageEntity(sessionId = 1L, role = "user", content = "How are you?", timestamp = 3L)
        )
        val prompt = PromptBuilder.buildForLiteRt(
            messages = messages,
            systemPrompt = "You are helpful.",
            injectGemmaThinkTrigger = false,
            compressedSummary = null,
            sanitizeMessageContent = { it.content },
            appContext = null,
            modelPath = "/models/gemma-3n-e4b.task"
        )
        // 以前の実装では "User: Hello\nAssistant: Hi\n...\nAssistant:" となっていた。
        assertFalse("LiteRT fallback must not leak raw 'User:' prefix", Regex("(?m)^User:\\s").containsMatchIn(prompt))
        assertFalse("LiteRT fallback must not leak raw 'Assistant:' prefix", Regex("(?m)^Assistant:").containsMatchIn(prompt))
        // 新実装は Gemma チャットテンプレ相当に統一される
        assertTrue("LiteRT fallback should use <start_of_turn> tags", prompt.contains("<start_of_turn>user\n"))
        assertTrue("LiteRT fallback should end with model turn opener", prompt.endsWith("<start_of_turn>model\n"))
    }

    /**
     * Bug fix(#48) 回帰: GGUF ヒューリスティックビルドで履歴の最後の user ターンが
     * 二重展開されないことを確認する (以前は Prompt / Response 相当と History の両方で
     * 同じ内容が吐かれていた)。appContext=null のためヒューリスティック経路を検証する。
     */
    @Test
    fun ggufHeuristicPrompt_rendersLastUserTurnOnce() {
        val messages = listOf(
            MessageEntity(sessionId = 1L, role = "user", content = "question-A", timestamp = 1L),
            MessageEntity(sessionId = 1L, role = "assistant", content = "answer-A", timestamp = 2L),
            MessageEntity(sessionId = 1L, role = "user", content = "question-B", timestamp = 3L)
        )
        // ヒューリスティック経路 (ChatML) で最後の user 内容が 1 回だけ吐かれることを確認する。
        val prompt = PromptBuilder.buildForGguf(
            messages = messages,
            systemPrompt = "",
            compressedSummary = null,
            format = PromptBuilder.GgufPromptFormat.CHATML,
            enableThinking = false,
            modelPath = "/models/generic.gguf",
            sanitizeMessageContent = { it.content }
        )
        // question-B は 1 回しか現れない (以前は Prompt と History 両方で 2 回現れていた)
        val occurrences = Regex("question-B").findAll(prompt).count()
        assertEquals("last user turn must appear exactly once", 1, occurrences)
    }
}
