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
    fun eachCloudServiceExposesItsOwnEffortLevels() {
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:ollama-local:qwen3")
        )
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:gemini:gemini-2.5-flash")
        )
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:openai:gpt-5")
        )
        assertEquals(
            listOf("low", "medium", "high"),
            CloudThinkingEffort.levelsForModel("cloud:claude:claude-sonnet-4-5")
        )
        assertTrue(CloudThinkingEffort.levelsForModel("cloud:lmstudio:qwen3").isEmpty())
    }

    @Test
    fun ollamaThinkValue_offIsFalse_onIsLevel() {
        assertEquals(false, CloudThinkingEffort.ollamaThinkValue(false, "high"))
        assertEquals("high", CloudThinkingEffort.ollamaThinkValue(true, "high"))
        assertEquals("low", CloudThinkingEffort.ollamaThinkValue(true, "nope"))
    }

    @Test
    fun providerGates_doNotAttachUnsupportedParams() {
        assertFalse(CloudThinkingEffort.geminiSupportsThinkingLevel("gemini-2.0-flash"))
        assertTrue(CloudThinkingEffort.geminiSupportsThinkingLevel("gemini-2.5-flash"))
        assertFalse(CloudThinkingEffort.openaiSupportsReasoningEffort("gpt-4o"))
        assertTrue(CloudThinkingEffort.openaiSupportsReasoningEffort("gpt-5"))
        assertFalse(CloudThinkingEffort.claudeSupportsEffort("claude-3-5-haiku-latest"))
        assertTrue(CloudThinkingEffort.claudeSupportsEffort("claude-opus-4.8"))
        assertEquals(1023, CloudThinkingEffort.claudeBudgetTokens("high", 1024))
    }
}
