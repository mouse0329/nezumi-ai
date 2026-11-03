package com.nezumi_ai.data.inference.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudThinkingEffortTest {

    @Test
    fun litertAndLmStudioHaveNoEffortLevels() {
        assertTrue(CloudThinkingEffort.levelsFor(CloudApiKeyStore.Provider.LM_STUDIO).isEmpty())
        assertTrue(CloudThinkingEffort.levelsForModel("/sdcard/model.litertlm").isEmpty())
        assertTrue(CloudThinkingEffort.levelsForModel("Gemma4-2B").isEmpty())
    }

    @Test
    fun eachCloudServiceExposesOnlyLevelsTheModelAccepts() {
        // 仕様未取得の Ollama には low/medium/high を出さない (送ると無視される)。
        assertTrue(CloudThinkingEffort.levelsForModel("cloud:ollama-local:qwen3").isEmpty())
        assertFalse(CloudThinkingEffort.controlForModel("cloud:ollama-local:qwen3").showToggle)
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:ollama-local:gpt-oss:20b")
        )
        assertFalse(CloudThinkingEffort.controlForModel("cloud:ollama-local:gpt-oss:20b").canDisable)
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:gemini:gemini-2.5-flash")
        )
        assertTrue(CloudThinkingEffort.controlForModel("cloud:gemini:gemini-2.5-flash").canDisable)
        assertFalse(CloudThinkingEffort.controlForModel("cloud:gemini:gemini-2.5-pro").canDisable)
        assertEquals(
            listOf("minimal", "low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:gemini:gemini-3-flash")
        )
        assertEquals(
            listOf("low", "high"),
            CloudThinkingEffort.levelsForModel("cloud:gemini:gemini-3-pro")
        )
        assertFalse(CloudThinkingEffort.controlForModel("cloud:gemini:gemini-2.0-flash").showToggle)
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:openai:gpt-5")
        )
        assertFalse(CloudThinkingEffort.controlForModel("cloud:openai:gpt-4o").showToggle)
        // Sonnet 4.5 は budget。Opus 4.5 は effort に max が無い。Haiku 3.5 は非対応。
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:claude:claude-sonnet-4-5")
        )
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:claude:claude-opus-4-5")
        )
        assertEquals(
            listOf("low", "medium", "high", "max"),
            CloudThinkingEffort.levelsForModel("cloud:claude:claude-opus-4-6")
        )
        assertFalse(CloudThinkingEffort.controlForModel("cloud:claude:claude-3-5-haiku-latest").showToggle)
        assertTrue(CloudThinkingEffort.levelsForModel("cloud:lmstudio:qwen3").isEmpty())
        assertFalse(CloudThinkingEffort.controlForModel("cloud:lmstudio:qwen3").showToggle)
    }

    @Test
    fun ollamaThinkValue_usesShowSpecNotGenericLevels() {
        val qwen = CloudThinkingEffort.OllamaThinkingSpec(listOf("true", "false"))
        assertEquals(false, CloudThinkingEffort.ollamaThinkValue(false, "high", qwen, "qwen3"))
        assertEquals(true, CloudThinkingEffort.ollamaThinkValue(true, "high", qwen, "qwen3"))
        val levels = CloudThinkingEffort.OllamaThinkingSpec(listOf("false", "low", "medium", "xhigh"), "medium")
        assertEquals(false, CloudThinkingEffort.ollamaThinkValue(false, "high", levels, "qwen3.8"))
        assertEquals("xhigh", CloudThinkingEffort.ollamaThinkValue(true, "xhigh", levels, "qwen3.8"))
        assertEquals("low", CloudThinkingEffort.ollamaThinkValue(true, "high", levels, "qwen3.8"))
        assertEquals(null, CloudThinkingEffort.ollamaThinkValue(true, "high", null, "qwen3"))
        assertEquals("high", CloudThinkingEffort.ollamaThinkValue(true, "high", null, "gpt-oss"))
    }

    @Test
    fun providerGates_doNotAttachUnsupportedParams() {
        assertFalse(CloudThinkingEffort.geminiSupportsThinkingLevel("gemini-2.0-flash"))
        assertFalse(CloudThinkingEffort.geminiSupportsThinkingLevel("gemini-2.5-flash"))
        assertTrue(CloudThinkingEffort.geminiSupportsThinkingLevel("gemini-3-flash"))
        assertFalse(CloudThinkingEffort.openaiSupportsReasoningEffort("gpt-4o"))
        assertTrue(CloudThinkingEffort.openaiSupportsReasoningEffort("gpt-5"))
        assertFalse(CloudThinkingEffort.claudeSupportsEffort("claude-3-5-haiku-latest"))
        assertFalse(CloudThinkingEffort.claudeSupportsEffort("claude-sonnet-4-5"))
        assertTrue(CloudThinkingEffort.claudeSupportsEffort("claude-opus-4-5"))
        assertTrue(CloudThinkingEffort.claudeSupportsEffort("claude-opus-4-6"))
        assertEquals(1023, CloudThinkingEffort.claudeBudgetTokens("high", 1024))
        assertEquals(0, CloudThinkingEffort.geminiBudgetTokens("gemini-2.5-flash", false, "high"))
        assertEquals(1024, CloudThinkingEffort.geminiBudgetTokens("gemini-2.5-pro", false, "low"))
    }
}
