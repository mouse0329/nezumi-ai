package com.nezumi_ai.presentation.ui.composable

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.sp
import ru.noties.jlatexmath.JLatexMathDrawable

import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.commonmark.Markdown

private sealed class MixedPart {
    data class MarkdownPart(val content: String) : MixedPart()
    data class LatexBlockPart(val formula: String) : MixedPart()
    data class LatexInlinePart(val formula: String) : MixedPart()
}

private fun parseMixed(text: String): List<MixedPart> {
    val parts = mutableListOf<MixedPart>()
    val codeBlockRegex = Regex("""```[\s\S]*?```""")
    val blockLatexRegex = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    val inlineLatexRegex = Regex("""\$(.+?)\$""")
    var remaining = text

    while (remaining.isNotEmpty()) {
        // コードブロックを検出
        val codeBlockMatch = codeBlockRegex.find(remaining)
        // コードブロック外のみで数式を検出
        val blockLatexMatch = blockLatexRegex.find(remaining)?.takeIf { match ->
            codeBlockMatch == null || match.range.first < codeBlockMatch.range.first
        }
        val inlineLatexMatch = inlineLatexRegex.find(remaining)?.takeIf { match ->
            codeBlockMatch == null || match.range.first < codeBlockMatch.range.first
        }

        // 最初に出現する要素を決定
        val firstMatch = listOfNotNull(codeBlockMatch, blockLatexMatch, inlineLatexMatch)
            .minByOrNull { it.range.first }

        if (firstMatch == null) {
            parts.add(MixedPart.MarkdownPart(remaining))
            break
        }

        // 最初の要素の前のテキストを処理
        if (firstMatch.range.first > 0) {
            val before = remaining.substring(0, firstMatch.range.first)
            val lastNewline = before.lastIndexOf('\n')
            
            if (firstMatch == blockLatexMatch || firstMatch == inlineLatexMatch) {
                // 数式の前は改行で分割
                if (lastNewline >= 0) {
                    parts.add(MixedPart.MarkdownPart(before.substring(0, lastNewline + 1)))
                    remaining = before.substring(lastNewline + 1) + remaining.substring(firstMatch.range.first)
                    continue
                }
            } else {
                // コードブロックの前も改行で分割
                if (lastNewline >= 0) {
                    parts.add(MixedPart.MarkdownPart(before.substring(0, lastNewline + 1)))
                    remaining = before.substring(lastNewline + 1) + remaining.substring(firstMatch.range.first)
                    continue
                }
            }
        }

        when {
            firstMatch == codeBlockMatch -> {
                // コードブロックはMarkdownの一部として扱う
                val lineStart = remaining.lastIndexOf('\n', firstMatch.range.first).let { 
                    if (it < 0) 0 else it + 1 
                }
                val lineEnd = remaining.indexOf('\n', firstMatch.range.last).let {
                    if (it < 0) remaining.length else it
                }
                val fullLine = remaining.substring(lineStart, lineEnd)
                parts.add(MixedPart.MarkdownPart(fullLine))
                remaining = remaining.substring(lineEnd)
            }
            firstMatch == blockLatexMatch -> {
                parts.add(MixedPart.LatexBlockPart(firstMatch.groupValues[1]))
                remaining = remaining.substring(firstMatch.range.last + 1)
            }
            firstMatch == inlineLatexMatch -> {
                // 同じ行全体をLatexTextで処理
                val lineStart = remaining.lastIndexOf('\n', firstMatch.range.first).let { 
                    if (it < 0) 0 else it + 1 
                }
                val lineEnd = remaining.indexOf('\n', firstMatch.range.last).let {
                    if (it < 0) remaining.length else it
                }
                val fullLine = remaining.substring(lineStart, lineEnd)
                parts.add(MixedPart.LatexInlinePart(fullLine))
                remaining = remaining.substring(lineEnd)
            }
        }
    }

    return parts
}

