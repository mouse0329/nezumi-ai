package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.presentation.ui.screen.effortSegmentsAlpha
import com.nezumi_ai.utils.PreferencesHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 添付シートのシンキング トグル / エフォートセグメントに関する仕様テスト。
 *
 * - 既定値は Thinking ON / effort Low (モック仕様)。
 * - Thinking OFF 時にセグメントは非表示にせず薄くする (alpha 0.4)。
 * - applyReasoningEffort はエンジン側未配線のため pass-through
 *   (プロンプトを変更しない) であることを固定する。
 */
class ThinkingEffortSpecTest {

    @Test
    fun defaultThinkingEffort_isLow() {
        assertEquals("low", PreferencesHelper.DEFAULT_THINKING_EFFORT)
    }

    @Test
    fun effortLevels_useTemplateCompatibleLowercase() {
        // チャットテンプレ系の reasoning_effort 命名 (low / medium / high) と一致させる。
        assertEquals("low", PreferencesHelper.THINKING_EFFORT_LOW)
        assertEquals("medium", PreferencesHelper.THINKING_EFFORT_MEDIUM)
        assertEquals("high", PreferencesHelper.THINKING_EFFORT_HIGH)
    }

    @Test
    fun effortSegmentsAlpha_offDimsButDoesNotHide() {
        // OFF でも 0 ではない (非表示にしない) ことを保証する。
        assertEquals(0.4f, effortSegmentsAlpha(thinkingOn = false))
        assertEquals(1.0f, effortSegmentsAlpha(thinkingOn = true))
    }

    @Test
    fun applyReasoningEffort_isPassThroughUntilEngineWiring() {
        val useCase = PromptBuildingUseCase()
        val prompt = "user: hello"
        listOf("low", "medium", "high", "").forEach { level ->
            // エンジン未配線の間はプロンプトを一切変更しない。
            assertEquals(prompt, useCase.applyReasoningEffort(prompt, level))
        }
    }
}
