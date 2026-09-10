package com.nezumi_ai.data.inference.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModelNameHeuristics] の単体テスト (計画書 Phase 2)。
 *
 * 旧 `PromptBuilder` の各 `isXxxModelName` 系と shared の `Gemma4ModelDetector` を
 * 統合した際に挙動が変わっていないことを、モデル名バリエーション
 * (Gemma 3/4, Qwen 2/3/3.5+, QwQ, LFM, GPT-2, Llama, クラウド ID) で網羅的に担保する。
 */
class ModelNameHeuristicsTest {

    // ---- isGemma4ModelName ----

    @Test
    fun isGemma4ModelName_matchesDirectGemma4Spellings() {
        listOf(
            "gemma4",
            "gemma-4",
            "gemma_4",
            "gemma 4",
            "gemma.4",
            "gemma-4-27b-it-q4_k_m.gguf",
            "gemma4b", // Google 短縮命名
        ).forEach { name ->
            assertTrue("$name should be Gemma4", ModelNameHeuristics.isGemma4ModelName(name))
        }
    }

    @Test
    fun isGemma4ModelName_matchesEfficientSeriesAndMoeNotation() {
        listOf(
            "gemma-4-e2b-it",
            "gemma-4-e4b",
            "gemma-4-e8b-q8_0.gguf",
            "gemma-4-e12b",
            "gemma-4-12b-a4b-it",
            "gemma-4-26b-a4b",
            "gemma-4-31b-a4b",
            "gemma-4-46b-a4b",
        ).forEach { name ->
            assertTrue("$name should be Gemma4", ModelNameHeuristics.isGemma4ModelName(name))
        }
    }

    @Test
    fun isGemma4ModelName_rejectsGemma3AndNonGemma() {
        listOf(
            "gemma-3-4b-it",
            "gemma-3-27b",
            "gemma2-9b-it",
            "qwen3-14b",
            "llama-3.2-3b-instruct",
            "gemma", // バージョン無しは Gemma4 とはみなさない
        ).forEach { name ->
            assertFalse("$name should NOT be Gemma4", ModelNameHeuristics.isGemma4ModelName(name))
        }
    }

    @Test
    fun isGemma4ModelName_rejectsFortyLikePrefixes() {
        // "gemma-40b" のような将来の 40 系は 4 系に誤判定しない (負の先読みの確認)
        assertFalse(ModelNameHeuristics.isGemma4ModelName("gemma-40b-it"))
    }

    // ---- isGemma4Model (cloud ID / パス正規化) ----

    @Test
    fun isGemma4Model_stripsCloudPrefixBeforeMatching() {
        assertTrue(ModelNameHeuristics.isGemma4Model("cloud:ollama:gemma-4-e4b"))
        assertTrue(ModelNameHeuristics.isGemma4Model("cloud:lmstudio:gemma-4-27b-it"))
        // プロバイダ側の名前に gemma が含まれていても、実モデル名で判定する
        assertFalse(ModelNameHeuristics.isGemma4Model("cloud:gemma-host:qwen3-14b"))
    }

    @Test
    fun isGemma4Model_usesOnlyFileNameForLocalPaths() {
        assertTrue(ModelNameHeuristics.isGemma4Model("/models/gemma-4-e2b-it.gguf"))
        // 親ディレクトリ名に gemma4 があってもファイル名が Gemma4 でなければ false
        assertFalse(ModelNameHeuristics.isGemma4Model("/models/gemma4-backup/qwen3-14b.gguf"))
    }

    @Test
    fun isGemma4Model_blankIsFalse() {
        assertFalse(ModelNameHeuristics.isGemma4Model(""))
        assertFalse(ModelNameHeuristics.isGemma4Model("   "))
    }

    // ---- resolveModelNameForCheck ----

