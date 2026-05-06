@file:OptIn(ExperimentalFoundationApi::class)

package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nezumi_ai.R
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import com.nezumi_ai.data.model.GroupedChatSessions
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionListRoute(
    viewModel: ChatSessionListViewModel,
    onOpenSettings: () -> Unit,
    onCreateSession: () -> Unit,
    onCreateIncognitoSession: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onRenameSession: (Long) -> Unit,
    currentSessionId: Long? = null
) {
    NezumiSessionTheme {
        val groupedSessions by viewModel.groupedSessions.collectAsStateWithLifecycle(initialValue = emptyList())
        SessionListScreen(
            groupedSessions = groupedSessions,
            onOpenSettings = onOpenSettings,
            onCreateSession = onCreateSession,
            onCreateIncognitoSession = onCreateIncognitoSession,
            onSessionClick = onSessionClick,
            onDeleteSession = onDeleteSession,
            onTogglePin = onTogglePin,
            onRenameSession = onRenameSession,
            currentSessionId = currentSessionId
        )
    }
}

@Composable
private fun SessionListScreen(
    groupedSessions: List<GroupedChatSessions>,
    onOpenSettings: () -> Unit,
    onCreateSession: () -> Unit,
    onCreateIncognitoSession: () -> Unit,
    onSessionClick: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onRenameSession: (Long) -> Unit,
    currentSessionId: Long?
) {
    val listState = rememberLazyListState()
    val totalSessions = groupedSessions.sumOf { it.sessions.size }
    LaunchedEffect(totalSessions) {
        if (totalSessions > 0) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.bg_session_list))
    ) {
        Spacer(modifier = Modifier.statusBarsPadding())
        SessionListHeader(
            onOpenSettings = onOpenSettings,
            onCreateSession = onCreateSession,
            onCreateIncognitoSession = onCreateIncognitoSession
        )

        if (totalSessions == 0) {
            EmptySessionState(
                modifier = Modifier.fillMaxSize(),
                onCreateSession = onCreateSession
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupedSessions.forEach { group ->
                    // 日付ラベルを表示
                    item(key = "label_${group.dateLabel}") {
                        DateHeader(dateLabel = group.dateLabel)
                    }
                    items(
                        items = group.sessions,
                        key = { "session_${it.id}" }
                    ) { session ->
                        SessionCard(
                            session = session,
                            onSessionClick = onSessionClick,
                            onDeleteSession = onDeleteSession,
                            onTogglePin = onTogglePin,
                            onRenameSession = onRenameSession,
                            isCurrentSession = session.id == currentSessionId
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DateHeader(dateLabel: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateLabel,
            color = colorResource(id = R.color.text_secondary),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SessionListHeader(
    onOpenSettings: () -> Unit,
    onCreateSession: () -> Unit,
    onCreateIncognitoSession: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_app_icon_96),
            contentDescription = stringResource(id = R.string.app_name),
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = stringResource(id = R.string.app_title_sessions),
            modifier = Modifier.weight(1f),
            color = colorResource(id = R.color.text_primary),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onOpenSettings) {
            Icon(
                painter = painterResource(id = R.drawable.ic_menu),
                contentDescription = "メニュー",
                tint = colorResource(id = R.color.text_primary)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FloatingActionButton(
                onClick = onCreateSession,
                containerColor = colorResource(id = R.color.primary),
                contentColor = colorResource(id = R.color.nezumi_on_primary),
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add),
                    contentDescription = "新規チャット"
                )
            }
            FloatingActionButton(
                onClick = onCreateIncognitoSession,
                containerColor = colorResource(id = R.color.text_secondary),
                contentColor = colorResource(id = R.color.nezumi_on_primary),
                modifier = Modifier.size(44.dp)
            ) {
                Text(
                    text = "🕵️",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
@Composable
private fun SessionCard(
    session: ChatSessionEntity,
    onSessionClick: (Long) -> Unit,
    onDeleteSession: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onRenameSession: (Long) -> Unit,
    isCurrentSession: Boolean
) {
    var showMenu by remember(session.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(
                color = colorResource(id = R.color.surface_card),
                shape = MaterialTheme.shapes.medium
            )
            .then(
                if (isCurrentSession) Modifier.border(
                    width = 2.dp,
                    color = colorResource(id = R.color.primary),
                    shape = MaterialTheme.shapes.medium
                ) else Modifier
            )
            .combinedClickable(
                onClick = { onSessionClick(session.id) },
                onLongClick = { showMenu = true }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (session.isPinned) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_pin),
                        contentDescription = "ピン留め",
                        tint = colorResource(id = R.color.primary),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                }
                DisableSelection {
                    Text(
                        text = session.name,
                        modifier = Modifier.weight(1f),
                        color = colorResource(id = R.color.text_primary),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_menu),
                        contentDescription = "メニュー",
                        tint = colorResource(id = R.color.text_secondary)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatDate(session.lastUpdated),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            DropdownMenuItem(
                text = { Text(if (session.isPinned) "ピン留め解除" else "ピン留め") },
                onClick = {
                    onTogglePin(session.id)
                    showMenu = false
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = if (session.isPinned) R.drawable.ic_pin else R.drawable.ic_pin_outline),
                        contentDescription = null
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("リネーム") },
                onClick = {
                    showMenu = false
                    onRenameSession(session.id)
                }
            )
            DropdownMenuItem(
                text = { Text("削除") },
                onClick = {
                    showMenu = false
                    onDeleteSession(session.id)
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_delete),
                        contentDescription = null
                    )
                }
            )
        }
    }
}

@Composable
private fun EmptySessionState(
    modifier: Modifier = Modifier,
    onCreateSession: () -> Unit
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_app_icon_96),
                contentDescription = stringResource(id = R.string.app_name),
                modifier = Modifier.size(80.dp),
                alpha = 0.5f
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.no_sessions_title),
                color = colorResource(id = R.color.text_primary),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.no_sessions_message),
                color = colorResource(id = R.color.text_secondary),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onCreateSession,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colorResource(id = R.color.primary),
                    contentColor = colorResource(id = R.color.nezumi_on_primary)
                )
            ) {
                Text(text = stringResource(id = R.string.create_first_session))
            }
        }
    }
}

@Composable
private fun NezumiSessionTheme(content: @Composable () -> Unit) {
    val bg = colorResource(id = R.color.bg_session_list)
    val primary = colorResource(id = R.color.primary)
    val onPrimary = colorResource(id = R.color.nezumi_on_primary)
    val primaryContainer = colorResource(id = R.color.nezumi_primary_container)
    val onPrimaryContainer = colorResource(id = R.color.nezumi_on_primary_container)
    val surface = colorResource(id = R.color.surface_card)
    val onSurface = colorResource(id = R.color.text_primary)
    val onSurfaceVariant = colorResource(id = R.color.text_secondary)

    val colorScheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onPrimaryContainer,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceVariant
        )
    } else {
        lightColorScheme(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            secondary = primary,
            onSecondary = onPrimary,
            secondaryContainer = primaryContainer,
            onSecondaryContainer = onPrimaryContainer,
            tertiary = primary,
            onTertiary = onPrimary,
            tertiaryContainer = primaryContainer,
            onTertiaryContainer = onPrimaryContainer,
            background = bg,
            onBackground = onSurface,
            surface = surface,
            onSurface = onSurface,
            surfaceVariant = surface,
            onSurfaceVariant = onSurfaceVariant
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}