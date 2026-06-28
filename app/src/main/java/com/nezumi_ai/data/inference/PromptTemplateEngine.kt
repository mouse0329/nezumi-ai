package com.nezumi_ai.data.inference

/**
 * モデルごとのプロンプト形式を定義する軽量テンプレートエンジン。
 *
 * Ollama / GGUF Modelfile 互換に近い構文を採用し、コード変更なしで
 * Llama 3 / Gemma / Qwen / ChatML / 独自モデルなどに切り替えられるようにする。
 *
 * 対応構文:
 *   - {{ .System }} / {{ .Prompt }} / {{ .Response }}
 *   - {{ if .System }} ... {{ end }}（{{ else }} 任意）
 *   - {{ if .Thinking }} ... {{ end }} （Issue #30 対応: Thinking フラグ）
 *   - {{ range .History }} ... {{ end }}（履歴ループ）
 *   - {{ .Role }} / {{ .Content }} （履歴ループ内のメッセージフィールド）
 *
 * 仕様:
 *   - パイプ ({{ var | upper }}) など複雑な関数呼び出しは未対応（将来 Jinja 互換へ拡張予定）
 *   - 未知の変数は空文字に展開（テンプレ崩れを防止）
 *   - 制御ブロックは入れ子可能だが、同種ブロックの厳密対応をパーサで検証する
 */
object PromptTemplateEngine {

    /**
     * テンプレートに渡すコンテキスト。
     *
     * @param system システムプロンプト（空文字なら未指定扱い）
     * @param prompt 直近のユーザーメッセージ。{{ .Prompt }} と {{ range .History }} を併用したい場合は
     *   呼び出し側で空文字を渡しても良い。
     * @param response 直近のアシスタント応答（マルチターン用）。空文字なら未指定扱い。
     * @param thinking Thinking モードが有効かどうか。{{ if .Thinking }} ... {{ end }} で参照可能。
     * @param history 履歴メッセージ（system を除外したターン一覧）。
     */
    data class PromptContext(
        val system: String = "",
        val prompt: String = "",
        val response: String = "",
        val thinking: Boolean = false,
        val history: List<HistoryMessage> = emptyList()
    )

    data class HistoryMessage(
        val role: String,
        val content: String
    )

    /** テンプレ解析時の例外。バリデーションで使う。 */
    class TemplateParseException(message: String) : RuntimeException(message)

    /** テンプレ実行時の例外。 */
    class TemplateRenderException(message: String) : RuntimeException(message)

    /**
     * テンプレートをレンダリングする。
     *
     * パース失敗時は [TemplateParseException]、未知変数による失敗時は
     * 空文字置換で続行する（GGUF の chat_template が壊れていてもプロンプトが完全に消えないよう
     * フェイルセーフ設計）。
     */
    fun render(template: String, context: PromptContext): String {
        if (template.isBlank()) return ""
        val tokens = tokenize(template)
        val ast = parse(tokens)
        val sb = StringBuilder()
        renderNodes(ast, context, sb)
        return sb.toString()
    }

    /**
     * テンプレが構文として正しいか検証する。UI のバリデーションから利用。
     * 正常時 null、失敗時はエラーメッセージを返す。
     */
    fun validate(template: String): String? {
        if (template.isBlank()) return "テンプレートが空です"
        return try {
            val tokens = tokenize(template)
            parse(tokens)
            null
        } catch (e: TemplateParseException) {
            e.message
        } catch (e: Exception) {
            e.message ?: "テンプレート解析に失敗しました"
        }
    }

    // ---- 内部実装 ----------------------------------------------------------

    private sealed class Token {
        data class Text(val value: String) : Token()
        data class Variable(val name: String) : Token()
        data class IfStart(val name: String) : Token()
        object ElseToken : Token()
        object EndToken : Token()
        data class RangeStart(val name: String) : Token()
    }

    private sealed class Node {
        data class Text(val value: String) : Node()
        data class Variable(val name: String) : Node()
        data class IfBlock(
            val condition: String,
            val thenBranch: List<Node>,
            val elseBranch: List<Node>
        ) : Node()
        data class RangeBlock(val name: String, val body: List<Node>) : Node()
    }

