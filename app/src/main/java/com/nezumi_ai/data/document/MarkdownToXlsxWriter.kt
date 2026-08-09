package com.nezumi_ai.data.document

import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream

/**
 * MarkdownParser.Block のリストを Excel (.xlsx) に書き出す。
 *
 * 方針:
 *   - Markdown 中の各テーブル(|a|b|)は、それぞれ独立したシートに変換する
 *     （複数テーブルがある文書を1シートに詰め込むとレイアウトが崩れるため）。
 *   - テーブル以外の見出し・段落・リスト等は「本文」シートに1行1要素で書き出す。
 *   - テーブルが1つも無い場合は「本文」シートのみが生成される。
 */
object MarkdownToXlsxWriter {

    private const val TEXT_SHEET_NAME = "本文"

    fun write(markdown: String, outputFile: File) {
        val blocks = MarkdownParser.parse(markdown)
        val workbook = XSSFWorkbook()
        try {
            val headerStyle = createHeaderStyle(workbook)
            val boldStyle = createBoldStyle(workbook)

            val textSheet = workbook.createSheet(TEXT_SHEET_NAME)
            var textRowIdx = 0
            var tableCount = 0

            for (block in blocks) {
                when (block) {
                    is MarkdownParser.Block.Heading -> {
                        val row = textSheet.createRow(textRowIdx++)
                        val cell = row.createCell(0)
                        cell.setCellValue(MarkdownParser.plainText(block.runs))
                        cell.cellStyle = boldStyle
                    }

                    is MarkdownParser.Block.Paragraph -> {
                        val row = textSheet.createRow(textRowIdx++)
                        row.createCell(0).setCellValue(MarkdownParser.plainText(block.runs))
                    }

                    is MarkdownParser.Block.ListItem -> {
                        val row = textSheet.createRow(textRowIdx++)
                        val prefix = if (block.ordered) "${block.index}. " else "• "
                        row.createCell(0).setCellValue(prefix + MarkdownParser.plainText(block.runs))
                    }

                    is MarkdownParser.Block.Quote -> {
                        val row = textSheet.createRow(textRowIdx++)
                        row.createCell(0).setCellValue("> " + MarkdownParser.plainText(block.runs))
                    }

                    is MarkdownParser.Block.CodeBlock -> {
                        for (line in block.lines) {
                            val row = textSheet.createRow(textRowIdx++)
                            row.createCell(0).setCellValue(line)
                        }
                    }

                    MarkdownParser.Block.HorizontalRule -> {
                        textRowIdx++ // 空行として扱う
                    }

                    is MarkdownParser.Block.Table -> {
                        tableCount++
                        val sheetName = sanitizeSheetName(
                            if (tableCount == 1) "テーブル" else "テーブル$tableCount"
                        )
                        val sheet = workbook.createSheet(sheetName)

                        val headerRow = sheet.createRow(0)
                        for ((colIdx, text) in block.header.withIndex()) {
                            val cell = headerRow.createCell(colIdx)
                            cell.setCellValue(text)
                            cell.cellStyle = headerStyle
                        }

                        for ((rowIdx, dataRow) in block.rows.withIndex()) {
                            val row = sheet.createRow(rowIdx + 1)
                            for ((colIdx, text) in dataRow.withIndex()) {
                                val cell = row.createCell(colIdx)
                                writeCellValue(cell, text)
                            }
                        }

                        // 列幅の自動調整
                        val colCount = block.header.size.coerceAtLeast(1)
                        for (c in 0 until colCount) {
                            runCatching { sheet.autoSizeColumn(c) }
                        }
                    }
                }
            }

            if (textRowIdx > 0) {
                runCatching { textSheet.autoSizeColumn(0) }
            } else {
                // 本文が何も無ければシート自体を削除（テーブルのみのMarkdownだった場合）
                if (tableCount > 0) {
                    workbook.removeSheetAt(workbook.getSheetIndex(TEXT_SHEET_NAME))
                }
            }

            FileOutputStream(outputFile).use { out ->
                workbook.write(out)
            }
        } finally {
            workbook.close()
        }
    }

    // 先頭ゼロを含まない素直な整数/小数表現のみ数値とみなす（郵便番号 "0123" 等を保護）。
    private val PLAIN_NUMBER_REGEX = Regex("^-?(0|[1-9]\\d*)(\\.\\d+)?$")

    /**
     * セルの中身が素直な数値表現であれば数値として、それ以外
     * （"007", "1.2.3", 空文字, 通常テキスト等）は文字列として書き込む。
     */
    private fun writeCellValue(cell: org.apache.poi.ss.usermodel.Cell, text: String) {
        val trimmed = text.trim()
        if (PLAIN_NUMBER_REGEX.matches(trimmed)) {
            cell.setCellValue(trimmed.toDouble())
        } else {
            cell.setCellValue(text)
        }
    }

    private fun createHeaderStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        val font = workbook.createFont() as XSSFFont
        font.bold = true
        style.setFont(font)
        style.fillForegroundColor = IndexedColors.GREY_25_PERCENT.getIndex()
        style.fillPattern = FillPatternType.SOLID_FOREGROUND
        return style
    }

    private fun createBoldStyle(workbook: XSSFWorkbook): XSSFCellStyle {
        val style = workbook.createCellStyle() as XSSFCellStyle
        val font = workbook.createFont() as XSSFFont
        font.bold = true
        font.fontHeightInPoints = 12
        style.setFont(font)
        return style
    }

    /**
     * Excel シート名の制約 (31文字以内、一部記号禁止) に合わせて調整する。
     */
    private fun sanitizeSheetName(name: String): String {
        val invalidChars = charArrayOf('\\', '/', '?', '*', '[', ']', ':')
        var sanitized = name
        for (c in invalidChars) sanitized = sanitized.replace(c, '_')
        return sanitized.take(31)
    }
}