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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
 * @param onSaveDocument ドキュメント作成カードの「保存」ボタンのコールバック。
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
                    // 通常は実行結果カードを優先する。まだ結果が届いていない場合でも、
                    // ツール呼び出し自体が COMPLETE になった瞬間には arguments が確定している。
                    // convert_md_to_document は保存ボタンの入力が呼び出し arguments だけで揃うため、
                    // ここで暫定 ToolResultCard を作り、保存ボタンをツール結果待ちにしない。
                    //
                    // Bug fix: 暫定カードの toolName に `seg.toolCall.name` (モデルが吐いた
                    // 生の名前) をそのまま入れると、モデルが camelCase (convertMdToDocument)
                    // や snake_case (convert_md_to_document) のどちらで返してくるかによって
                    // InlineToolCallCard 側の保存ボタン判定が素通りし、
                    // 「呼び出し完了直後は保存ボタンが出ない」バグになる。
                    // ToolResultCard の toolName の契約は「正規化済みツール名」なので、
                    // 実行結果カード (ChatViewModel 側で正規化済み) と揃うよう、
                    // ここでも `_` を除いて小文字化した名前を入れる。
                    val actualCard = toolResults.getOrNull(seg.index)
                        ?: inlineToolResponseCards.getOrNull(seg.index)
                    val completedCallCard = if (
                        actualCard == null &&
                        seg.status == GgufToolCallParser.Segment.CompletionStatus.COMPLETE &&
                        seg.toolCall != null
                    ) {
                        ToolResultCard(
                            toolName = normalizeToolNameForCard(seg.toolCall.name),
                            success = true,
                            payload = seg.toolCall.arguments.mapValues { (_, value) ->
                                value.toJsonElement()
                            }
                        )
                    } else {
                        null
                    }
                    val card = actualCard ?: completedCallCard

                    // 完了の判定基準:
                    //   - 実行結果カードが届いていれば、その結果をそのまま表示する。
                    //   - 結果カードがまだ無くても、tool_call の閉じタグまで到達していて
                    //     ToolCall が確定していれば、UI 上は呼び出し完了として暫定カードを使う。
                    //     これにより convert_md_to_document の「保存」ボタンを、ツール実行結果を
                    //     待たずに呼び出し完了直後から表示できる。
                    //   - 未完タグは Running、トークン切れは Error のまま。
                    val status = when {
                        actualCard != null && actualCard.success -> InlineToolCallStatus.Success(actualCard)
                        actualCard != null && !actualCard.success -> InlineToolCallStatus.Error(actualCard)
                        completedCallCard != null -> InlineToolCallStatus.Success(completedCallCard)
                        seg.status == GgufToolCallParser.Segment.CompletionStatus.TRUNCATED ->
                            InlineToolCallStatus.Error(
                                card = GgufToolCallParser.buildTruncatedFailureCard(seg.toolCall?.name),
                                message = "ツール呼び出しが途中で切れました（トークン上限）。実行されていません。"
                            )
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
 * 暫定 [ToolResultCard] に載せるツール名を、`NezumiLiteRtToolExecutor.normalizeToolName`
 * のフォールバック規則 (`_` 除去 + 小文字化) に合わせて正規化する。
 * 実行結果カードは ChatViewModel 側で常に正規化済みの名前で作られているため、
 * ここも同じ規則で揃えておかないと、保存ボタン判定など「正規化名で比較する側」の
 * ロジックが暫定カードだけ素通りしてしまう。
 */
private fun normalizeToolNameForCard(name: String): String =
    name.replace("_", "").lowercase()

private fun Any?.toJsonElement(): JsonElement {
    return when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is Map<*, *> -> JsonObject(
            entries
                .filter { it.key is String }
                .associate { (key, value) ->
                    (key as String) to value.toJsonElement()
                }
        )
        is Iterable<*> -> JsonArray(map { it.toJsonElement() })
        else -> JsonPrimitive(toString())
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
