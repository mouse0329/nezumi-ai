package com.nezumi_ai.data.document

import android.content.Context
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File

private const val TAG = "MarkdownToPdfWriter"

/**
 * MarkdownParser.Block のリストを PDF に書き出す。
 * PDFBox-Android (com.tom-roush:pdfbox-android) を使用。
 *
 * 日本語対応について:
 *   PDFBox の標準14フォントは Latin のみのため、CJK を含む Markdown を
 *   正しく描画するには TrueType/OpenType フォントの埋め込みが必須。
 *   本実装はアプリ同梱の Noto Sans JP を assets/fonts/static/ から読み込み
 *   embed する。読み込みに失敗した場合のみ端末のシステム日本語フォントを
 *   試し、それでも無ければ Latin 標準フォントにフォールバックする
 *   （日本語は "?" に置換され WriteResult.usedFallbackFont=true を返す）。
 */
object MarkdownToPdfWriter {

    data class WriteResult(
        val usedFallbackFont: Boolean,
        val fontSource: String
    )

    private const val PAGE_MARGIN = 50f
    private val PAGE_WIDTH = PDRectangle.A4.width
    private val PAGE_HEIGHT = PDRectangle.A4.height
    private val CONTENT_WIDTH = PAGE_WIDTH - 2 * PAGE_MARGIN

    // assets 同梱フォント（UI / PDF 共通）。static 配下の個別 weight を優先する。
    private const val ASSET_FONT_REGULAR = "fonts/static/NotoSansJP-Regular.ttf"
    private const val ASSET_FONT_BOLD = "fonts/static/NotoSansJP-Bold.ttf"

