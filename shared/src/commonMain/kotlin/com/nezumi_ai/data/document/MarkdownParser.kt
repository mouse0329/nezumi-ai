package com.nezumi_ai.data.document

/**
 * Markdown から Word/PDF/Excel ドキュメントを作成する際に共有する、極めて軽量な Markdown パーサ。
 *
 * フル仕様の CommonMark には対応しない。実用上よく使われる範囲のみ:
 *   - 見出し (# ~ ######)
 *   - 段落
 *   - 箇条書き (-, *, +) / 番号付きリスト (1. 2. ...)
 *   - テーブル (| a | b |\n|---|---|\n| 1 | 2 |)
 *   - 水平線 (---, ***, ___)
 *   - コードブロック (``` ... ```)
 *   - 引用 (> ...)
 *   - インライン: **太字**, *斜体*, `コード`
 */
object MarkdownParser {

    sealed class Block {
        data class Heading(val level: Int, val runs: List<InlineRun>) : Block()
        data class Paragraph(val runs: List<InlineRun>) : Block()
        data class ListItem(val ordered: Boolean, val index: Int, val runs: List<InlineRun>) : Block()
        data class Table(val header: List<String>, val rows: List<List<String>>) : Block()
        data class CodeBlock(val language: String?, val lines: List<String>) : Block()
        data class Quote(val runs: List<InlineRun>) : Block()
        object HorizontalRule : Block()
    }

    data class InlineRun(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    )

    fun parse(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        val blocks = mutableListOf<Block>()

        var i = 0
        var orderedListIndex = 0

        while (i < lines.size) {
            val rawLine = lines[i]
            val line = rawLine

            when {
                line.isBlank() -> {
                    orderedListIndex = 0
                    i++
                }

                // コードブロック ```lang ... ```
                line.trimStart().startsWith("```") -> {
                    val fenceIndent = line.indexOf("```")
                    val lang = line.trim().removePrefix("```").trim().takeIf { it.isNotBlank() }
                    val codeLines = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        codeLines.add(lines[i])
                        i++
                    }
                    if (i < lines.size) i++ // skip closing fence
                    blocks.add(Block.CodeBlock(lang, codeLines))
                    orderedListIndex = 0
                }

                // 水平線
                line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> {
                    blocks.add(Block.HorizontalRule)
                    orderedListIndex = 0
                    i++
                }

                // 見出し
                line.trimStart().startsWith("#") -> {
                    val trimmed = line.trimStart()
                    val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = trimmed.drop(level).trim()
                    blocks.add(Block.Heading(level, parseInline(text)))
                    orderedListIndex = 0
                    i++
                }

                // テーブル: 次の行が区切り行 (|---|---|) であること
                line.trimStart().startsWith("|") &&
                    i + 1 < lines.size &&
                    isTableSeparator(lines[i + 1]) -> {
                    val header = splitTableRow(line)
                    i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        rows.add(splitTableRow(lines[i]))
                        i++
                    }
                    blocks.add(Block.Table(header, rows))
                    orderedListIndex = 0
                }

                // 引用
                line.trimStart().startsWith(">") -> {
                    val text = line.trimStart().removePrefix(">").trim()
                    blocks.add(Block.Quote(parseInline(text)))
                    orderedListIndex = 0
                    i++
                }

                // 番号付きリスト
                Regex("^\\s*\\d+\\.\\s+").containsMatchIn(line) -> {
                    val text = line.trimStart().replaceFirst(Regex("^\\d+\\.\\s+"), "")
                    orderedListIndex++
                    blocks.add(Block.ListItem(ordered = true, index = orderedListIndex, runs = parseInline(text)))
                    i++
                }

                // 箇条書き
                line.trimStart().let { it.startsWith("- ") || it.startsWith("* ") || it.startsWith("+ ") } -> {
                    val text = line.trimStart().drop(2).trim()
                    blocks.add(Block.ListItem(ordered = false, index = 0, runs = parseInline(text)))
                    orderedListIndex = 0
                    i++
                }

                // 通常段落（連続する非空行を1つの段落にまとめる）
                else -> {
                    val paraLines = mutableListOf<String>()
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isBlockStart(lines[i])
                    ) {
                        paraLines.add(lines[i].trim())
                        i++
                    }
                    if (paraLines.isEmpty()) {
                        // 保険: isBlockStart の判定漏れで無限ループしないようにする
                        paraLines.add(line.trim())
                        i++
                    }
                    blocks.add(Block.Paragraph(parseInline(paraLines.joinToString(" "))))
                    orderedListIndex = 0
                }
            }
        }

        return blocks
    }

    private fun isBlockStart(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("#") ||
            t.startsWith("```") ||
            t.startsWith("|") ||
            t.startsWith(">") ||
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ||
            Regex("^\\d+\\.\\s+").containsMatchIn(line) ||
            line.trim().matches(Regex("^(-{3,}|\\*{3,}|_{3,})$"))
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        if (!t.startsWith("|") && !t.contains("-")) return false
        return Regex("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$").matches(t)
    }

    private fun splitTableRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.removePrefix("|")
        if (t.endsWith("|")) t = t.removeSuffix("|")
        return t.split("|").map { it.trim() }
    }

    /**
     * **bold**, *italic*, `code` のみサポートするシンプルなインラインパーサ。
     * ネストは非対応（bold内のitalicなど）。
     */
    fun parseInline(text: String): List<InlineRun> {
        if (text.isEmpty()) return emptyList()

        val runs = mutableListOf<InlineRun>()
        val pattern = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`|\\*[^*]+\\*)")
        var lastEnd = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastEnd) {
                runs.add(InlineRun(text.substring(lastEnd, match.range.first)))
            }
            val token = match.value
            when {
                token.startsWith("**") -> runs.add(InlineRun(token.removePrefix("**").removeSuffix("**"), bold = true))
                token.startsWith("`") -> runs.add(InlineRun(token.removePrefix("`").removeSuffix("`"), code = true))
                token.startsWith("*") -> runs.add(InlineRun(token.removePrefix("*").removeSuffix("*"), italic = true))
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < text.length) {
            runs.add(InlineRun(text.substring(lastEnd)))
        }
        return if (runs.isEmpty()) listOf(InlineRun(text)) else runs
    }

    fun plainText(runs: List<InlineRun>): String = runs.joinToString("") { it.text }
}
