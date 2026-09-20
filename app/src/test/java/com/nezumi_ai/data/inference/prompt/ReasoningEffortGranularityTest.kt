package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ReasoningEffortGranularity
import com.nezumi_ai.presentation.ui.screen.availableEffortLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat_template の `reasoning_effort` 解釈粒度パーサ
 * ([ModelNameHeuristics.parseReasoningEffortGranularity]) と、
 * UI 選択肢生成 (availableEffortLevels) の仕様テスト。
 */
class ReasoningEffortGranularityTest {

    /**
     * Granite 4.2 (granite-4.2-3b) の chat_template からの抜粋。
     * `reasoning_effort` を "low" かどうかの二値としてしか解釈しない。
     */
    private val granite42Template = """
        {%- set low_effort = low_effort if low_effort is defined else False %}
        {%- if reasoning_effort is defined and reasoning_effort is not none %}
            {%- set low_effort = reasoning_effort == "low" %}
        {%- endif %}
        {%- for message in messages %}
            {%- if message.role == "user" and loop.index0 == ns.last_user_idx and low_effort %}
                {{- content + '\n\n{reasoning effort: low}' }}
            {%- else %}
                {{- content }}
            {%- endif %}
        {%- endfor %}
    """.trimIndent()

    @Test
    fun granite42Template_isDetectedAsBinary() {
        val result = ModelNameHeuristics.parseReasoningEffortGranularity(granite42Template)
        assertEquals(ReasoningEffortGranularity.Binary, result)
        // BINARY は Low 1 択 (+ Thinking OFF) のみ意味を持つ。
        assertEquals(listOf("low"), ModelNameHeuristics.supportedEffortLevels(result))
    }

    @Test
    fun templateWithoutReasoningEffort_isDetectedAsNone() {
        val dummy = """
            {%- for message in messages %}
            {{- '<|im_start|>' + message.role + '\n' + message.content + '<|im_end|>\n' }}
            {%- endfor %}
            {%- if add_generation_prompt %}
            {{- '<|im_start|>assistant\n' }}
            {%- endif %}
        """.trimIndent()
        val result = ModelNameHeuristics.parseReasoningEffortGranularity(dummy)
        assertEquals(ReasoningEffortGranularity.None, result)
        // NONE は effort 選択肢なし (Thinking ON/OFF トグルのみ意味を持つ)。
        assertTrue(ModelNameHeuristics.supportedEffortLevels(result).isEmpty())
    }

    @Test
    fun templateComparingThreeLevels_isDetectedAsGraded() {
        val dummy = """
            {%- if reasoning_effort is defined and reasoning_effort is not none %}
                {%- if reasoning_effort == "low" %}
                    {%- set tag = "low" %}
                {%- elif reasoning_effort == "medium" %}
                    {%- set tag = "medium" %}
                {%- elif reasoning_effort == "high" %}
                    {%- set tag = "high" %}
                {%- endif %}
            {%- endif %}
        """.trimIndent()
        val result = ModelNameHeuristics.parseReasoningEffortGranularity(dummy)
        assertEquals(
            ReasoningEffortGranularity.Graded(setOf("low", "medium", "high")),
            result
        )
        assertEquals(
            listOf("low", "medium", "high"),
            ModelNameHeuristics.supportedEffortLevels(result)
        )
    }

    @Test
    fun singleQuotedComparison_isDetected() {
        val dummy = "{%- if reasoning_effort == 'low' %}a{%- endif %}"
        assertEquals(
            ReasoningEffortGranularity.Binary,
            ModelNameHeuristics.parseReasoningEffortGranularity(dummy)
        )
    }

    @Test
    fun blankTemplate_isDetectedAsNone() {
        assertEquals(
            ReasoningEffortGranularity.None,
            ModelNameHeuristics.parseReasoningEffortGranularity("")
        )
    }

    // ---- UI 選択肢生成 (AttachmentOptionsSheet.availableEffortLevels) ----

    @Test
    fun uiLevels_binaryShowsOnlyLow() {
        assertEquals(
            listOf("low"),
            availableEffortLevels(ReasoningEffortGranularity.Binary)
        )
    }

    @Test
    fun uiLevels_noneShowsNothing() {
        assertEquals(
            emptyList<String>(),
            availableEffortLevels(ReasoningEffortGranularity.None)
        )
    }

    @Test
    fun uiLevels_gradedShowsDetectedSetInPreferredOrder() {
        assertEquals(
            listOf("low", "medium", "high"),
            availableEffortLevels(ReasoningEffortGranularity.Graded(setOf("high", "low", "medium")))
        )
        // 3 値とは限らない (例: minimal / low のみ比較するテンプレート)。
        assertEquals(
            listOf("low", "minimal"),
            availableEffortLevels(ReasoningEffortGranularity.Graded(setOf("low", "minimal")))
        )
    }
}
