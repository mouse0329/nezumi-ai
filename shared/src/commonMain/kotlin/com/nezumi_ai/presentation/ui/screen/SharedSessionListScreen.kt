package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nezumi_ai.presentation.ui.model.SharedSessionGroup
import com.nezumi_ai.presentation.ui.model.SharedSessionSummary

@Composable
fun SharedSessionListScreen(
    groups: List<SharedSessionGroup>,
    currentSessionId: Long?,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "セッション",
    isLoading: Boolean = false,
    onCreateSession: () -> Unit = {},
    onCreateIncognitoSession: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onDeleteSession: (Long) -> Unit = {},
    onTogglePin: (Long) -> Unit = {},
    onRenameSession: (Long, String) -> Unit = { _, _ -> },
) {
    var renamingSession by remember { mutableStateOf<SharedSessionSummary?>(null) }
    var deletingSession by remember { mutableStateOf<SharedSessionSummary?>(null) }
    var renameText by remember { mutableStateOf("") }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenSettings) { Text("設定") }
                TextButton(onClick = onSearchClick) { Text("検索") }
                TextButton(onClick = onCreateIncognitoSession) { Text("秘密") }
                Button(onClick = onCreateSession) { Text("新規") }
            }
            when {
                isLoading -> Text(
                    text = "読み込み中…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                groups.isEmpty() -> Text(
                    text = "セッションはありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    groups.forEach { group ->
                        item(key = "group_${group.label}") {
                            Text(
                                text = group.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(group.sessions, key = { it.id }) { session ->
                            SharedSessionRow(
                                session = session,
                                isCurrent = session.id == currentSessionId,
                                onClick = { onSessionClick(session.id) },
                                onRename = {
                                    renamingSession = session
                                    renameText = session.name
                                },
                                onTogglePin = { onTogglePin(session.id) },
                                onDelete = { deletingSession = session }
                            )
                        }
                    }
                }
            }
            if (renamingSession != null) {
                AlertDialog(
                    onDismissRequest = { renamingSession = null },
                    title = { Text("セッション名を変更") },
                    text = {
                        androidx.compose.material3.OutlinedTextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            singleLine = true,
                            label = { Text("新しい名前") }
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            renamingSession?.id?.let { id ->
                                if (renameText.isNotBlank()) onRenameSession(id, renameText.trim())
                            }
                            renamingSession = null
                        }) { Text("変更") }
                    },
                    dismissButton = {
                        TextButton(onClick = { renamingSession = null }) { Text("キャンセル") }
                    }
                )
            }
            if (deletingSession != null) {
                AlertDialog(
                    onDismissRequest = { deletingSession = null },
                    title = { Text("セッションを削除") },
                    text = { Text("このセッションとメッセージを削除します。よろしいですか？") },
                    confirmButton = {
                        TextButton(onClick = {
                            deletingSession?.id?.let(onDeleteSession)
                            deletingSession = null
                        }) { Text("削除") }
                    },
                    dismissButton = {
                        TextButton(onClick = { deletingSession = null }) { Text("キャンセル") }
                    }
                )
            }
        }
    }
}

@Composable
private fun SharedSessionRow(
    session: SharedSessionSummary,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.name.ifBlank { "無題のセッション" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val tags = listOfNotNull(
                "固定".takeIf { session.isPinned },
                "シークレット".takeIf { session.isIncognito }
            )
            if (tags.isNotEmpty()) {
                Text(
                    text = tags.joinToString("・"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = onTogglePin) { Text(if (session.isPinned) "解除" else "固定") }
        TextButton(onClick = onRename) { Text("名前") }
        TextButton(onClick = onDelete) { Text("削除") }
    }
}