    @Test
    fun resolveModelNameForCheck_extractsCloudModelName() {
        assertEquals(
            "qwen3-14b",
            ModelNameHeuristics.resolveModelNameForCheck("cloud:openai_compat:qwen3-14b")
        )
        // モデル名に ':' を含む場合は先頭の provider 部分だけ剥がす
        assertEquals(
            "gemma-4:e4b",
            ModelNameHeuristics.resolveModelNameForCheck("cloud:ollama:gemma-4:e4b")
        )
    }

    @Test
    fun resolveModelNameForCheck_handlesCloudWithoutModel() {
        // モデル名が空なら入力をそのまま返す (誤判定は isGemma4ModelName 側の "gemma" 要件で防がれる)
        assertEquals("cloud:ollama", ModelNameHeuristics.resolveModelNameForCheck("cloud:ollama"))
    }

    @Test
    fun resolveModelNameForCheck_extractsFileNameFromPath() {
        assertEquals(
            "model.gguf",
            ModelNameHeuristics.resolveModelNameForCheck("/data/models/model.gguf")
        )
        assertEquals(
            "model.gguf",
            ModelNameHeuristics.resolveModelNameForCheck("C:\\models\\model.gguf")
        )
        assertEquals("plain-name", ModelNameHeuristics.resolveModelNameForCheck("plain-name"))
    }

    // ---- isQwen35OrLaterModelName ----

    @Test
    fun isQwen35OrLater_matchesQwen35AndNewer() {
        listOf(
            "qwen3.5-2b-instruct",
            "qwen-3.6-4b",
            "qwen3.9-0.6b",
            "qwen4-8b",
            "qwen4.1-3b",
            "qwen-4.0-1.7b-q4_k_m.gguf",
        ).forEach { name ->
            assertTrue("$name should be Qwen 3.5+", ModelNameHeuristics.isQwen35OrLaterModelName(name))
        }
    }

    @Test
    fun isQwen35OrLater_rejectsQwen30To34AndOlder() {
        listOf(
            "qwen3-14b",
            "qwen-3.2-4b-instruct",
            "qwen3.4-7b",
            "qwen2.5-7b-instruct",
            "qwen2-72b",
            "qwen-max", // バージョン不明
            "qwq-32b", // QwQ は Qwen 系列だが別扱い
        ).forEach { name ->
            assertFalse("$name should NOT be Qwen 3.5+", ModelNameHeuristics.isQwen35OrLaterModelName(name))
        }
    }

    @Test
    fun isQwen35OrLater_paramCountNotationIsNotMinorVersion() {
        // "qwen3.4b" は "3.4" がマイナーバージョンではなく 4B パラメータ表記
        assertFalse(ModelNameHeuristics.isQwen35OrLaterModelName("qwen3.4b"))
        // "qwen3.5b-instruct" も小数点直後が 'b' で終わるためパラメータ表記とみなし、
        // 旧実装と同じく Qwen 3.5+ とは判定しない (誤って prefill 経路に流さないための保守的判定)
        assertFalse(ModelNameHeuristics.isQwen35OrLaterModelName("qwen3.5b-instruct"))
    }

    // ---- isQwenSoftSwitchCompatibleModelName ----

    @Test
    fun isQwenSoftSwitchCompatible_matchesQwen30To34() {
        listOf(
            "qwen3-14b",
            "qwen-3-32b",
            "qwen3.2-4b-instruct",
            "qwen-3.4-7b",
            "qwen3.4b", // パラメータ表記の 3.4b は 3 系としてソフトスイッチ対象
        ).forEach { name ->
            assertTrue(
                "$name should be soft-switch compatible",
                ModelNameHeuristics.isQwenSoftSwitchCompatibleModelName(name)
            )
        }
    }

    @Test
    fun isQwenSoftSwitchCompatible_rejectsUnsupported() {
        listOf(
            "qwen3.5-2b",      // ソフトスイッチ廃止世代
            "qwen4-8b",        // 4 系以降
            "qwen2.5-7b",      // thinking 非対応世代
            "qwq-32b",         // thinking 常時 ON、スイッチなし
            "qwen-max",        // バージョン不明は注入しない
            "qwen-plus",
            "llama-3.2-3b",    // Qwen ではない
        ).forEach { name ->
            assertFalse(
                "$name should NOT be soft-switch compatible",
                ModelNameHeuristics.isQwenSoftSwitchCompatibleModelName(name)
            )
        }
    }

