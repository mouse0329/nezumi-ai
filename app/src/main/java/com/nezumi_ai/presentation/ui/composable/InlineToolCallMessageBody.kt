package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
 *   これら全体が「1つの吹き出し」の中に収まっている必要がある
 *   (テキストとカードをそれぞれ別々の背景ブロックにして縦に並べるのではなく、
 *    吹き出し背景は外側の Column に1つだけ持たせ、内部でテキスト/カードを重ねる)。
 *
 * ツール結果 (toolResults) と `<tool_call>` の対応付けは出現順マッチ。
 * 対応する結果が無い / まだ届いていない場合は Running カードとして表示する
 * (ストリーミング中の未完タグも同じ扱い)。
 *
 * @param content <tool_call>...</tool_call> タグを保持したままの本文
 * @param toolResults 出現順に並んだツール実行結果 (無ければ空)
 * @param isStreaming ストリーミング中フラグ (末尾未完タグの扱いに影響)
 * @param onSaveDocument ドキュメント生成カードの「保存」ボタンのコールバック。
 *   カードごとに独立して呼ばれるため、1メッセージ内で複数ドキュメントが
 *   生成されても個別に保存先を選べる。実際の docx/pdf/xlsx 変換はこの
 *   コールバック内で行われ、完了時に onComplete が呼ばれる。
 *   null なら保存ボタン自体を出さない。
 */
@Composable
fun InlineToolCallMessageBody(
    content: String,
    toolResults: List<ToolResultCard>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    onSaveDocument: ((markdown: String, format: String, fileName: String, onComplete: (Boolean) -> Unit) -> Unit)? = null
) {
    val segments = GgufToolCallParser.parseSegments(content)
    val inlineToolResponseCards = GgufToolCallParser.parseToolResponseCards(content)

    // タグが 1 つも無いレガシー本文 (旧DBレコード) はセグメント化されないので、
    // 従来通り単一の Markdown ブロックとして描画する。
    // ただし `<tool_response>` は履歴保持用メタデータであり UI では非表示にする。
    if (segments.none { it is GgufToolCallParser.Segment.ToolCallSegment }) {
        val visibleText = segments
            .filterIsInstance<GgufToolCallParser.Segment.TextSegment>()
            .joinToString(separator = "") { it.text }
        BubbleContainer(modifier = modifier) {
            BubbleText(text = visibleText)
        }
        return
    }

    // 吹き出し背景は BubbleContainer 側で1回だけ描画し、
    // テキストセグメント・カードは同じ吹き出しの内側で縦に並べる。
    BubbleContainer(modifier = modifier) {
        for (seg in segments) {
            when (seg) {
                is GgufToolCallParser.Segment.TextSegment -> {
                    val t = seg.text.trim('\n', ' ', '\t')
                    if (t.isNotEmpty()) {
                        BubbleText(text = t)
                    }
                }
                is GgufToolCallParser.Segment.ToolCallSegment -> {
                    val card = toolResults.getOrNull(seg.index)
                        ?: inlineToolResponseCards.getOrNull(seg.index)
                    // 完了の判定基準: 「ツールが実際に応答を返したか」(card != null) を一次基準とする。
                    //   - 閉じタグを観測した (status == COMPLETE) だけでは、ツールの実行自体は
                    //     まだ終わっていない可能性がある (実行はモデルの出力が閉じタグまで届いた後、
                    //     非同期に行われるため)。閉じタグ観測はあくまで「呼び出し内容が確定した」
                    //     ことを意味するだけで、「応答が返ってきた」ことの証明にはならない。
                    //   - よってチェックマーク (Success/Error) は card が実際に届いた時点でのみ出す。
                    //     card がまだ null の間は、status に関わらず Running (実行中) として表示する。
                    //   - status が PENDING/TRUNCATED の場合はそもそも実行対象にならないため、
                    //     card が届くことはない (GgufInferenceEngine は閉じタグ観測後の
                    //     GgufToolCallParser.parse() 結果のみ実行する) — これらは引き続き
                    //     Running / Error (トークン切れ) のまま表示する。
                    // 注: GgufToolCallParser.parseSegments はストリーミング表示用であり、
                    // 生成が続いている間は TRUNCATED を返さない (常に PENDING) ように変更した。
                    // 真のトークン切れ判定は GgufInferenceEngine 側が最終テキストに対して行い、
                    // その結果は UI には別経路 (toolResultCards への失敗カード追加) で届く。
                    // このブランチは現状 parseSegments からは到達しないが、念のため残す。
                    val status = when {
                        card != null && card.success -> InlineToolCallStatus.Success(card)
                        card != null && !card.success -> InlineToolCallStatus.Error(card)
                        seg.status == GgufToolCallParser.Segment.CompletionStatus.TRUNCATED ->
                            InlineToolCallStatus.Error(
                                card = GgufToolCallParser.buildTruncatedFailureCard(seg.toolCall?.name),
                                message = "ツール呼び出しが途中で切れました（トークン上限）。実行されていません。"
                            )
                        // status が PENDING でも COMPLETE でも、応答 (card) がまだ無いなら Running。
                        // 「呼び出しが確定した」と「応答が完了した」を区別する。
                        else -> InlineToolCallStatus.Running
                    }
                    InlineToolCallCard(
                        toolCall = seg.toolCall,
                        rawJson = seg.rawJson,
                        status = status,
                        onSaveDocument = onSaveDocument
                    )
                }
            }
        }
    }
}

/**
 * 吹き出し背景。MessageAdapter.GalleryMarkdownText と同等の見た目
 * (widthIn max=280dp, カード背景, padding 11dp) を外側で1回だけ描画し、
 * 内部にテキスト・ツールカードを isStreaming や tool_call の有無に関わらず
 * 同じ1つの吹き出しとして縦に並べる。
 */
@Composable
private fun BubbleContainer(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
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
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

/**
 * 吹き出し内側のテキストセグメント。背景・枠線は持たず、
 * 外側の BubbleContainer が提供する吹き出し背景の上にそのまま乗る。
 */
@Composable
private fun BubbleText(text: String) {
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
