package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.presentation.ui.theme.nezumiSwitchColors
import com.nezumi_ai.utils.PreferencesHelper

/**
 * エフォートセグメントの不透明度。
 *
 * 要望変更: 思考強度は Thinking OFF でも切り替え可能にするため、
 * 旧来の「OFF 時は薄くして押せない」dim 表現は廃止し常に 1.0f を返す
 * (引数は呼び出し側シグネチャ互換のために残す)。
 */
internal fun effortSegmentsAlpha(thinkingOn: Boolean): Float = 1.0f

/**
 * chat_template の [ModelNameHeuristics.ReasoningEffortGranularity] に応じた
 * エフォート選択肢を返す。
 *  - Binary → ["low"] (Granite 4.x 系: "low" かそれ以外かの二値のみ解釈)
 *  - Graded → 検出されたレベル集合 (3 値とは限らない)
 *  - None   → 空 (テンプレートに effort 概念なし。セグメント自体を表示しない)
 */
internal fun availableEffortLevels(
    granularity: ModelNameHeuristics.ReasoningEffortGranularity
): List<String> = ModelNameHeuristics.supportedEffortLevels(granularity)

/**
 * 旧 sheet_attachment_options.xml の Compose 置き換え。
 * 「シンキング」セクション (トグル + Low/Medium/High セグメント) を先頭に置き、
 * 区切り線の下に「画像」「カメラ」「ファイル」の 3 タイルと「キャンセル」を並べる。
 * BottomSheetDialog のコンテンツ、または Compose の ModalBottomSheet から使う。
 *
 * [thinkingOn] が null のときはシンキングセクション自体を表示しない
 * (モデルがシンキング非対応の場合など、従来どおり添付のみのシートになる)。
 * [effortLevels] はロード中モデルの chat_template が解釈できる思考強度レベル
 * (PreferencesHelper.resolveSupportedThinkingEffortLevels の結果)。空のときは
 * エフォートセグメントを表示せず ON/OFF トグルのみとする。
 */
@Composable
fun AttachmentOptionsSheet(
    imageEnabled: Boolean,
    onImageClick: () -> Unit,
    onCameraClick: () -> Unit,
    onFileClick: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    thinkingOn: Boolean? = null,
    thinkingEffort: String = PreferencesHelper.THINKING_EFFORT_LOW,
    effortLevels: List<String> = PreferencesHelper.ALL_THINKING_EFFORTS,
    onThinkingChange: (Boolean) -> Unit = {},
    onEffortChange: (String) -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colorResource(R.color.surface_card))
            .padding(16.dp)
    ) {
        // 上部のドラッグハンドル相当 (36dp x 4dp のバー)
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .width(36.dp)
                .height(4.dp)
                .background(colorResource(R.color.border), RoundedCornerShape(2.dp))
        )

        Text(
            text = stringResource(R.string.attachment_options_title),
            color = colorResource(R.color.text_secondary),
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)
        )

        // シンキングセクション: ラベル + トグル + エフォートセグメントを同一行に並べる。
        // OFF のときセグメントは薄くして押せなくするが、非表示にはしない
        // (最後に選んだ値は thinkingEffort 側に残る)。
        if (thinkingOn != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 14.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_thinking_label),
                    color = colorResource(R.color.text_primary),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Switch(
                    checked = thinkingOn,
                    onCheckedChange = onThinkingChange,
                    colors = nezumiSwitchColors(),
                    modifier = Modifier.padding(start = 12.dp)
                )
                // 要望: 思考強度は Thinking OFF でも切り替え可能 (常に enabled)。
                // ただしテンプレートが reasoning_effort を解釈しないモデルでは
                // セグメント自体を表示しない (UI から消す)。
                if (effortLevels.isNotEmpty()) {
                    ThinkingEffortSegments(
                        selected = thinkingEffort,
                        enabled = true,
                        onSelect = onEffortChange,
                        levels = effortLevels,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
            HorizontalDivider(
                color = colorResource(R.color.border),
                modifier = Modifier.padding(bottom = 14.dp)
            )
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            AttachmentOptionTile(
                iconRes = R.drawable.ic_image,
                label = stringResource(R.string.attachment_options_image),
                iconBackgroundColor = colorResource(R.color.nezumi_primary_container),
                iconTint = colorResource(R.color.nezumi_primary),
                enabled = imageEnabled,
                onClick = onImageClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 6.dp)
            )
            AttachmentOptionTile(
                iconRes = R.drawable.ic_camera,
                label = stringResource(R.string.attachment_options_camera),
                iconBackgroundColor = Color(0xFFF3E9DE),
                iconTint = Color(0xFFA5652F),
                enabled = imageEnabled,
                onClick = onCameraClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 3.dp, end = 3.dp)
            )
            AttachmentOptionTile(
                iconRes = R.drawable.ic_description,
                label = stringResource(R.string.attachment_options_file),
                iconBackgroundColor = Color(0xFFE9E6F5),
                iconTint = Color(0xFF5B4E9C),
                enabled = true,
                onClick = onFileClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            )
        }

        // bg_option_tile.xml (bg_chat + border + 16dp 角) 相当
        Text(
            text = stringResource(R.string.attachment_options_cancel),
            color = colorResource(R.color.text_secondary),
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(colorResource(R.color.bg_chat), RoundedCornerShape(16.dp))
                .clickable(onClick = onCancel)
                .padding(vertical = 14.dp)
        )
    }
}

