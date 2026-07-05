package com.nezumi_ai.sd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDreamModuleTest {
    @Test
    fun resolveEffectiveUseOpenCL_keepsUserPreferenceRegardlessOfBackend() {
        assertTrue(LocalDreamModule.resolveEffectiveUseOpenCL(true, "mnn"))
        assertTrue(LocalDreamModule.resolveEffectiveUseOpenCL(true, "cpu"))
        assertTrue(LocalDreamModule.resolveEffectiveUseOpenCL(true, "gpu"))
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(false, "mnn"))
    }
}