    // ---- isLfmModelName ----

    @Test
    fun isLfmModelName_matchesLfmFamilies() {
        listOf(
            "lfm2.5-2.6b",
            "lfm2.5-1.2b-thinking",
            "lfm2-350m",
            "lfm-7b",
            "my-lfm2.5-model.gguf",
        ).forEach { name ->
            assertTrue("$name should be LFM", ModelNameHeuristics.isLfmModelName(name))
        }
    }

    @Test
    fun isLfmModelName_rejectsSimilarButNonLfm() {
        listOf(
            "lfmx-1b",          // lfm の直後が英字のものは別物
            "llama-3.2-3b",
            "golfmodel",        // 語中に lfm を含むが独立トークンではない
        ).forEach { name ->
            assertFalse("$name should NOT be LFM", ModelNameHeuristics.isLfmModelName(name))
        }
    }

    // ---- isGpt2ModelName ----

    @Test
    fun isGpt2ModelName_matchesGpt2Families() {
        listOf(
            "gpt2",
            "gpt-2",
            "gpt 2",
            "gpt2-medium",
        ).forEach { name ->
            assertTrue("$name should be GPT-2", ModelNameHeuristics.isGpt2ModelName(name))
        }
    }

    @Test
    fun isGpt2ModelName_rejectsNonGpt2() {
        listOf(
            "gpt-3.5-turbo",
            "gpt4all-j",
            "llama-3.2-3b",
            // "distilgpt2" は "gpt2" の直前が英字のため名前判定では false (旧実装と同一)。
            // app 側では GGUF メタデータのアーキテクチャ判定 (isGpt2ArchitectureHint) でカバーする。
            "distilgpt2",
        ).forEach { name ->
            assertFalse("$name should NOT be GPT-2", ModelNameHeuristics.isGpt2ModelName(name))
        }
    }

    @Test
    fun isGpt2Model_honorsArchitectureHint() {
        assertTrue(ModelNameHeuristics.isGpt2Model("some-random-model.gguf", isGpt2ArchitectureHint = true))
        assertFalse(ModelNameHeuristics.isGpt2Model("some-random-model.gguf", isGpt2ArchitectureHint = false))
    }

    // ---- resolveThinkingPromptStyle ----

