package com.nezumi_ai.data.inference

/**
 * ツール実行結果・外部取得コンテンツに紛れ込んだツールコール系タグの無害化 (サニタイズ)。
 *
 * 背景:
 *   Web 検索 / ページ取得 (web_search / web_fetch) の結果は jsoup + flexmark で
 *   Markdown 化されるが、`<tool_call>` や `</tool_response>` といったタグ literal の
 *   無害化はどこでも行われていなかった。そのまま `<tool_response>` ブロックの
 *   content としてモデルに埋め込まれると、次ラウンドのモデルがそれを本物の
 *   ツールコールと誤認して実行してしまう (間接プロンプトインジェクション)。
 *
 * 方針 (入口防御):
 *   下流の [Gemma4ThinkingParser.removeToolTagSegments] などの正規表現走査は
 *   「テキストが JSON 構造の内側か外側か」を区別できないため、ここを直すより
 *   モデルのコンテキストに入る入口で無害化するのが筋がよい。
 *   このファイルは以下の入口で必ず通す:
 *     - [CloudToolCallParser.formatToolResults] (クラウド経路)
 *     - GgufToolCallParser.formatToolResults (GGUF 経路, app 側)
 *     - LiteRtLmEngine.buildToolResponseContentJson (LiteRT 経路, app 側)
 *     - PromptBuildingUseCase.sanitizeMessageContentForPrompt (履歴再構築・ユーザー入力経路)
 *
 * 無害化の方法:
 *   タグ文字列を「削除」すると、ツール結果本文から情報が失われてモデルが混乱する。
 *   また「`&lt;` への HTML エスケープ」は、`<tool_response>` 内のテキストが
 *   HTML として解釈されないこのパイプラインでは、モデルにそのまま `&lt;` という
 *   文字列が見えるだけで読みにくい。よって `<` / `>` を視覚的に近い全角の
 *   `＜` (U+FF1C) / `＞` (U+FF1E) に置換する。正規表現による走査・
 *   `indexOf` によるタグ検出のいずれも ASCII の `<` / `>` にのみ反応するため、
 *   この置換だけでタグとしては確実に不活性化される。
 */
object ToolPayloadSanitizer {

    /**
     * [text] 中に含まれるツールコール系タグを全角化して不活性化する。
     *
     * 対象は [ToolCallTags.STRIP_TOKEN_SEQUENCES] と同一集合 (「サニタイズ対象タグ一覧」は
     * タグ定義の集約場所である [ToolCallTags] と同居させる運用とし、ここではそれを参照する)。
     * 大文字・小文字の揺れ (`<Tool_Call>` 等) も大小文字無視で拾う。
     * `<think>` 等のシンキングタグも対象に含まれるが、モデルに返すツール結果内で
     * これらが「生きたタグ」として必要になるケースはない。
     *
     * 制御トークン (`<end_of_turn>` 等) も同様に不活性化する。
     * 置換は単一の正規表現パスで行うため、入力サイズに対して線形コスト。
     */
    fun sanitizeToolTags(text: String): String {
        if (text.isEmpty()) return text
        if ('<' !in text) return text
        return tagPattern.replace(text) { m ->
            "＜" + m.groupValues[1] + "＞"
        }
    }

    /**
     * ネストした JSON 文字列値などを個別に無害化するための薄いラッパ。
     * 意味的には [sanitizeToolTags] と同じだが、呼び出し側で「これはモデルに埋め込む
     * 文字列値である」という意図を明示したい箇所で使う。
     */
    fun sanitizeValue(text: String): String = sanitizeToolTags(text)

    // 例: "<tool_call>" → キャプチャ "tool_call"、 "</tool_response>" → "/tool_response"
    // STRIP_TOKEN_SEQUENCES から動的に構築するため、タグ定義の追加に追従する。
    private val tagPattern: Regex = run {
        val targets = ToolCallTags.STRIP_TOKEN_SEQUENCES
            .map { it.removePrefix("<").removeSuffix(">") }
            .distinct()
            .sortedByDescending { it.length } // 長い方を先に (例: "/tool_call" より "tool_call" を先に…ではなく prefix の有無で競合しないよう最長一致)
            .joinToString("|") { Regex.escape(it) }
        Regex("<($targets)>", RegexOption.IGNORE_CASE)
    }
}