@Composable
fun MarkdownLatexText(
    text: String,
    modifier: Modifier = Modifier,
    textSize: Float = 40f
) {
    val density = LocalDensity.current
    val contentColor = LocalContentColor.current
    val isDark = isSystemInDarkTheme()
    
    // ダークモード時は白文字、ライトモード時は自動（contentColor）
    val textColorInt = if (isDark) {
        0xFFFFFFFF.toInt() // 白
    } else {
        android.graphics.Color.argb(
            (contentColor.alpha * 255).toInt(),
            (contentColor.red * 255).toInt(),
            (contentColor.green * 255).toInt(),
            (contentColor.blue * 255).toInt()
        )
    }
    // ダークモード時は透明背景、ライトモード時は白背景
    val bgColorInt = if (isDark) 0x00000000 else 0xFFFFFFFF.toInt()

    val parts = remember(text) { parseMixed(text) }

    Column(modifier = modifier) {
        for (part in parts) {
            when (part) {
                is MixedPart.MarkdownPart -> {
                    // Markdownコンテンツが空でないか確認
                    if (part.content.isNotBlank()) {
                        RichText {
                            Markdown(content = part.content)
                        }
                    }
                }
                is MixedPart.LatexBlockPart -> {
                    renderLatex(part.formula, textSize * 1.2f, textColorInt, bgColorInt)?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = part.formula,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is MixedPart.LatexInlinePart -> {
                    LatexText(text = part.formula, textSize = textSize)
                }
            }
        }
    }
}

private sealed class LatexPart {
    data class PlainText(val content: String) : LatexPart()
    data class Inline(val formula: String, val id: String) : LatexPart()
    data class Block(val formula: String) : LatexPart()
}

private sealed class RenderItem {
    data class TextRow(val parts: List<LatexPart>) : RenderItem()
    data class BlockRow(val formula: String) : RenderItem()
}

@Composable
fun LatexText(
    text: String,
    modifier: Modifier = Modifier,
    textSize: Float = 40f
) {
    val density = LocalDensity.current
    val contentColor = LocalContentColor.current
    val isDark = isSystemInDarkTheme()
    
    // ダークモード時は白文字、ライトモード時は自動（contentColor）
    val textColorInt = if (isDark) {
        0xFFFFFFFF.toInt() // 白
    } else {
        android.graphics.Color.argb(
            (contentColor.alpha * 255).toInt(),
            (contentColor.red * 255).toInt(),
            (contentColor.green * 255).toInt(),
            (contentColor.blue * 255).toInt()
        )
    }
    // ダークモード時は透明背景、ライトモード時は白背景
    val bgColorInt = if (isDark) 0x00000000 else 0xFFFFFFFF.toInt()

    val parts = remember(text) { parseLatex(text) }

    val renderItems = remember(parts) {
        val items = mutableListOf<RenderItem>()
        val buffer = mutableListOf<LatexPart>()
        for (part in parts) {
            when (part) {
                is LatexPart.PlainText, is LatexPart.Inline -> buffer.add(part)
                is LatexPart.Block -> {
                    if (buffer.isNotEmpty()) {
                        items.add(RenderItem.TextRow(buffer.toList()))
                        buffer.clear()
                    }
                    items.add(RenderItem.BlockRow(part.formula))
                }
            }
        }
        if (buffer.isNotEmpty()) {
            items.add(RenderItem.TextRow(buffer.toList()))
        }
        items
    }

    Column(modifier = modifier) {
        for (item in renderItems) {
            when (item) {
                is RenderItem.TextRow -> {
                    val inlineContent = item.parts
                        .filterIsInstance<LatexPart.Inline>()
                        .associate { part ->
                            val drawable = try {
                                JLatexMathDrawable.builder(part.formula)
                                    .textSize(textSize)
                                    .build()
                            } catch (e: Exception) { null }
                            
                            // 高さをテキストサイズに合わせて調整
                            val baseDp = textSize.sp
                            val wPx = drawable?.intrinsicWidth?.toFloat() ?: 160f
                            val hPx = drawable?.intrinsicHeight?.toFloat() ?: 60f
                            
                            with(density) {
                                part.id to InlineTextContent(
                                    Placeholder(
                                        wPx.toDp().value.sp,
                                        baseDp, // テキストサイズに合わせた高さ
                                        PlaceholderVerticalAlign.Center // 中央配置でテキストとの重なりを回避
                                    )
                                ) {
                                    renderLatex(part.formula, textSize, textColorInt, bgColorInt)?.let {
                                        androidx.compose.foundation.Image(
                                            bitmap = it.asImageBitmap(),
                                            contentDescription = part.formula,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }

                    val annotated = buildAnnotatedString {
                        item.parts.forEach { p ->
                            when (p) {
                                is LatexPart.PlainText -> append(p.content)
                                is LatexPart.Inline -> appendInlineContent(p.id, p.formula)
                                else -> {}
                            }
                        }
                    }
                    Text(text = annotated, inlineContent = inlineContent)
                }

                is RenderItem.BlockRow -> {
                    renderLatex(item.formula, textSize * 1.2f, textColorInt, bgColorInt)?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = item.formula,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

private fun renderLatex(
    formula: String,
    textSize: Float,
    textColor: Int = 0xFF000000.toInt(),
    bgColor: Int = 0xFFFFFFFF.toInt()
): Bitmap? {
    return try {
        val drawable = JLatexMathDrawable.builder(formula)
            .textSize(textSize)
            .background(bgColor)
            .color(textColor)
            .build()
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 400
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 100
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        bitmap
    } catch (e: Exception) {
        null
    }
}

private fun parseLatex(text: String): List<LatexPart> {
    val parts = mutableListOf<LatexPart>()
    val codeBlockRegex = Regex("""```[\s\S]*?```""")
    val blockLatexRegex = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    val inlineLatexRegex = Regex("""\$(.+?)\$""")
    var idCounter = 0
    var remaining = text

    while (remaining.isNotEmpty()) {
        // コードブロックを検出
        val codeBlockMatch = codeBlockRegex.find(remaining)
        // コードブロック外のみで数式を検出
        val blockLatexMatch = blockLatexRegex.find(remaining)?.takeIf { match ->
            codeBlockMatch == null || match.range.first < codeBlockMatch.range.first
        }
        val inlineLatexMatch = inlineLatexRegex.find(remaining)?.takeIf { match ->
            codeBlockMatch == null || match.range.first < codeBlockMatch.range.first
        }

        // 最初に出現する要素を決定
        val firstMatch = listOfNotNull(codeBlockMatch, blockLatexMatch, inlineLatexMatch)
            .minByOrNull { it.range.first }

        if (firstMatch == null) {
            parts.add(LatexPart.PlainText(remaining))
            break
        }

        // 最初の要素の前のテキストを追加
        if (firstMatch.range.first > 0) {
            parts.add(LatexPart.PlainText(remaining.substring(0, firstMatch.range.first)))
        }

        when {
            firstMatch == codeBlockMatch -> {
                // コードブロックはプレーンテキストとして追加
                parts.add(LatexPart.PlainText(remaining.substring(firstMatch.range)))
            }
            firstMatch == blockLatexMatch -> {
                parts.add(LatexPart.Block(firstMatch.groupValues[1]))
            }
            firstMatch == inlineLatexMatch -> {
                parts.add(LatexPart.Inline(firstMatch.groupValues[1], "latex_${idCounter++}"))
            }
        }

        remaining = remaining.substring(firstMatch.range.last + 1)
    }

    return parts
}
