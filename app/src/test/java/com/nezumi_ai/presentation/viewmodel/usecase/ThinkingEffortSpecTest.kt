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
 * - applyReasoningEffort は実配線済みで、テンプレートの
 *   `{reasoning effort: low}` パターンを effort=low のときのみ挿入する。
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
    fun applyReasoningEffort_appendsLowEffortMarkerOnlyForLow() {
        val useCase = PromptBuildingUseCase()
        val prompt = "user: hello"
        // low: テンプレートの `{reasoning effort: low}` パターンが末尾に挿入される。
        assertEquals(
            "user: hello\n\n{reasoning effort: low}",
            useCase.applyReasoningEffort(prompt, "low")
        )
        // 大文字や空白混じりでも low と解釈される (保存値の揺れに対する後方互換)。
        assertEquals(
            "user: hello\n\n{reasoning effort: low}",
            useCase.applyReasoningEffort(prompt, " LOW ")
        )
        // 既にマーカーが挿入済み (ネイティブレンダラが処理済み) の場合は二重挿入しない。
        val alreadyMarked = "user: hello\n\n{reasoning effort: low}"
        assertEquals(alreadyMarked, useCase.applyReasoningEffort(alreadyMarked, "low"))
    }

    @Test
    fun applyReasoningEffort_nonLowEffortLeavesPromptUnchanged() {
        val useCase = PromptBuildingUseCase()
        val prompt = "user: hello"
        // medium / high / 空はマーカーを挿入しない (BINARY モデルでは
        // テンプレート上「指定なし」と同一に扱われるため)。
        listOf("medium", "high", "").forEach { level ->
            assertEquals(prompt, useCase.applyReasoningEffort(prompt, level))
        }
    }

    @Test
    fun normalizeThinkingEffort_clampsUnsupportedLevels() {
        // BINARY (low のみ有効) なモデルに対する保存済み medium / high は low に丸める。
        assertEquals(
            PreferencesHelper.THINKING_EFFORT_LOW,
            PreferencesHelper.normalizeThinkingEffortForLevels("medium", listOf("low"))
        )
        assertEquals(
            PreferencesHelper.THINKING_EFFORT_LOW,
            PreferencesHelper.normalizeThinkingEffortForLevels("high", listOf("low"))
        )
        // GRADED で有効な値はそのまま通す。
        assertEquals(
            PreferencesHelper.THINKING_EFFORT_HIGH,
            PreferencesHelper.normalizeThinkingEffortForLevels(
                "high",
                listOf("low", "medium", "high")
            )
        )
        // 空リスト (テンプレートが effort を解釈しない) でもクラッシュせず既定値に。
        assertEquals(
            PreferencesHelper.DEFAULT_THINKING_EFFORT,
            PreferencesHelper.normalizeThinkingEffortForLevels("medium", emptyList())
        )
    }
}
