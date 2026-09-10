package com.nezumi_ai.data.inference.prompt

/**
 * プロンプトに注入するツール (MCP / Skills / ビルトイン) の1件分の定義。
 *
 * `GgufToolPromptBuilder` などが組み立てた `<tools>` ブロックの元データを
 * エンジン非依存の形で持ち回るための最小セット。命名やタグ形式
 * (Gemma4 公式 `<|tool_call>` / 汎用 `<tool_call>`) への変換はレンダラー側で行う。
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema 相当のパラメータ定義 (文字列のままレンダラーへ渡す)。 */
    val parametersJson: String,
) {
    /**
     * このツールの定義をモデル種別非依存の1行 JSON に整形する。
     * レンダラーはこの形式をタグで包むだけにし、個別のエスケープを持たない。
     */
    fun toCompactJson(): String = buildString {
        append("{\"name\":")
        appendJsonString(name)
        append(",\"description\":")
        appendJsonString(description)
        append(",\"parameters\":")
        append(parametersJson.ifBlank { "{}" })
        append("}")
    }

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
