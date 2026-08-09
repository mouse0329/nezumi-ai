package com.nezumi_ai.presentation.ui.composable

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ToolResultCard
import com.google.ai.edge.litertlm.ToolCall

/**
 * インライン tool-call カードの状態。
 */
sealed class InlineToolCallStatus {
    /** ツール実行中 / タグ未完成。薄青背景 + 回転インジケータ。 */
    data object Running : InlineToolCallStatus()

    /** 成功。薄紫背景 + チェックマーク。 */
    data class Success(val card: ToolResultCard?) : InlineToolCallStatus()

    /** 失敗。薄赤背景 + バツ。 */
    data class Error(val card: ToolResultCard?, val message: String? = null) : InlineToolCallStatus()
}

/**
 * インライン tool-call カード。
 * モック `nezumi-tool-calls-v2.html` に準拠した薄紫 (#EDE6F3) / 薄青 (#E7F0FE) / 薄赤 (#FBE9E9) の
 * 角丸ブロック。円形アイコン + タイトル + サブテキストで、タップすると arguments と結果を展開表示する。
 *
 * @param toolCall モデルが呼び出した ToolCall (未パースなら null)
 * @param rawJson  <tool_call>...</tool_call> の中身 (トグル展開時に arguments が表示できないときのフォールバック)
 * @param status Running / Success / Error
 */
@Composable
fun InlineToolCallCard(
    toolCall: ToolCall?,
    rawJson: String,
    status: InlineToolCallStatus,
    modifier: Modifier = Modifier
) {
    // ダークモード対応: 背景・アイコン少底・アイコン tint はセマンティックな color リソースに列す、
    // values-night でダーク背景とコントラストする値に差し替える。ハードコードはポーシーズ・ハネリスト
    // マークなどをハードコードしていた旧実装は、ダークモードでカードが周囲の吹き出しと一体化して
    // 見えなくなる問題があったためこのリソース化を行う。
    val (bgColor, iconBg, iconContent, iconColor) = when (status) {
        InlineToolCallStatus.Running -> ToolCardVisuals(
            bg = colorResource(id = R.color.tool_card_running_bg),
            iconBg = colorResource(id = R.color.tool_card_running_icon_bg),
            iconContent = ToolCardIcon.Spinner,
            iconTint = colorResource(id = R.color.tool_card_running_icon_tint)
        )
        is InlineToolCallStatus.Success -> ToolCardVisuals(
            bg = colorResource(id = R.color.tool_card_success_bg),
            iconBg = colorResource(id = R.color.tool_card_success_icon_bg),
            iconContent = ToolCardIcon.Check,
            iconTint = colorResource(id = R.color.tool_card_success_icon_tint)
        )
        is InlineToolCallStatus.Error -> ToolCardVisuals(
            bg = colorResource(id = R.color.tool_card_error_bg),
            iconBg = colorResource(id = R.color.tool_card_error_icon_bg),
            iconContent = ToolCardIcon.Cross,
            iconTint = colorResource(id = R.color.tool_card_error_icon_tint)
        )
    }
    val titleColor = colorResource(id = R.color.tool_card_title)
    val subtitleColor = colorResource(id = R.color.tool_card_subtitle)
    val chevronColor = colorResource(id = R.color.tool_card_chevron)

    var expanded by remember { mutableStateOf(false) }
    val title = titleForToolCall(toolCall, rawJson)
    val subtitle = subtitleForToolCall(toolCall, rawJson)

    Box(
        modifier = modifier
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor, shape = RoundedCornerShape(12.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .animateContentSize()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 円形アイコン
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(iconBg, shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    when (iconContent) {
                        ToolCardIcon.Spinner -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = iconColor
                        )
                        ToolCardIcon.Check -> Text(
                            text = "✓",
                            color = iconColor,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        ToolCardIcon.Cross -> Text(
                            text = "×",
                            color = iconColor,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = title,
                        color = titleColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 18.sp
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            color = subtitleColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp
                        )
                    }
                }
                Text(
                    text = if (expanded) "▾" else "▸",
                    color = chevronColor,
                    fontSize = 14.sp
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                InlineToolCallDetails(
                    toolCall = toolCall,
                    rawJson = rawJson,
                    status = status
                )
            }
        }
    }
}

