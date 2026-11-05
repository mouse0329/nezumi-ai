package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nezumi_ai.presentation.ui.model.SharedSessionSummary

@Composable
fun SharedSessionListScreen(
    sessions: List<SharedSessionSummary>,
    currentSessionId: Long?,
    onSessionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "セッション"
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(16.dp)
            )
            if (sessions.isEmpty()) {
                Text(
                    text = "セッションはありません",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSessionClick(session.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                text = session.name.ifBlank { "無題のセッション" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (session.id == currentSessionId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (session.isPinned || session.isIncognito) {
                                Text(
                                    text = listOfNotNull(
                                        "固定".takeIf { session.isPinned },
                                        "シークレット".takeIf { session.isIncognito }
                                    ).joinToString("・"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
