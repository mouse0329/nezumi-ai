package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.ToolCallTags
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ThinkingPromptStyle

/**
 * [ThinkingPromptStyle] ごとの
 *   - assistant プレフィル
 *   - グローバルプレフィックス
 *   - Qwen `/think` `/no_think` ソフトスイッチ差込
 * を 1 箇所に集約する表 (app 側旧 `ThinkingPromptStyleSpec` の shared 移行版)。
 *
 * 旧実装は `PromptBuilder.ThinkingPromptStyle` を参照していたが、Phase 2 で
 * `ModelNameHeuristics.ThinkingPromptStyle` に統合されたため、こちらは
 * shared モジュールの型だけを見る純粋関数群として再定義する
 * (app 側 `ThinkingPromptStyleSpec` は Phase 3 の GgufRenderer 移行完了後に削除)。
 *
 * この Spec 自体は純粋関数で副作用を持たず、テストで直接検証できる。
 */
object ThinkingStyleSpec {

    /** assistant ターン直後に必ず入れるプレフィル文字列。空文字は「プレフィル無し」。 */
    fun assistantPrefill(
        style: ThinkingPromptStyle,
        enableThinking: Boolean
    ): String = when {
        // Qwen 系の OFF: 空 <think></think> を必ず先入れして chat_template の暴発を封じる。
        //   - QWEN_COMMAND (Qwen 3.0-3.4): ソフトスイッチと同時に prefill も入れると効果最強。
        //   - QWEN_ASSISTANT_PREFILL (Qwen 3.5+): 公式 non-thinking jinja そのまま。
        !enableThinking && (
            style == ThinkingPromptStyle.QWEN_COMMAND ||
                style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            ) -> ToolCallTags.QWEN_EMPTY_THINK_PREFILL
        // Thinking 発火側: <think>\n を assistant 直後に入れる。
        //   ASSISTANT_TAG (DeepSeek-R1 / QwQ)、GEMMA4_CHANNEL (Gemma 4 GGUF)、
        //   QWEN_ASSISTANT_PREFILL (Qwen 3.5+) が対象。
        enableThinking && (
            style == ThinkingPromptStyle.ASSISTANT_TAG ||
                style == ThinkingPromptStyle.GEMMA4_CHANNEL ||
                style == ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            ) -> ToolCallTags.ASSISTANT_THINK_PREFILL
        else -> ""
    }

    /**
     * プロンプト全体の先頭に置くプレフィックス。GEMMA_PREFIX のみ `<|think|>\n` を先頭に置く。
     * GEMMA4_CHANNEL はシステムターン内に埋めるためここでは何も返さない。
     */
    fun globalPrefix(
        style: ThinkingPromptStyle,
        enableThinking: Boolean
    ): String = if (enableThinking && style == ThinkingPromptStyle.GEMMA_PREFIX) {
        ToolCallTags.GEMMA_THINK_PREFIX
    } else {
        ""
    }

    /**
     * 直近 user メッセージ末尾に `/think` / `/no_think` を差し込むべきかどうか。
     * QWEN_COMMAND (Qwen 3.0-3.4) のみ true。他のスタイルでは false。
     */
    fun usesQwenSoftSwitch(style: ThinkingPromptStyle): Boolean =
        style == ThinkingPromptStyle.QWEN_COMMAND

    /** QWEN_COMMAND スタイルで差し込む directive 文字列。 */
    fun qwenSoftSwitchDirective(enableThinking: Boolean): String =
        if (enableThinking) ToolCallTags.QWEN_THINK_COMMAND else ToolCallTags.QWEN_NO_THINK_COMMAND

    /**
     * GEMMA4_CHANNEL のとき、システムターン内に `<|think|>` を埋めるかどうか。
     * enableThinking=false のときは false、true のときのみ true。
     */
    fun injectsGemma4SystemThinkTrigger(
        style: ThinkingPromptStyle,
        enableThinking: Boolean
    ): Boolean = style == ThinkingPromptStyle.GEMMA4_CHANNEL && enableThinking
}
