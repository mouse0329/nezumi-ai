package com.nezumi_ai.data.inference

import com.nezumi_ai.data.inference.ToolCallTags

/**
 * [PromptBuilder.ThinkingPromptStyle] ごとの
 *   - assistant プレフィル
 *   - グローバルプレフィックス
 *   - Qwen `/think` `/no_think` ソフトスイッチ差込
 *   - `{{ if .Thinking }}` テンプレ変数 ON/OFF
 * を 1 箇所に集約する。
 *
 * Bug fix: 以前は `PromptBuilder` の `assistantPrefillFor` / `thinkingGlobalPrefix` /
 * `decorateHistoryForThinkingStyle` / `buildForGgufGemma` / `buildForGgufChatMl` /
 * `buildWithCustomTemplate` の 6 箇所で同じ style flag を when 分岐していた。
 * どこか 1 箇所を直し忘れて Thinking ON/OFF の挙動が食い違う恐れがあったので、
 * 「style から Spec を引く」 1 段挟む形にして分岐を単一の表にまとめる。
 *
 * この Spec 自体は純粋関数で副作用を持たず、テストで直接検証できる。
 */
internal object ThinkingPromptStyleSpec {

    /** assistant ターン直後に必ず入れるプレフィル文字列。空文字は「プレフィル無し」。 */
    fun assistantPrefill(
        style: PromptBuilder.ThinkingPromptStyle,
        enableThinking: Boolean
    ): String = when {
        // Qwen 系の OFF: 空 <think></think> を必ず先入れして chat_template の暴発を封じる。
        //   - QWEN_COMMAND (Qwen 3.0-3.4): ソフトスイッチと同時に prefill も入れると効果最強。
        //   - QWEN_ASSISTANT_PREFILL (Qwen 3.5+): 公式 non-thinking jinja そのまま。
        !enableThinking && (
            style == PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND ||
                style == PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            ) -> ToolCallTags.QWEN_EMPTY_THINK_PREFILL
        // Thinking 発火側: <think>\n を assistant 直後に入れる。
        //   ASSISTANT_TAG (DeepSeek-R1 / QwQ)、GEMMA4_CHANNEL (Gemma 4 GGUF)、
        //   QWEN_ASSISTANT_PREFILL (Qwen 3.5+) が対象。
        enableThinking && (
            style == PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG ||
                style == PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL ||
                style == PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            ) -> ToolCallTags.ASSISTANT_THINK_PREFILL
        else -> ""
    }

    /**
     * プロンプト全体の先頭に置くプレフィックス。GEMMA_PREFIX のみ `<|think|>\n` を先頭に置く。
     * GEMMA4_CHANNEL はシステムターン内に埋めるためここでは何も返さない。
     */
    fun globalPrefix(
        style: PromptBuilder.ThinkingPromptStyle,
        enableThinking: Boolean
    ): String = if (enableThinking && style == PromptBuilder.ThinkingPromptStyle.GEMMA_PREFIX) {
        ToolCallTags.GEMMA_THINK_PREFIX
    } else {
        ""
    }

    /** `{{ if .Thinking }}` テンプレ変数の値。 */
    fun thinkingTemplateFlag(
        style: PromptBuilder.ThinkingPromptStyle,
        enableThinking: Boolean
    ): Boolean = enableThinking && (
        style == PromptBuilder.ThinkingPromptStyle.ASSISTANT_TAG ||
            style == PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL ||
            style == PromptBuilder.ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
        )

    /**
     * 直近 user メッセージ末尾に `/think` / `/no_think` を差し込むべきかどうか。
     * QWEN_COMMAND (Qwen 3.0-3.4) のみ true。他のスタイルでは false。
     */
    fun usesQwenSoftSwitch(style: PromptBuilder.ThinkingPromptStyle): Boolean =
        style == PromptBuilder.ThinkingPromptStyle.QWEN_COMMAND

    /** QWEN_COMMAND スタイルで差し込む directive 文字列。 */
    fun qwenSoftSwitchDirective(enableThinking: Boolean): String =
        if (enableThinking) ToolCallTags.QWEN_THINK_COMMAND else ToolCallTags.QWEN_NO_THINK_COMMAND

    /**
     * GEMMA4_CHANNEL のとき、システムターン内に `<|think|>` を埋めるかどうか。
     * enableThinking=false のときは false、true のときのみ true。
     */
    fun injectsGemma4SystemThinkTrigger(
        style: PromptBuilder.ThinkingPromptStyle,
        enableThinking: Boolean
    ): Boolean = style == PromptBuilder.ThinkingPromptStyle.GEMMA4_CHANNEL && enableThinking
}
