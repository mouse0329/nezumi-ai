package com.nezumi_ai.sd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDreamModuleTest {
    @Test
    fun shouldDisableNativeServerForTests_onlyWhenExplicitlyRequestedInDebugBuild() {
        assertFalse(LocalDreamModule.shouldDisableNativeServerForTests(isDebugBuild = true, systemProperty = null))
        assertFalse(LocalDreamModule.shouldDisableNativeServerForTests(isDebugBuild = true, systemProperty = "false"))
        assertTrue(LocalDreamModule.shouldDisableNativeServerForTests(isDebugBuild = true, systemProperty = "true"))
        assertFalse(LocalDreamModule.shouldDisableNativeServerForTests(isDebugBuild = false, systemProperty = "true"))
    }

    @Test
    fun resolveEffectiveUseOpenCL_respectsUserPreferenceOnMnnBackendAtSafeSize() {
        // MNN (CPU) + 安全な解像度 (<=448) では、ユーザー希望値がそのまま反映される。
        assertTrue(LocalDreamModule.resolveEffectiveUseOpenCL(true, "mnn", 384))
        assertTrue(LocalDreamModule.resolveEffectiveUseOpenCL(true, "cpu", 448))
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(false, "mnn", 384))
    }

    @Test
    fun resolveEffectiveUseOpenCL_forcedOffAtOrAbove512() {
        // 512 以上では OpenCL を強制オフにする (mobile GPU の driver abort 回避)。
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(true, "mnn", 512))
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(true, "cpu", 640))
    }

    @Test
    fun resolveEffectiveUseOpenCL_forcedOffOnQnnBackend() {
        // QNN (GPU/NPU) バックエンド利用時は OpenCL パスに入らない。
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(true, "qnn", 256))
        assertFalse(LocalDreamModule.resolveEffectiveUseOpenCL(true, "qnn", 512))
    }
}
