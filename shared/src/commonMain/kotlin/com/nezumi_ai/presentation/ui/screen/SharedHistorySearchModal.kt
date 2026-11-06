package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nezumi_ai.presentation.ui.model.SharedHistorySearchResult

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SharedHistorySearchModal(
    query: String,
    results: List<SharedHistorySearchResult>,
    isSearching: Boolean,
    onQueryChange: (String) -> Unit,
    onResultClick: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }
    val groups = remember(results) {
        results.groupBy { it.sessionId }.values
            .sortedByDescending { group -> group.maxOf { it.score } }
            .map { it.sortedByDescending(SharedHistorySearchResult::score) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("履歴を検索") },
                singleLine = true
            )
            when {
                isSearching -> Text("検索中…", modifier = Modifier.padding(24.dp))
                query.isNotBlank() && groups.isEmpty() ->
                    Text("検索結果がありません", modifier = Modifier.padding(24.dp))
                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(groups, key = { it.first().sessionId }) { group ->
                        val sessionId = group.first().sessionId
                        val isExpanded = expanded[sessionId] == true
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    expanded[sessionId] = !isExpanded
                                }.padding(12.dp)
                            ) {
                                Text(
                                    text = group.first().sessionName,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (group.size > 1) {
                                    Text(if (isExpanded) "閉じる" else "${group.size}件")
                                }
                            }
                            SearchResultCard(group.first(), onResultClick)
                            if (isExpanded) {
                                group.drop(1).forEach { SearchResultCard(it, onResultClick) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: SharedHistorySearchResult,
    onResultClick: (Long, Long) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable {
            onResultClick(result.sessionId, result.messageId)
        },
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = result.chunkText,
            modifier = Modifier.padding(12.dp),
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
    }
}
