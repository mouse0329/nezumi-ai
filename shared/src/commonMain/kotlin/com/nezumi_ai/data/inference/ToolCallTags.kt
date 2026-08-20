package com.nezumi_ai.data.inference

/**
 * ツールコール／シンキング関連のタグ literal を 1 箇所に集約する。
 *
 * Bug fix: 以前は `<tool_call>` / `<|tool_call>` / `<tool_call|>` / `<tool_response>` などの
 * literal が [Gemma4ThinkingParser] / [GgufToolCallParser] (app) / [CloudToolCallParser] /
 * [GgufToolPromptBuilder] (app) の 4 ファイルに散在していた。片方だけ修正して片方の
 * サニタイズや regex を直し忘れる、というバグの温床になっていたので統一する。
 *
 * 名前空間:
 *   - 汎用 (Qwen / Hermes-Pro / ChatML 系) の tool_call → [TOOL_CALL_OPEN] / [TOOL_CALL_CLOSE]
 *   - Gemma 4 (Google 公式) の非対称タグ           → [GEMMA4_TOOL_CALL_OPEN] / [GEMMA4_TOOL_CALL_CLOSE]
 *   - tool_response (両フォーマット共通)             → [TOOL_RESPONSE_OPEN] / [TOOL_RESPONSE_CLOSE]
 *   - Thinking                                       → [THINK_OPEN] / [THINK_CLOSE]
 *   - Gemma 4 Channel Thinking                       → [CHANNEL_OPEN] / [CHANNEL_CLOSE] / [THOUGHT_LABEL]
 */
object ToolCallTags {
    /** 汎用 `<tool_call>` (Qwen 3 / Hermes-Pro / DeepSeek-R1-tool 等の Hugging Face 公式仕様)。 */
    const val TOOL_CALL_OPEN = "<tool_call>"
    const val TOOL_CALL_CLOSE = "</tool_call>"

    /** Gemma 4 (Google 公式) の非対称タグ。開き/閉じで `|` の位置が入れ替わる。 */
    const val GEMMA4_TOOL_CALL_OPEN = "<|tool_call>"
    const val GEMMA4_TOOL_CALL_CLOSE = "<tool_call|>"

    /** ツール実行結果を戻すブロック。汎用・Gemma 4 とも共通。 */
    const val TOOL_RESPONSE_OPEN = "<tool_response>"
    const val TOOL_RESPONSE_CLOSE = "</tool_response>"

    /** 旧・独自ツール結果ブロック (レガシー・後方互換のためサニタイズ対象に残す)。 */
    const val TOOL_RESULT_OPEN = "<tool_result>"
    const val TOOL_RESULT_CLOSE = "</tool_result>"

    /** ツール定義を包むブロック。 */
    const val TOOLS_OPEN = "<tools>"
    const val TOOLS_CLOSE = "</tools>"

    /** 汎用シンキングタグ (Qwen 3 / DeepSeek-R1 / QwQ / GGUF llama.cpp すべて共通)。 */
    const val THINK_OPEN = "<think>"
    const val THINK_CLOSE = "</think>"

    /** Qwen3 公式 non-thinking jinja と同じ「空 `<think></think>`」プレフィル。 */
    const val QWEN_EMPTY_THINK_PREFILL = "<think>\n\n</think>\n\n"

    /** assistant ターン直後にプレフィルする thinking 発火トリガ。 */
    const val ASSISTANT_THINK_PREFILL = "<think>\n"

    /** Gemma 4 公式の thinking トリガ (システムターン内に置く)。 */
    const val GEMMA_THINK_PREFIX = "<|think|>\n"
    const val GEMMA_THINK_TRIGGER = "<|think|>"

    /** Gemma 4 公式の thought channel タグ (非対称)。 */
    const val CHANNEL_OPEN = "<|channel>"
    const val CHANNEL_CLOSE = "<channel|>"
    const val THOUGHT_LABEL = "thought\n"

    /** Qwen 3.0-3.4 のソフトスイッチ commands。 */
    const val QWEN_THINK_COMMAND = "/think"
    const val QWEN_NO_THINK_COMMAND = "/no_think"

    /**
     * `<tool_call>` / `</tool_call>` および Gemma 4 の非対称ペアを含む、
     * 「インライン tool-call カード描画のために保持したい」タグ集合。
     *
     * [Gemma4ThinkingParser.sanitizeVisibleText] が `preserveToolCallTags=true` で
     * 走るときは、この集合のタグを STRIP 対象から外す。
     */
    val TOOL_CALL_TAG_TOKENS: Set<String> = setOf(
        TOOL_CALL_OPEN,
        TOOL_CALL_CLOSE,
        GEMMA4_TOOL_CALL_OPEN,
        GEMMA4_TOOL_CALL_CLOSE,
        TOOL_RESPONSE_OPEN,
        TOOL_RESPONSE_CLOSE
    )

    /**
     * サニタイズで無条件に除去する制御トークンの列。
     * ツールコール／レスポンスタグは [TOOL_CALL_TAG_TOKENS] のフィルタで
     * `preserveToolCallTags` に応じて動的に外す。
     */
    val STRIP_TOKEN_SEQUENCES: List<String> = listOf(
        "<end_of_turn>",
        "<turn|>",
        "<eos>",
        "<|eos|>",
        "<|eot_id|>",
        GEMMA_THINK_TRIGGER,     // Gemma 4 の thinking トリガが可視出力に漏れた場合の防護
        THINK_OPEN,
        THINK_CLOSE,
        TOOL_CALL_OPEN,
        TOOL_CALL_CLOSE,
        GEMMA4_TOOL_CALL_OPEN,
        GEMMA4_TOOL_CALL_CLOSE,
        TOOL_RESPONSE_OPEN,
        TOOL_RESPONSE_CLOSE,
        TOOL_RESULT_OPEN,
        TOOL_RESULT_CLOSE,
        TOOLS_OPEN,
        TOOLS_CLOSE
    )

    /**
     * [splitThinkingBySpecialToken] 用の「思考本文を強制終端させるトークン列」。
     * `{` / `}` / バッククォートなど thinking と JSON tool-call の境界に現れる汎用文字と、
     * 各 tool_* / tools タグを合成したもの。
     */
    val THINKING_TERMINATOR_TOKENS: List<String> = listOf(
        "```",
        "`",
        "{",
        "}",
        TOOL_CALL_OPEN,
        TOOL_CALL_CLOSE,
        GEMMA4_TOOL_CALL_OPEN,
        GEMMA4_TOOL_CALL_CLOSE,
        TOOL_RESULT_OPEN,
        TOOL_RESULT_CLOSE,
        TOOLS_OPEN,
        TOOLS_CLOSE,
        TOOL_RESPONSE_OPEN,
        TOOL_RESPONSE_CLOSE
    )
}
