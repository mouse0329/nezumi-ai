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

/**
 * プロンプト挿入用の <txtfile> ブロックを UI 表示用テキストから取り除く。
 * テキスト添付の内容はモデルには <txtfile>{name:"...",body:"..."}</txtfile> として
 * 渡すが、チャットの吹き出しにはファイル一覧 (カード) だけを見せる設計のため、
 * 生のタグは表示しない。コピー・読み上げ経路でも同じく除去する。
 */
fun String.stripTxtFileBlocks(): String {
    if (!contains("<txtfile>")) return this
    return replace(Regex("<txtfile>.*?</txtfile>\n?", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        .trimStart('\n', '\r', ' ', '　')
}

/**
 * プロンプト挿入用の <video> ブロック (動画フレーム一覧のメタ情報) を
 * UI 表示用テキストから取り除く。
 * 動画メタはモデル向けの情報であり、吹き出し・コピー・読み上げ・セッションタイトルには
 * 出さない (stripTxtFileBlocks と同じ思想)。
 */
fun String.stripVideoBlocks(): String {
    if (!contains("<video>")) return this
    return replace(Regex("<video>.*?</video>\n?", setOf(RegexOption.DOT_MATCHES_ALL)), "")
        .trimStart('\n', '\r', ' ', '　')
}