    // assets が読めない場合の端末システム日本語フォント候補（優先順）
    private val CANDIDATE_SYSTEM_FONT_PATHS = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/NotoSansCJK-Regular.otf",
        "/system/fonts/NotoSansJP-Regular.otf",
        "/system/fonts/DroidSansJapanese.ttf",
        "/system/fonts/DroidSansFallback.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttf"
    )

    /**
     * ページ送り・カーソル位置・現在の PDPageContentStream をまとめて保持する
     * 可変状態。トップレベルの描画ループと drawTable() の両方から同一インスタンスを
     * 参照させることで、「クロージャが別スコープの変数を更新してしまい呼び出し元に
     * 反映されない」という不具合を防ぐ。
     */
    private class RenderState(
        val document: PDDocument,
        var page: PDPage,
        var stream: PDPageContentStream,
        var cursorY: Float
    ) {
        fun newPage() {
            stream.close()
            page = PDPage(PDRectangle.A4)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            cursorY = PAGE_HEIGHT - PAGE_MARGIN
        }

        fun ensureSpace(neededHeight: Float) {
            if (cursorY - neededHeight < PAGE_MARGIN) {
                newPage()
            }
        }
    }

    fun write(context: Context, markdown: String, outputFile: File): WriteResult {
        PDFBoxResourceLoader.init(context.applicationContext)

        val blocks = MarkdownParser.parse(markdown)
        val document = PDDocument()

        try {
            val fonts = resolveFonts(document, context)

            val firstPage = PDPage(PDRectangle.A4)
            document.addPage(firstPage)
            val state = RenderState(
                document = document,
                page = firstPage,
                stream = PDPageContentStream(document, firstPage),
                cursorY = PAGE_HEIGHT - PAGE_MARGIN
            )

            for (block in blocks) {
                renderBlock(block, state, fonts)
            }

            state.stream.close()
            document.save(outputFile)

            return WriteResult(usedFallbackFont = fonts.isFallback, fontSource = fonts.source)
        } finally {
            document.close()
        }
    }

    private fun renderBlock(block: MarkdownParser.Block, state: RenderState, fonts: Fonts) {
        when (block) {
            is MarkdownParser.Block.Heading -> {
                val fontSize = when (block.level) {
                    1 -> 20f
                    2 -> 17f
                    3 -> 15f
                    4 -> 13f
                    else -> 12f
                }
                state.ensureSpace(fontSize + 16f)
                state.cursorY -= 8f
                drawWrappedText(
                    state, MarkdownParser.plainText(block.runs), fonts.bold, fontSize, fontSize + 6f
                )
                state.cursorY -= 4f
            }

            is MarkdownParser.Block.Paragraph -> {
                drawWrappedText(state, MarkdownParser.plainText(block.runs), fonts.regular, 11f, 16f)
                state.cursorY -= 6f
            }

            is MarkdownParser.Block.ListItem -> {
                val prefix = if (block.ordered) "${block.index}. " else "• "
                drawWrappedText(
                    state,
                    prefix + MarkdownParser.plainText(block.runs),
                    fonts.regular,
                    11f,
                    16f,
                    indent = 14f
                )
            }

            is MarkdownParser.Block.Quote -> {
                drawWrappedText(
                    state,
                    "\u201C " + MarkdownParser.plainText(block.runs),
                    fonts.regular,
                    11f,
                    16f,
                    indent = 18f
                )
                state.cursorY -= 4f
            }

            is MarkdownParser.Block.CodeBlock -> {
                state.ensureSpace(4f)
                state.cursorY -= 4f
                for (line in block.lines) {
                    drawWrappedText(state, line, fonts.regular, 9.5f, 13f, indent = 10f)
                }
                state.cursorY -= 6f
            }

            MarkdownParser.Block.HorizontalRule -> {
                state.ensureSpace(20f)
                state.cursorY -= 10f
                state.stream.moveTo(PAGE_MARGIN, state.cursorY)
                state.stream.lineTo(PAGE_WIDTH - PAGE_MARGIN, state.cursorY)
                state.stream.stroke()
                state.cursorY -= 10f
            }

            is MarkdownParser.Block.Table -> {
                drawTable(state, block, fonts)
                state.cursorY -= 10f
            }
        }
    }

    private fun drawWrappedText(
        state: RenderState,
        text: String,
        font: PDFont,
        fontSize: Float,
        leading: Float,
        indent: Float = 0f
    ) {
        if (text.isBlank()) return
        val lines = wrapText(text, font, fontSize, CONTENT_WIDTH - indent)
        for (line in lines) {
            state.ensureSpace(leading)
            state.stream.beginText()
            state.stream.setFont(font, fontSize)
            state.stream.newLineAtOffset(PAGE_MARGIN + indent, state.cursorY)
            state.stream.showText(sanitizeForFont(line, font))
            state.stream.endText()
            state.cursorY -= leading
        }
    }

    private data class Fonts(
        val regular: PDFont,
        val bold: PDFont,
        val source: String,
        val isFallback: Boolean
    )

    /**
     * アプリ assets の Noto Sans JP を優先して embed する。
     * 失敗時は端末システム日本語フォント、最後に Helvetica へフォールバックする。
     */
    private fun resolveFonts(document: PDDocument, context: Context): Fonts {
        // 1) assets/fonts/static/NotoSansJP-*.ttf （UI と同一ソース）
        try {
            val regular = context.assets.open(ASSET_FONT_REGULAR).use { stream ->
                PDType0Font.load(document, stream)
            }
            val bold = try {
                context.assets.open(ASSET_FONT_BOLD).use { stream ->
                    PDType0Font.load(document, stream)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bold asset font missing, reusing regular", e)
                regular
            }
            Log.i(TAG, "Loaded asset fonts for PDF: $ASSET_FONT_REGULAR / $ASSET_FONT_BOLD")
            return Fonts(
                regular = regular,
                bold = bold,
                source = "assets/$ASSET_FONT_REGULAR",
                isFallback = false
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load asset Noto Sans JP fonts", e)
        }

        // 2) 端末システムフォント
        for (path in CANDIDATE_SYSTEM_FONT_PATHS) {
            val file = File(path)
            if (!file.exists() || !file.canRead()) continue
            try {
                val font = PDType0Font.load(document, file)
                Log.i(TAG, "Loaded system font for PDF: $path")
                return Fonts(regular = font, bold = font, source = path, isFallback = false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load candidate font: $path", e)
            }
        }

        Log.w(TAG, "No Japanese font found. Falling back to standard Helvetica (CJK will render as '?').")
        return Fonts(
            regular = PDType1Font.HELVETICA,
            bold = PDType1Font.HELVETICA_BOLD,
            source = "standard-helvetica",
            isFallback = true
        )
    }

    /**
     * フォールバックフォント (Helvetica, Latin専用) を使う場合、フォントに
     * グリフが存在しない文字（例: 日本語）は PDFBox が例外を投げるため、
     * ASCII 印字可能文字以外を "?" に置換して安全側に倒す。
     */
    private fun sanitizeForFont(text: String, font: PDFont): String {
        if (font !is PDType1Font) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(if (ch.code in 0x20..0x7E) ch else '?')
        }
        return sb.toString()
    }

    private fun widthOf(font: PDFont, s: String, fontSize: Float): Float = try {
        font.getStringWidth(s) / 1000f * fontSize
    } catch (e: Exception) {
        // フォントにグリフが無い文字などで例外時は概算値で代用
        s.length * fontSize * 0.6f
    }

    private fun wrapText(text: String, font: PDFont, fontSize: Float, maxWidth: Float): List<String> {
        // 日本語には単語区切り（スペース）が存在しないことが多いため、
        // 文字単位で幅を測って折り返す（英語混在時は単語区切りより粗いが安全）。
        val lines = mutableListOf<String>()
        var current = StringBuilder()

        for (ch in text) {
            val candidate = current.toString() + ch
            if (widthOf(font, candidate, fontSize) > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                current = StringBuilder().append(ch)
            } else {
                current.append(ch)
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        if (lines.isEmpty()) lines.add("")
        return lines
    }

    /**
     * テーブルを描画する。ページをまたぐ場合は state.newPage() でページ送りする。
     */
    private fun drawTable(state: RenderState, table: MarkdownParser.Block.Table, fonts: Fonts) {
        val colCount = table.header.size.coerceAtLeast(1)
        val colWidth = CONTENT_WIDTH / colCount
        val rowHeight = 20f
        val fontSize = 9.5f

        fun drawRow(cells: List<String>, bold: Boolean) {
            state.ensureSpace(rowHeight)
            val font = if (bold) fonts.bold else fonts.regular
            val topY = state.cursorY
            val bottomY = state.cursorY - rowHeight

            var x = PAGE_MARGIN
            for (c in 0 until colCount) {
                val text = cells.getOrElse(c) { "" }
                state.stream.beginText()
                state.stream.setFont(font, fontSize)
                state.stream.newLineAtOffset(x + 3f, bottomY + 6f)
                val truncated = truncate(text, colWidth, font, fontSize)
                state.stream.showText(sanitizeForFont(truncated, font))
                state.stream.endText()
                x += colWidth
            }

            // セル罫線（縦線）
            x = PAGE_MARGIN
            for (c in 0..colCount) {
                state.stream.moveTo(x, topY)
                state.stream.lineTo(x, bottomY)
                state.stream.stroke()
                x += colWidth
            }
            // 上下の横線
            state.stream.moveTo(PAGE_MARGIN, topY)
            state.stream.lineTo(PAGE_MARGIN + CONTENT_WIDTH, topY)
            state.stream.stroke()
            state.stream.moveTo(PAGE_MARGIN, bottomY)
            state.stream.lineTo(PAGE_MARGIN + CONTENT_WIDTH, bottomY)
            state.stream.stroke()

            state.cursorY = bottomY
        }

        drawRow(table.header, bold = true)
        for (row in table.rows) {
            drawRow(row, bold = false)
        }
    }

    private fun truncate(text: String, maxWidth: Float, font: PDFont, fontSize: Float): String {
        val available = maxWidth - 6f
        if (widthOf(font, text, fontSize) <= available) return text
        var result = text
        while (result.isNotEmpty() && widthOf(font, "$result…", fontSize) > available) {
            result = result.dropLast(1)
        }
        return if (result.isEmpty()) text.take(1) else "$result…"
    }
}
