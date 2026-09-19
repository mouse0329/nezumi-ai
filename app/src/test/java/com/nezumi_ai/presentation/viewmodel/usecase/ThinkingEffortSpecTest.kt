package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.presentation.ui.screen.effortSegmentsAlpha
import com.nezumi_ai.utils.PreferencesHelper
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 添付シートのシンキング トグル / エフォートセグメントに関する仕様テスト。
 *
 * - 既定値は Thinking ON / effort Low (モック仕様)。
 * - 思考強度は Thinking OFF でも切り替え可能 (セグメントは常に不透過)。
 * - applyReasoningEffort はテンプレート非対応 (supportsEffort=false) では pass-through、
 *   対応時は最後の user ターン末尾に `{reasoning effort: <level>}` を追記する。
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
    fun effortSegmentsAlpha_offKeepsFullOpacity() {
        // 要望変更: OFF でも切り替え可能になったため常に不透過。
        assertEquals(1.0f, effortSegmentsAlpha(thinkingOn = false))
        assertEquals(1.0f, effortSegmentsAlpha(thinkingOn = true))
    }

    @Test
    fun applyReasoningEffort_appendsMarkerToLastUserTurn() {
        val useCase = PromptBuildingUseCase()
        val prompt = "<|im_start|>user\nこんにちは<|im_end|>\n<|im_start|>assistant\n"
        val result = useCase.applyReasoningEffort(prompt, "low", supportsEffort = true)
        // 最後の user ターンの閉じタグ手前にマーカーが入る。
        assertEquals(
            "<|im_start|>user\nこんにちは\n{reasoning effort: low}\n<|im_end|>\n<|im_start|>assistant\n",
            result
        )
    }

    @Test
    fun applyReasoningEffort_noopWhenTemplateUnsupported() {
        // テンプレート非対応 (supportsEffort=false) ではプロンプトを一切変更しない。
        val useCase = PromptBuildingUseCase()
        val prompt = "user: hello"
        listOf("low", "medium", "high", "").forEach { level ->
            assertEquals(prompt, useCase.applyReasoningEffort(prompt, level, supportsEffort = false))
        }
    }
}
