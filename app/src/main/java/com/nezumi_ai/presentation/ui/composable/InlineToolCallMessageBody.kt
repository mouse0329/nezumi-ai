package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.GgufToolCallParser
import com.nezumi_ai.data.inference.ToolResultCard

/**
 * 本文テキストと `<tool_call>` インラインカードを、生成順序のまま縦に並べて描画する。
 *
 * 依頼書の要件:
 *   [本文テキスト] → [ツールカード] → [本文テキスト] → [ツールカード] → [本文テキスト]
 *
 * ツール結果 (toolResults) と `<tool_call>` の対応付けは出現順マッチ。
 * 対応する結果が無い / まだ届いていない場合は Running カードとして表示する
 * (ストリーミング中の未完タグも同じ扱い)。
 *
 * @param content <tool_call>...</tool_call> タグを保持したままの本文
 * @param toolResults 出現順に並んだツール実行結果 (無ければ空)
 * @param isStreaming ストリーミング中フラグ (末尾未完タグの扱いに影響)
 */
@Composable
fun InlineToolCallMessageBody(
    content: String,
    toolResults: List<ToolResultCard>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val segments = GgufToolCallParser.parseSegments(content)

    // タグが 1 つも無いレガシー本文 (旧DBレコード) はセグメント化されないので、
    // 従来通り単一の Markdown ブロックとして描画する。
    if (segments.none { it is GgufToolCallParser.Segment.ToolCallSegment }) {
        BubbleTextBlock(text = content, modifier = modifier)
        return
    }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        for (seg in segments) {
            when (seg) {
                is GgufToolCallParser.Segment.TextSegment -> {
                    val t = seg.text.trim('\n', ' ', '\t')
                    if (t.isNotEmpty()) {
                        BubbleTextBlock(text = t)
                    }
                }
                is GgufToolCallParser.Segment.ToolCallSegment -> {
                    val card = toolResults.getOrNull(seg.index)
                    val status = when {
                        !seg.isComplete -> InlineToolCallStatus.Running
                        card == null && isStreaming -> InlineToolCallStatus.Running
                        card == null -> InlineToolCallStatus.Success(null)
                        card.success -> InlineToolCallStatus.Success(card)
                        else -> InlineToolCallStatus.Error(card)
                    }
                    InlineToolCallCard(
                        toolCall = seg.toolCall,
                        rawJson = seg.rawJson,
                        status = status
                    )
                }
            }
        }
    }
}

/**
 * 吹き出し内テキスト用の Markdown ブロック。
 * MessageAdapter.GalleryMarkdownText と同等の見た目 (widthIn max=280dp, カード背景, padding 11dp)。
 * 依頼書「カード前後の本文は通常の吹き出し内テキストと同じスタイルで」に相当する。
 */
@Composable
private fun BubbleTextBlock(
    text: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .widthIn(max = 280.dp)
            .background(colorResource(id = R.color.surface_card), shape)
            .border(
                BorderStroke(1.dp, colorResource(id = R.color.border)),
                shape
            )
            .padding(11.dp)
    ) {
        SelectionContainer {
            ProvideTextStyle(
                value = TextStyle(
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = colorResource(id = R.color.text_primary),
                    letterSpacing = 0.2.sp
                )
            ) {
                MaterialTheme {
                    MarkdownLatexText(text = text, textSize = 40f)
                }
            }
        }
    }
}
