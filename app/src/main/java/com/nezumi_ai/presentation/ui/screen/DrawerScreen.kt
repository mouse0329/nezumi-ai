package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ドロワーの履歴リスト項目。旧 DrawerHistoryAdapter.DrawerHistoryItem と同じ構造。
 * (item_drawer_session_label.xml / item_drawer_session.xml の Compose 置き換え)
 */
sealed class DrawerHistoryEntry {
    data class Label(val label: String) : DrawerHistoryEntry()
    data class Session(val session: ChatSessionEntity) : DrawerHistoryEntry()
}

/**
 * サイドドロワーの中身全体 (旧 activity_main.xml の drawer_panel 相当)。
 */
@Composable
fun DrawerContent(
    entries: List<DrawerHistoryEntry>,
    currentSessionId: Long?,
    sessionsEmpty: Boolean,
    onSessionClick: (ChatSessionEntity) -> Unit,
    onSessionMenuClick: (ChatSessionEntity) -> Unit,
    onSettingsClick: () -> Unit,
    onModelSettingsClick: () -> Unit,
    onPresetSettingsClick: () -> Unit,
    onMiniAppsClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onIncognitoClick: () -> Unit,
    onImageGenClick: () -> Unit,
    onSearchClick: () -> Unit,
    onListUpdated: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg_session_list))
            .padding(16.dp)
    ) {
        // ロゴセクション
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_nezumi_ai),
                contentDescription = stringResource(id = R.string.app_name),
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(24.dp),
                contentScale = ContentScale.Fit
            )
            Text(
                text = stringResource(id = R.string.app_name),
                color = colorResource(id = R.color.text_primary),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onSearchClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search),
                    contentDescription = "履歴検索",
                    tint = colorResource(id = R.color.text_primary)
                )
            }
        }

        // クイックナビ (設定・モデル・プリセット) 横並び
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp)
        ) {
            DrawerOutlinedButton(
                text = stringResource(id = R.string.action_settings),
                iconRes = R.drawable.settings_24,
                onClick = onSettingsClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            )
            DrawerOutlinedButton(
                text = stringResource(id = R.string.model_settings),
                iconRes = R.drawable.deployed_code_24,
                onClick = onModelSettingsClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            )
            DrawerOutlinedButton(
                text = stringResource(id = R.string.drawer_preset_button),
                iconRes = R.drawable.ic_nezumi_ai,
                onClick = onPresetSettingsClick,
                modifier = Modifier.weight(1f)
            )
        }

        // Mini Apps (Mini App Platform 仕様 v1.1 §35.5: Mini App Manager が唯一の入口)
        DrawerOutlinedButton(
            text = stringResource(id = R.string.miniapp_manager_title),
            iconRes = R.drawable.ic_menu,
            onClick = onMiniAppsClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            fontSize = 12.sp
        )

        // 新規セッションボタン (プライマリ)
        Button(
            onClick = onNewChatClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_add),
                contentDescription = null
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(id = R.string.new_session),
                fontWeight = FontWeight.Bold
            )
        }

        // サブアクション (シークレット・画像生成) 横並び
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            DrawerOutlinedButton(
                text = stringResource(id = R.string.drawer_incognito_button),
                iconRes = R.drawable.ic_menu,
                onClick = onIncognitoClick,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp),
                fontSize = 12.sp
            )
            DrawerOutlinedButton(
                text = stringResource(id = R.string.drawer_image_gen_start),
                iconRes = android.R.drawable.ic_menu_gallery,
                onClick = onImageGenClick,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp
            )
        }

        Text(
            text = stringResource(id = R.string.drawer_history_label),
            color = colorResource(id = R.color.text_secondary),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            items(entries, key = { entry ->
                when (entry) {
                    is DrawerHistoryEntry.Label -> "label_${entry.label}"
                    is DrawerHistoryEntry.Session -> "session_${entry.session.id}"
                }
            }) { entry ->
                when (entry) {
                    is DrawerHistoryEntry.Label -> DrawerSessionLabelItem(label = entry.label)
                    is DrawerHistoryEntry.Session -> DrawerSessionRow(
                        session = entry.session,
                        isCurrent = entry.session.id == currentSessionId,
                        onClick = { onSessionClick(entry.session) },
                        onMenuClick = { onSessionMenuClick(entry.session) }
                    )
                }
            }
            if (sessionsEmpty) {
                item(key = "empty") {
                    Text(
                        text = stringResource(id = R.string.drawer_history_empty),
                        color = colorResource(id = R.color.text_secondary),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerOutlinedButton(
    text: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 11.sp
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 40.dp),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = colorResource(id = R.color.drawer_text),
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = text,
            color = colorResource(id = R.color.drawer_text),
            fontSize = fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** item_drawer_session_label.xml 相当。 */
@Composable
fun DrawerSessionLabelItem(label: String) {
    Text(
        text = label,
        fontSize = 12.sp,
        color = colorResource(id = R.color.text_secondary),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 8.dp)
    )
}

/**
 * item_drawer_session.xml 相当。
 * 背景は drawable (bg_input_field.xml / bg_current_session.xml) を参照せず、
 * 同じ見た目 (solid + stroke + corner) を Compose の Modifier で再現する。
 */
@Composable
fun DrawerSessionRow(
    session: ChatSessionEntity,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val rowShape = if (isCurrent) RoundedCornerShape(8.dp) else RoundedCornerShape(22.dp)
    val backgroundColor = colorResource(
        id = if (isCurrent) R.color.surface_card else R.color.bg_chat
    )
    val borderStroke = if (isCurrent) {
        BorderStroke(2.dp, colorResource(id = R.color.primary))
    } else {
        BorderStroke(1.dp, colorResource(id = R.color.border))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(backgroundColor, rowShape)
            .border(borderStroke, rowShape)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (session.isPinned) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_pin),
                    contentDescription = "ピン留め",
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(14.dp)
                )
            }
            Text(
                text = session.name.ifBlank { "無題のチャット" },
                color = colorResource(id = R.color.text_primary),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onMenuClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_more_vert),
                    contentDescription = "メニュー",
                    tint = colorResource(id = R.color.text_primary),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(session.lastUpdated)),
            color = colorResource(id = R.color.text_secondary),
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
