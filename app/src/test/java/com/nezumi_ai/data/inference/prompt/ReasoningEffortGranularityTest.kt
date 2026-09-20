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
        // BINARY は "default" (未指定 = フル思考) と "low" の 2 択を持つ。
        // "low" だけだとデフォルトのフル思考に戻す手段が UI から失われるため。
        assertEquals(listOf("default", "low"), ModelNameHeuristics.supportedEffortLevels(result))
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
    fun uiLevels_binaryShowsDefaultAndLow() {
        // "low" だけだとフル思考 (デフォルト) に戻す手段が UI から失われるため、
        // "default" (未指定) を含めた 2 択にする。
        assertEquals(
            listOf("default", "low"),
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

    // ---- Qwen3.5 系の実測回帰テスト ----

    /**
     * Qwen3.5-2B (unsloth GGUF, Q4_K_M / Q6_K いずれも同一) の chat_template 抜粋。
     * `enable_thinking` の ON/OFF のみを解釈し、`reasoning_effort` には
     * 一切言及がない。Qwen3.6 も同様に非対応であることが公に報告されている
     * (QwenLM/Qwen3.8#217 のコメント参照)。reasoning_effort 対応は
     * Qwen3.8 から導入された。
     */
    private val qwen35Template = """
        {%- if add_generation_prompt %}
            {{- '<|im_start|>assistant\n' }}
            {%- if enable_thinking is defined and enable_thinking is true %}
                {{- '<think>\n' }}
            {%- else %}
                {{- '<think>\n\n</think>\n\n' }}
            {%- endif %}
        {%- endif %}
    """.trimIndent()

    @Test
    fun qwen35Template_isDetectedAsNone() {
        // reasoning_effort への言及が一切ないため None (effort 概念なし)。
        // Binary や Graded と誤判定すると、効果のない Low/Medium/High を
        // UI に表示してしまう (HuggingChat で観測された "選べるが効かない" 問題)。
        val result = ModelNameHeuristics.parseReasoningEffortGranularity(qwen35Template)
        assertEquals(ReasoningEffortGranularity.None, result)
        assertTrue(ModelNameHeuristics.supportedEffortLevels(result).isEmpty())
    }

    @Test
    fun usesThinkingEffortVariable_excludesQwen35And36() {
        // 実測で reasoning_effort 非対応と判明した 3.5 / 3.6 系は
        // 名前ヒューリスティックの対象から除外する
        // (テンプレートが読めない場合のフォールバックであっても
        // 効果のない選択肢を UI に出さないため)。
        assertTrue(!ModelNameHeuristics.usesThinkingEffortVariable("Qwen3.5-2B"))
        assertTrue(!ModelNameHeuristics.usesThinkingEffortVariable("qwen3.6-27b-dwq46"))
    }

    @Test
    fun usesThinkingEffortVariable_includesQwen38AndLater() {
        // Qwen3.8 系は reasoning_effort ∈ {low, medium, xhigh} を実際に解釈する。
        assertTrue(ModelNameHeuristics.usesThinkingEffortVariable("Qwen3.8-27B-AWQ-INT4"))
        assertTrue(ModelNameHeuristics.usesThinkingEffortVariable("qwen4-something"))
    }

    // ---- `in (...)` タプル所属チェック形式のテンプレート ----

    /**
     * prism-ml/Ternary-Bonsai-2-27B-gguf の chat_template 抜粋。
     * general.architecture は "qwen35" のままだが、サードパーティによる
     * カスタムテンプレートで reasoning_effort ∈ {xhigh, medium, low} の
     * タプル所属チェックを実装しており、範囲外の値は raise_exception する。
     * "==" 比較ではなく "not in (...)" 構文である点が Granite / Qwen3.8 とは異なる。
     */
    private val ternaryBonsaiTemplate = """
        {%- if enable_thinking is undefined or enable_thinking is true %}
            {%- set resolved_reasoning_effort = reasoning_effort|default('xhigh') %}
            {%- if resolved_reasoning_effort not in ('xhigh', 'medium', 'low') %}
                {{- raise_exception('Unexpected reasoning effort ' ~ reasoning_effort ~ '. Supported types are xhigh (default), medium, and low.') }}
            {%- endif %}
        {%- endif %}
    """.trimIndent()

    @Test
    fun tupleInComparison_isDetectedAsGradedWithXhigh() {
        // "==" 比較用の正規表現だけでは levels が空になり、誤って従来互換の
        // 3 値 {low, medium, high} にフォールバックしてしまう (xhigh を見落とし、
        // 存在しない "high" を選択肢に出してしまう) ため、"in (...)" 構文も
        // 検出できる必要がある。
        val result = ModelNameHeuristics.parseReasoningEffortGranularity(ternaryBonsaiTemplate)
        assertEquals(ReasoningEffortGranularity.Graded(setOf("xhigh", "medium", "low")), result)
        assertEquals(
            listOf("low", "medium", "xhigh"),
            ModelNameHeuristics.supportedEffortLevels(result)
        )
    }

    @Test
    fun tupleInComparison_normalizeGuardsAgainstUnsupportedValue() {
        // UI/保存値に残る旧来の "high" のような、このテンプレートには存在しない値は
        // normalizeThinkingEffortForLevels 相当のガードで許可リスト内にフォールバック
        // させる必要がある (直接 raise_exception を踏むと該当ターンの生成が失敗する)。
        val granularity = ModelNameHeuristics.parseReasoningEffortGranularity(ternaryBonsaiTemplate)
        val supported = ModelNameHeuristics.supportedEffortLevels(granularity)
        assertTrue("high" !in supported)
        assertTrue("xhigh" in supported)
    }
}
