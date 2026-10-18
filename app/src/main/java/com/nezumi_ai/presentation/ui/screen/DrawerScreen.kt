package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerContent(
    entries: List<DrawerHistoryEntry>,
    currentSessionId: Long?,
    sessionsEmpty: Boolean,
    historyLoading: Boolean,
    menuSessionId: Long?,
    onSessionClick: (ChatSessionEntity) -> Unit,
    onSessionMenuClick: (ChatSessionEntity) -> Unit,
    onSessionMenuDismiss: () -> Unit,
    onTogglePin: (ChatSessionEntity) -> Unit,
    onRenameSession: (ChatSessionEntity) -> Unit,
    onDeleteSession: (ChatSessionEntity) -> Unit,
    onExportSession: (ChatSessionEntity) -> Unit = {},
    onBulkDeleteSessions: (List<ChatSessionEntity>) -> Unit = {},
    onBulkSetPin: (List<ChatSessionEntity>, pinned: Boolean) -> Unit = { _, _ -> },
    onBulkExportSessions: (List<ChatSessionEntity>) -> Unit = {},
    onSettingsClick: () -> Unit,
    onModelSettingsClick: () -> Unit,
    onPresetSettingsClick: () -> Unit,
    onMiniAppsClick: () -> Unit,
    showMiniApps: Boolean = false,
    onNewChatClick: () -> Unit,
    onIncognitoClick: () -> Unit,
    onImageGenClick: () -> Unit,
    onSearchClick: () -> Unit,
    onListUpdated: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 長押しで複数選択モードに入り、一括固定・一括削除できるようにする。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }

    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
    }

    fun selectedSessions(): List<ChatSessionEntity> =
        entries.mapNotNull { (it as? DrawerHistoryEntry.Session)?.session }
            .filter { it.id in selectedIds }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg_session_list))
            .statusBarsPadding()
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
            IconButton(onClick = onSearchClick, modifier = Modifier.requiredSize(36.dp)) {
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
        // ベータのため標準では非表示。全般設定のスイッチで表示。
        if (showMiniApps) {
            DrawerOutlinedButton(
                text = stringResource(id = R.string.miniapp_manager_title),
                iconRes = R.drawable.ic_menu,
                onClick = onMiniAppsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                fontSize = 12.sp
            )
        }

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

        if (selectionMode) {
            val selected = selectedSessions()
            val allPinned = selected.isNotEmpty() && selected.all { it.isPinned }
            val context = LocalContext.current
            val isJa = Locale.getDefault().language.startsWith("ja")
            val countLabel = if (isJa) {
                "${selectedIds.size}件"
            } else {
                "${selectedIds.size} Selected"
            }
            val pinHint = when {
                isJa && allPinned -> "固定を解除"
                isJa -> "固定"
                allPinned -> "Unpin"
                else -> "Pin"
            }
            val exportHint = if (isJa) "書き出し" else "Export"
            val deleteHint = if (isJa) "削除" else "Delete"
            val cancelHint = if (isJa) "キャンセル" else "Cancel"

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                // 1 行目: 件数 + 閉じる
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = countLabel,
                        color = colorResource(id = R.color.text_primary),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 4.dp)
                    )
                    DrawerSelectionIconButton(
                        iconRes = R.drawable.ic_close,
                        contentDescription = cancelHint,
                        enabled = true,
                        onClick = { exitSelection() },
                        onLongClick = {
                            Toast.makeText(context, cancelHint, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
                // 2 行目: 固定 / 書き出し / 削除（アイコンのみ、長押しで説明）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DrawerSelectionIconButton(
                        iconRes = if (allPinned) R.drawable.ic_pin_outline else R.drawable.ic_pin,
                        contentDescription = pinHint,
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isEmpty()) return@DrawerSelectionIconButton
                            onBulkSetPin(selected, !allPinned)
                            exitSelection()
                        },
                        onLongClick = {
                            Toast.makeText(context, pinHint, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    DrawerSelectionIconButton(
                        iconRes = R.drawable.ic_save_24,
                        contentDescription = exportHint,
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isEmpty()) return@DrawerSelectionIconButton
                            onBulkExportSessions(selected)
                            exitSelection()
                        },
                        onLongClick = {
                            Toast.makeText(context, exportHint, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    DrawerSelectionIconButton(
                        iconRes = R.drawable.ic_delete,
                        contentDescription = deleteHint,
                        enabled = selected.isNotEmpty(),
                        onClick = {
                            if (selected.isEmpty()) return@DrawerSelectionIconButton
                            onBulkDeleteSessions(selected)
                            exitSelection()
                        },
                        onLongClick = {
                            Toast.makeText(context, deleteHint, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            Text(
                text = stringResource(id = R.string.drawer_history_label),
                color = colorResource(id = R.color.text_secondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            if (historyLoading && entries.isEmpty()) {
                item(key = "loading") {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            items(entries, key = { entry ->
                when (entry) {
                    is DrawerHistoryEntry.Label -> "label_${entry.label}"
                    is DrawerHistoryEntry.Session -> "session_${entry.session.id}"
                }
            }) { entry ->
                when (entry) {
                    is DrawerHistoryEntry.Label -> DrawerSessionLabelItem(label = entry.label)
                    is DrawerHistoryEntry.Session -> {
                        val sid = entry.session.id
                        val isSelected = sid in selectedIds
                        DrawerSessionRow(
                            session = entry.session,
                            isCurrent = sid == currentSessionId,
                            isSelected = isSelected,
                            selectionMode = selectionMode,
                            onClick = {
                                if (selectionMode) {
                                    selectedIds = if (isSelected) {
                                        selectedIds - sid
                                    } else {
                                        selectedIds + sid
                                    }
                                    if (selectedIds.isEmpty()) {
                                        selectionMode = false
                                    }
                                } else {
                                    onSessionClick(entry.session)
                                }
                            },
                            onLongClick = {
                                selectionMode = true
                                selectedIds = selectedIds + sid
                                onSessionMenuDismiss()
                            },
                            onMenuClick = { onSessionMenuClick(entry.session) },
                            menuExpanded = !selectionMode && sid == menuSessionId,
                            onMenuDismiss = onSessionMenuDismiss,
                            onTogglePin = { onTogglePin(entry.session) },
                            onRename = { onRenameSession(entry.session) },
                            onExport = { onExportSession(entry.session) },
                            onDelete = { onDeleteSession(entry.session) }
                        )
                    }
                }
            }
            if (sessionsEmpty && !historyLoading) {
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

/** 複数選択バー用: アイコンのみ。長押しで説明トースト。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSelectionIconButton(
    iconRes: Int,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = colorResource(
        id = if (enabled) R.color.primary else R.color.text_secondary
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
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
 * 長押しで複数選択モードに入り、一括固定・一括削除できる。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrawerSessionRow(
    session: ChatSessionEntity,
    isCurrent: Boolean,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onMenuClick: () -> Unit,
    menuExpanded: Boolean,
    onMenuDismiss: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit = {},
    onDelete: () -> Unit
) {
    val rowShape = when {
        isSelected || isCurrent -> RoundedCornerShape(8.dp)
        else -> RoundedCornerShape(22.dp)
    }
    val backgroundColor = colorResource(
        id = when {
            isSelected -> R.color.surface_card
            isCurrent -> R.color.surface_card
            else -> R.color.bg_chat
        }
    )
    val borderStroke = when {
        isSelected -> BorderStroke(2.dp, colorResource(id = R.color.primary))
        isCurrent -> BorderStroke(2.dp, colorResource(id = R.color.primary))
        else -> BorderStroke(1.dp, colorResource(id = R.color.border))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(backgroundColor, rowShape)
            .border(borderStroke, rowShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (selectionMode) {
                Text(
                    text = if (isSelected) "✓" else "○",
                    color = colorResource(
                        id = if (isSelected) R.color.primary else R.color.text_secondary
                    ),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 8.dp)
                )
            } else if (session.isPinned) {
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
            if (!selectionMode) {
                Box {
                    IconButton(onClick = onMenuClick, modifier = Modifier.requiredSize(36.dp)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_more_vert),
                            contentDescription = "メニュー",
                            tint = colorResource(id = R.color.text_primary),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = onMenuDismiss
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (session.isPinned) "固定を解除" else "固定") },
                            onClick = onTogglePin
                        )
                        DropdownMenuItem(text = { Text("名前を変更") }, onClick = onRename)
                        DropdownMenuItem(text = { Text("書き出し") }, onClick = onExport)
                        DropdownMenuItem(text = { Text("削除") }, onClick = onDelete)
                    }
                }
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
