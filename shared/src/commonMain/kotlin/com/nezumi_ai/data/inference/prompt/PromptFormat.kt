package com.nezumi_ai.data.inference.prompt

/**
 * GGUF モデルのプロンプト形式 (計画書 2.4)。
 *
 * 旧 `PromptBuilder.GgufPromptFormat` の移行先。`FormatResolver` (Phase 3) が
 * 「ユーザー指定テンプレート → 内蔵ヒューリスティック → モデル名推定」の
 * 優先順位で解決し、レンダラーはこの値だけで分岐する。
 */
sealed interface PromptFormat {

    /** Gemma 系: `<start_of_turn>` / `<end_of_turn>`。 */
    data object GemmaChat : PromptFormat

    /** Llama 3 / Mistral / Qwen 等: `<|im_start|>` / `<|im_end|>` ChatML。 */
    data object ChatMl : PromptFormat

    /** Llama 3 系ネイティブ: `<|start_header_id|>...<|end_header_id|>`。 */
    data object Llama3 : PromptFormat

    /** GPT-2 など: プレーンな completion プロンプト。 */
    data object PlainCompletion : PromptFormat

    /**
     * ユーザーが選択したカスタム Jinja テンプレート。
     * ネイティブ llama.cpp (minja) でのレンダリングを優先し、失敗時は
     * [fallback] 形式で手組みレンダリングする。
     */
    data class CustomJinja(val templateId: String, val fallback: PromptFormat = ChatMl) : PromptFormat
}