    private fun tokenize(template: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        val text = StringBuilder()
        while (i < template.length) {
            if (i + 1 < template.length && template[i] == '{' && template[i + 1] == '{') {
                if (text.isNotEmpty()) {
                    tokens.add(Token.Text(text.toString()))
                    text.clear()
                }
                val end = template.indexOf("}}", i + 2)
                if (end < 0) {
                    throw TemplateParseException("対応する '}}' が見つかりません (位置 $i)")
                }
                val inner = template.substring(i + 2, end).trim()
                tokens.add(parseTag(inner))
                i = end + 2
            } else {
                text.append(template[i])
                i++
            }
        }
        if (text.isNotEmpty()) tokens.add(Token.Text(text.toString()))
        return tokens
    }

    private fun parseTag(inner: String): Token {
        if (inner.isEmpty()) {
            throw TemplateParseException("空のタグ '{{ }}'")
        }
        // {{- ... -}} のホワイトスペーストリミング記号を許容（影響なしで除去）
        val body = inner.trim('-').trim()
        return when {
            body == "end" -> Token.EndToken
            body == "else" -> Token.ElseToken
            body.startsWith("if ") -> Token.IfStart(stripDot(body.removePrefix("if ").trim()))
            body.startsWith("range ") -> Token.RangeStart(stripDot(body.removePrefix("range ").trim()))
            body.startsWith(".") -> Token.Variable(body.removePrefix("."))
            else -> throw TemplateParseException("未対応のタグ: '{{ $body }}'")
        }
    }

    private fun stripDot(name: String): String = name.removePrefix(".").trim()

    private fun parse(tokens: List<Token>): List<Node> {
        val iter = PeekableIterator(tokens)
        val nodes = parseUntil(iter, stopOnElse = false, stopOnEnd = false)
        if (iter.hasNext()) {
            // tokens 全部消化したかチェック
            throw TemplateParseException("予期しない余剰トークンが存在します")
        }
        return nodes
    }

    private fun parseUntil(
        iter: PeekableIterator<Token>,
        stopOnElse: Boolean,
        stopOnEnd: Boolean
    ): List<Node> {
        val out = mutableListOf<Node>()
        while (iter.hasNext()) {
            val t = iter.peek()
            when (t) {
                is Token.EndToken -> {
                    if (stopOnEnd || stopOnElse) return out
                    throw TemplateParseException("対応する '{{ if }}' / '{{ range }}' がない '{{ end }}'")
                }
                is Token.ElseToken -> {
                    if (stopOnElse) return out
                    throw TemplateParseException("対応する '{{ if }}' がない '{{ else }}'")
                }
                else -> {
                    iter.next()
                    when (t) {
                        is Token.Text -> out.add(Node.Text(t.value))
                        is Token.Variable -> out.add(Node.Variable(t.name))
                        is Token.IfStart -> out.add(parseIf(iter, t.name))
                        is Token.RangeStart -> out.add(parseRange(iter, t.name))
                        else -> { /* 上で処理済 */ }
                    }
                }
            }
        }
        if (stopOnEnd || stopOnElse) {
            throw TemplateParseException("ブロックが閉じられていません ('{{ end }}' が必要)")
        }
        return out
    }

    private fun parseIf(iter: PeekableIterator<Token>, condition: String): Node.IfBlock {
        val thenBranch = parseUntil(iter, stopOnElse = true, stopOnEnd = true)
        val next = if (iter.hasNext()) iter.next() else
            throw TemplateParseException("'{{ if $condition }}' に対応する '{{ end }}' がありません")
        val elseBranch: List<Node>
        when (next) {
            is Token.EndToken -> elseBranch = emptyList()
            is Token.ElseToken -> {
                elseBranch = parseUntil(iter, stopOnElse = false, stopOnEnd = true)
                if (!iter.hasNext()) {
                    throw TemplateParseException("'{{ else }}' に対応する '{{ end }}' がありません")
                }
                val endTok = iter.next()
                if (endTok !is Token.EndToken) {
                    throw TemplateParseException("'{{ end }}' が期待されました")
                }
            }
            else -> throw TemplateParseException("if ブロック解析中の予期せぬトークン")
        }
        return Node.IfBlock(condition, thenBranch, elseBranch)
    }