/**
 * エフォート選択セグメントコントロール。
 * 表示するレベルは [levels] (chat_template 解析結果) に従う。
 * [enabled] = false (Thinking OFF) のときは半透明になり操作を受け付けないが、
 * 選択値 [selected] の表示は維持する。
 */
@Composable
private fun ThinkingEffortSegments(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    levels: List<String>,
    modifier: Modifier = Modifier
) {
    // 保存済み値が非対応レベル (BINARY モデルへの medium/high 等) の場合は
    // 先頭レベルの選択表示にフォールバックする (後方互換ガード)。
    val effectiveSelected = if (selected in levels) selected else levels.firstOrNull()
    val entries = levels.map { level ->
        val label = when (level.trim().lowercase()) {
            PreferencesHelper.THINKING_EFFORT_LOW -> stringResource(R.string.thinking_effort_low)
            PreferencesHelper.THINKING_EFFORT_MEDIUM -> stringResource(R.string.thinking_effort_medium)
            PreferencesHelper.THINKING_EFFORT_HIGH -> stringResource(R.string.thinking_effort_high)
            // 未知のレベル (minimal 等) はリソースがないため生の値をそのまま表示する。
            else -> level
        }
        level to label
    }
    Row(
        modifier = modifier
            .alpha(effortSegmentsAlpha(enabled))
            .border(1.dp, colorResource(R.color.border), RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
    ) {
        entries.forEachIndexed { index, (level, label) ->
            val isSelected = level == effectiveSelected
            Text(
                text = label,
                color = colorResource(
                    if (isSelected) R.color.text_primary else R.color.text_secondary
                ),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .background(
                        if (isSelected) colorResource(R.color.bg_chat) else Color.Transparent
                    )
                    .clickable(enabled = enabled) { onSelect(level) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
            )
            if (index < entries.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .align(Alignment.CenterVertically)
                        .background(colorResource(R.color.border))
                )
            }
        }
    }
}

@Composable
private fun AttachmentOptionTile(
    iconRes: Int,
    label: String,
    iconBackgroundColor: Color,
    iconTint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 無効時は半透明にしてグレイアウト (旧 applyAttachmentTileEnabled と同等)
    val contentAlpha = if (enabled) 1.0f else 0.38f
    Column(
        modifier = modifier
            .alpha(contentAlpha)
            .background(colorResource(R.color.bg_chat), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .background(iconBackgroundColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(26.dp)
            )
        }
        Text(
            text = label,
            color = colorResource(
                if (enabled) R.color.text_primary else R.color.text_secondary
            ),
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
