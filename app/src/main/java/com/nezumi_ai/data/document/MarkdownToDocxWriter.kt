package com.nezumi_ai.data.document

import org.apache.poi.xwpf.usermodel.ParagraphAlignment
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File
import java.io.FileOutputStream

/**
 * MarkdownParser.Block のリストを Word (.docx) に書き出す。
 * Apache POI XWPF を使用。
 */
object MarkdownToDocxWriter {

    fun write(markdown: String, outputFile: File) {
        val blocks = MarkdownParser.parse(markdown)
        val doc = XWPFDocument()
        try {
            for (block in blocks) {
                when (block) {
                    is MarkdownParser.Block.Heading -> {
                        val p = doc.createParagraph()
                        p.alignment = ParagraphAlignment.LEFT
                        // 見出しレベルに応じてフォントサイズを変える（Wordの組み込みHeadingスタイル名は
                        // テンプレート依存でズレやすいため、フォントサイズ+太字で表現する）
                        val fontSize = when (block.level) {
                            1 -> 26
                            2 -> 22
                            3 -> 19
                            4 -> 16
                            5 -> 14
                            else -> 12
                        }
                        p.spacingBefore = 240
                        p.spacingAfter = 120
                        for (run in block.runs) {
                            val r = p.createRun()
                            r.setText(run.text)
                            r.isBold = true
                            r.fontSize = fontSize
                            if (run.italic) r.isItalic = true
                        }
                    }

                    is MarkdownParser.Block.Paragraph -> {
                        val p = doc.createParagraph()
                        p.spacingAfter = 120
                        writeRuns(p, block.runs)
                    }

                    is MarkdownParser.Block.ListItem -> {
                        val p = doc.createParagraph()
                        p.indentationLeft = 360
                        p.spacingAfter = 60
                        val bullet = if (block.ordered) "${block.index}. " else "• "
                        val bulletRun = p.createRun()
                        bulletRun.setText(bullet)
                        writeRuns(p, block.runs)
                    }

                    is MarkdownParser.Block.Table -> {
                        val table: XWPFTable = doc.createTable(
                            (block.rows.size + 1).coerceAtLeast(1),
                            block.header.size.coerceAtLeast(1)
                        )
                        // ヘッダー行
                        for ((colIdx, headerText) in block.header.withIndex()) {
                            val cell = table.getRow(0).getCell(colIdx) ?: table.getRow(0).createCell()
                            cell.removeParagraph(0)
                            val p = cell.addParagraph()
                            val r = p.createRun()
                            r.setText(headerText)
                            r.isBold = true
                        }
                        // データ行
                        for ((rowIdx, row) in block.rows.withIndex()) {
                            val tableRow = table.getRow(rowIdx + 1) ?: table.createRow()
                            for ((colIdx, cellText) in row.withIndex()) {
                                val cell = tableRow.getCell(colIdx) ?: tableRow.createCell()
                                cell.removeParagraph(0)
                                val p = cell.addParagraph()
                                val r = p.createRun()
                                r.setText(cellText)
                            }
                        }
                        // テーブル後に空行を挿入して次のブロックと視覚的に分離する
                        doc.createParagraph()
                    }

                    is MarkdownParser.Block.CodeBlock -> {
                        for (line in block.lines) {
                            val p = doc.createParagraph()
                            p.spacingAfter = 0
                            val r = p.createRun()
                            r.setText(line.ifEmpty { " " })
                            r.fontFamily = "Courier New"
                            r.fontSize = 10
                        }
                        doc.createParagraph()
                    }

                    is MarkdownParser.Block.Quote -> {
                        val p = doc.createParagraph()
                        p.indentationLeft = 480
                        p.spacingAfter = 120
                        for (run in block.runs) {
                            val r = p.createRun()
                            r.setText(run.text)
                            r.isItalic = true
                        }
                    }

                    MarkdownParser.Block.HorizontalRule -> {
                        val p = doc.createParagraph()
                        val r = p.createRun()
                        r.setText("─".repeat(40))
                    }
                }
            }

            FileOutputStream(outputFile).use { out ->
                doc.write(out)
            }
        } finally {
            doc.close()
        }
    }

    private fun writeRuns(p: org.apache.poi.xwpf.usermodel.XWPFParagraph, runs: List<MarkdownParser.InlineRun>) {
        for (run in runs) {
            val r = p.createRun()
            r.setText(run.text)
            r.isBold = run.bold
            r.isItalic = run.italic
            if (run.code) {
                r.fontFamily = "Courier New"
            }
        }
    }
}