    private fun parseRange(iter: PeekableIterator<Token>, name: String): Node.RangeBlock {
        val body = parseUntil(iter, stopOnElse = false, stopOnEnd = true)
        if (!iter.hasNext()) {
            throw TemplateParseException("'{{ range .$name }}' に対応する '{{ end }}' がありません")
        }
        val endTok = iter.next()
        if (endTok !is Token.EndToken) {
            throw TemplateParseException("'{{ end }}' が期待されました")
        }
        return Node.RangeBlock(name, body)
    }

    private fun renderNodes(nodes: List<Node>, ctx: PromptContext, sb: StringBuilder) {
        for (n in nodes) {
            when (n) {
                is Node.Text -> sb.append(n.value)
                is Node.Variable -> sb.append(lookupTopLevel(n.name, ctx))
                is Node.IfBlock -> {
                    val truthy = evalCondition(n.condition, ctx)
                    val branch = if (truthy) n.thenBranch else n.elseBranch
                    renderNodes(branch, ctx, sb)
                }
                is Node.RangeBlock -> {
                    if (!n.name.equals("History", ignoreCase = true)) {
                        // 未対応の range は無視（フェイルセーフ）
                        continue
                    }
                    for (msg in ctx.history) {
                        renderHistoryNodes(n.body, msg, ctx, sb)
                    }
                }
            }
        }
    }

    private fun renderHistoryNodes(
        nodes: List<Node>,
        msg: HistoryMessage,
        ctx: PromptContext,
        sb: StringBuilder
    ) {
        for (n in nodes) {
            when (n) {
                is Node.Text -> sb.append(n.value)
                is Node.Variable -> sb.append(lookupHistory(n.name, msg, ctx))
                is Node.IfBlock -> {
                    val truthy = evalConditionInHistory(n.condition, msg, ctx)
                    val branch = if (truthy) n.thenBranch else n.elseBranch
                    renderHistoryNodes(branch, msg, ctx, sb)
                }
                is Node.RangeBlock -> {
                    // ネストした range は未対応
                }
            }
        }
    }

    private fun lookupTopLevel(name: String, ctx: PromptContext): String {
        return when (name.lowercase()) {
            "system" -> ctx.system
            "prompt" -> ctx.prompt
            "response" -> ctx.response
            "thinking" -> if (ctx.thinking) "true" else ""
            else -> ""
        }
    }

    private fun lookupHistory(name: String, msg: HistoryMessage, ctx: PromptContext): String {
        return when (name.lowercase()) {
            "role" -> msg.role
            "content" -> msg.content
            // 履歴ループ内でも上位フィールドにアクセスできるようにする
            "system" -> ctx.system
            "prompt" -> ctx.prompt
            "response" -> ctx.response
            "thinking" -> if (ctx.thinking) "true" else ""
            else -> ""
        }
    }

    private fun evalCondition(name: String, ctx: PromptContext): Boolean {
        return when (name.lowercase()) {
            "system" -> ctx.system.isNotBlank()
            "prompt" -> ctx.prompt.isNotBlank()
            "response" -> ctx.response.isNotBlank()
            "thinking" -> ctx.thinking
            "history" -> ctx.history.isNotEmpty()
            else -> false
        }
    }

    private fun evalConditionInHistory(name: String, msg: HistoryMessage, ctx: PromptContext): Boolean {
        return when (name.lowercase()) {
            "role" -> msg.role.isNotBlank()
            "content" -> msg.content.isNotBlank()
            else -> evalCondition(name, ctx)
        }
    }

    private class PeekableIterator<T>(list: List<T>) {
        private val backing = list
        private var index = 0
        fun hasNext(): Boolean = index < backing.size
        fun peek(): T = backing[index]
        fun next(): T = backing[index++]
    }
}
