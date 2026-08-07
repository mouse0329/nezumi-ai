package com.nezumi_ai.data.inference

/**
 * Gemma / トークナイザ由来の制御トークンを除去して可視本文を返すヘルパー。
 *
 * @param preserveToolCallTags true のとき `<tool_call>` / `<tool_response>` タグを保持する。
 *   インライン tool-call カードの描画に使う UI 表示経路だけ true を渡す。
 *   コピー・読み上げ・プロンプト再構築など本当にタグを見せたくない経路は既定 false のままでよい。
 */
fun String.stripGemmaTokens(preserveToolCallTags: Boolean = false): String {
    return Gemma4ThinkingParser.sanitizeVisibleText(this, preserveToolCallTags)
}
