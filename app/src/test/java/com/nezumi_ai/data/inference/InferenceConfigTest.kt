package com.nezumi_ai.data.inference

import org.junit.Assert.assertFalse
import org.junit.Test

class InferenceConfigTest {
    @Test
    fun contextCompressionIsDisabledByBuildFlagByDefault() {
        val config = InferenceConfig(contextCompressionEnabled = true)
        assertFalse(config.isContextCompressionEnabledForRuntime())
    }
}