    @Test
    fun resolveThinkingPromptStyle_routesEachFamily() {
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.PLAIN_COMPLETION,
            ModelNameHeuristics.resolveThinkingPromptStyle("gpt2-medium.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL,
            ModelNameHeuristics.resolveThinkingPromptStyle("qwen3.5-2b-instruct.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.QWEN_COMMAND,
            ModelNameHeuristics.resolveThinkingPromptStyle("qwen3-14b.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.GEMMA4_CHANNEL,
            ModelNameHeuristics.resolveThinkingPromptStyle("gemma-4-e4b-it.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.GEMMA_PREFIX,
            ModelNameHeuristics.resolveThinkingPromptStyle("gemma-3-4b-it.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG,
            ModelNameHeuristics.resolveThinkingPromptStyle("lfm2.5-1.2b-thinking.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG,
            ModelNameHeuristics.resolveThinkingPromptStyle("llama-3.2-3b-instruct.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG,
            ModelNameHeuristics.resolveThinkingPromptStyle("deepseek-r1-distill-llama-8b.gguf")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG,
            ModelNameHeuristics.resolveThinkingPromptStyle("qwq-32b.gguf")
        )
    }

    @Test
    fun resolveThinkingPromptStyle_userTemplateOverrideDisablesGpt2PlainCompletion() {
        // Bug fix(#42): ユーザーがテンプレートを明示選択している GPT-2 でも PLAIN_COMPLETION に強制しない
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.PLAIN_COMPLETION,
            ModelNameHeuristics.resolveThinkingPromptStyle("gpt2.gguf", hasExplicitUserTemplate = false)
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG,
            ModelNameHeuristics.resolveThinkingPromptStyle("gpt2.gguf", hasExplicitUserTemplate = true)
        )
    }

    @Test
    fun resolveThinkingPromptStyle_caseInsensitive() {
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.QWEN_COMMAND,
            ModelNameHeuristics.resolveThinkingPromptStyle("Qwen3-14B")
        )
        assertEquals(
            ModelNameHeuristics.ThinkingPromptStyle.GEMMA4_CHANNEL,
            ModelNameHeuristics.resolveThinkingPromptStyle("Gemma-4-E4B-IT")
        )
    }

    // ---- guessGgufFormat ----

    @Test
    fun guessGgufFormat_routesEachFamily() {
        assertEquals(PromptFormat.PlainCompletion, ModelNameHeuristics.guessGgufFormat("gpt2-medium.gguf"))
        assertEquals(PromptFormat.GemmaChat, ModelNameHeuristics.guessGgufFormat("gemma-3-4b-it.gguf"))
        // Bug fix(#45): Gemma 4 も GEMMA_CHAT (CHATML に振ると Thinking が発火しない)
        assertEquals(PromptFormat.GemmaChat, ModelNameHeuristics.guessGgufFormat("gemma-4-e4b-it.gguf"))
        assertEquals(PromptFormat.ChatMl, ModelNameHeuristics.guessGgufFormat("qwen3-14b.gguf"))
        assertEquals(PromptFormat.ChatMl, ModelNameHeuristics.guessGgufFormat("llama-3.2-3b-instruct.gguf"))
    }

    @Test
    fun guessGgufFormat_userTemplateOverrideDisablesGpt2PlainCompletion() {
        assertEquals(
            PromptFormat.PlainCompletion,
            ModelNameHeuristics.guessGgufFormat("gpt2.gguf", hasExplicitUserTemplate = false)
        )
        assertEquals(
            PromptFormat.ChatMl,
            ModelNameHeuristics.guessGgufFormat("gpt2.gguf", hasExplicitUserTemplate = true)
        )
    }

    // ---- 派生ヘルパー ----

    @Test
    fun usesAssistantThinkingPrefill_coversPrefillStyles() {
        assertTrue(ModelNameHeuristics.usesAssistantThinkingPrefill("deepseek-r1-distill-llama-8b.gguf")) // ASSISTANT_TAG
        assertTrue(ModelNameHeuristics.usesAssistantThinkingPrefill("gemma-4-e4b-it.gguf"))               // GEMMA4_CHANNEL
        assertTrue(ModelNameHeuristics.usesAssistantThinkingPrefill("qwen3.5-2b.gguf"))                   // QWEN_ASSISTANT_PREFILL
        assertFalse(ModelNameHeuristics.usesAssistantThinkingPrefill("gemma-3-4b-it.gguf"))               // GEMMA_PREFIX
        assertFalse(ModelNameHeuristics.usesAssistantThinkingPrefill("gpt2-medium.gguf"))                 // PLAIN_COMPLETION
    }

    @Test
    fun usesQwenStyleThinking_coversBothQwenGenerations() {
        assertTrue(ModelNameHeuristics.usesQwenStyleThinking("qwen3-14b.gguf"))
        assertTrue(ModelNameHeuristics.usesQwenStyleThinking("qwen3.5-2b.gguf"))
        assertFalse(ModelNameHeuristics.usesQwenStyleThinking("qwen2.5-7b.gguf"))
        assertFalse(ModelNameHeuristics.usesQwenStyleThinking("gemma-3-4b-it.gguf"))
        assertFalse(ModelNameHeuristics.usesQwenStyleThinking("llama-3.2-3b.gguf"))
    }
}