@Composable
private fun InlineToolCallDetails(
    toolCall: ToolCall?,
    rawJson: String,
    status: InlineToolCallStatus
) {
    val argumentsText = when {
        toolCall != null && toolCall.arguments.isNotEmpty() ->
            toolCall.arguments.entries.joinToString("\n") { (k, v) -> "$k: $v" }
        rawJson.isNotBlank() -> rawJson
        else -> "(引数なし)"
    }
    val resultText = when (status) {
        InlineToolCallStatus.Running -> "実行中…"
        is InlineToolCallStatus.Success -> status.card?.payload
            ?.entries
            ?.joinToString("\n") { (k, v) -> "$k: $v" }
            ?.ifBlank { "(結果なし)" }
        // バグ修正 (最後のツールコールだけ結果が出ないバグ):
        //   InlineToolCallMessageBody 側でタグが complete になった時点で Success に
        //   遷移させるようにしたため、ここでは `card == null` = 「モデルへ送信済みで
        //   結果 JSON はまだ流れてきていない」状態を意味する。旧文言「(結果を待機中)」は
        //   ユーザーには「ツールが止まっている」ように見えてしまうため、送信済みで
        //   あることが伝わる表記に置き換える。
            ?: "(モデルへ送信済み)"
        is InlineToolCallStatus.Error -> buildString {
            val msg = status.message?.takeIf { it.isNotBlank() }
            if (msg != null) {
                appendLine(msg)
            }
            val payload = status.card?.payload
                ?.entries
                ?.joinToString("\n") { (k, v) -> "$k: $v" }
            if (!payload.isNullOrBlank()) append(payload)
        }.ifBlank { "(エラー詳細なし)" }
    }

    val detailsBg = colorResource(id = R.color.tool_card_details_bg)
    val labelColor = colorResource(id = R.color.tool_card_details_label)
    val textColor = colorResource(id = R.color.tool_card_details_text)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(detailsBg, shape = RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = "arguments",
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        SelectionContainer {
            Text(
                text = argumentsText,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightInMax(160.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "result",
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(2.dp))
        SelectionContainer {
            Text(
                text = resultText,
                color = textColor,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightInMax(200.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
    }
}

/** Compose の Modifier.heightIn(max=...) の別名 (可読性のため)。 */
private fun Modifier.heightInMax(max: androidx.compose.ui.unit.Dp): Modifier =
    this.heightIn(max = max)

private data class ToolCardVisuals(
    val bg: Color,
    val iconBg: Color,
    val iconContent: ToolCardIcon,
    val iconTint: Color
)

private enum class ToolCardIcon { Spinner, Check, Cross }

/**
 * ツール名から人が読める日本語タイトルに変換する。
 * 未知のツール名は生の名前をそのまま返す (デザイン仕様で「日本語の動作名」推奨だが、
 * どれが該当するかはツール登録側でしか正確に判断できないため、既知エイリアスのみ変換)。
 */
private fun titleForToolCall(toolCall: ToolCall?, rawJson: String): String {
    val name = toolCall?.name
        ?: extractToolNameFromRawJson(rawJson)
        ?: return "ツール呼び出し"
    return when (name.lowercase()) {
        "set_alarm", "setalarm" -> "アラームを設定"
        "dismiss_alarm", "dismissalarm" -> "アラームを解除"
        "list_alarms", "listalarms" -> "アラーム一覧を取得"
        "start_timer", "starttimer" -> "タイマーを開始"
        "stop_timer", "stoptimer" -> "タイマーを停止"
        "set_flashlight", "setflashlight" -> "ライトを操作"
        "send_message", "sendmessage" -> "メッセージを送信"
        "search", "web_search", "websearch" -> "検索"
        "search_memory", "searchmemory" -> "記憶を検索"
        "get_current_time", "getcurrenttime" -> "現在時刻を確認"
        "get_battery_level", "getbatterylevel" -> "バッテリー残量を確認"
        "generate_image", "generateimage" -> "画像を生成"
        "convert_md_to_document", "convertmdtodocument" -> "ドキュメントに変換"
        "convert_document_to_md", "convertdocumenttomd" -> "Markdownに変換"
        else -> name
    }
}

private fun subtitleForToolCall(toolCall: ToolCall?, rawJson: String): String {
    val name = toolCall?.name
        ?: extractToolNameFromRawJson(rawJson)
        ?: return ""
    val args = toolCall?.arguments
    val argsPreview = if (args.isNullOrEmpty()) {
        ""
    } else {
        // 主要な引数だけを短く表示 (すべて出すと長いので先頭 3 件)
        args.entries.take(3).joinToString(", ") { (k, v) ->
            val short = v.toString().let { if (it.length > 24) it.take(24) + "…" else it }
            "$k=$short"
        }
    }
    return if (argsPreview.isBlank()) name else "$name · $argsPreview"
}

private val toolNameFromJsonPattern = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

private fun extractToolNameFromRawJson(rawJson: String): String? {
    if (rawJson.isBlank()) return null
    return toolNameFromJsonPattern.find(rawJson)?.groupValues?.getOrNull(1)
}
