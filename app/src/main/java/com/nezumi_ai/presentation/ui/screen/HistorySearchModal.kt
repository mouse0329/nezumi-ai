package com.nezumi_ai.presentation.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.nezumi_ai.presentation.ui.composable.SvgSpinner
import com.nezumi_ai.R
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySearchModal(
    viewModel: ChatSessionListViewModel,
    onResultClick: (sessionId: Long, messageId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    // セッションごとにグループ化（スコア順は維持）
    val grouped = remember(results) {
        results
            .groupBy { it.sessionId }
            .entries
            .sortedByDescending { (_, chunks) -> chunks.maxOf { it.score } }
            .map { (_, chunks) -> chunks.sortedByDescending { it.score } }
    }

    // 各セッションの展開状態（デフォルト全部閉じ）
    val expandedSessions = remember(grouped) {
        mutableStateMapOf<Long, Boolean>()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colorResource(id = R.color.bg_session_list),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(id = R.string.history_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search),
                        contentDescription = null,
                        tint = colorResource(id = R.color.text_secondary)
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(id = R.string.history_search_clear_description),
                                tint = colorResource(id = R.color.text_secondary)
                            )
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colorResource(id = R.color.text_primary),
                    unfocusedTextColor = colorResource(id = R.color.text_primary),
                    focusedBorderColor = colorResource(id = R.color.primary),
                    unfocusedBorderColor = colorResource(id = R.color.text_secondary)
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                isSearching -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        SvgSpinner(modifier = Modifier.size(48.dp))
                    }
                }
                query.isNotEmpty() && grouped.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(id = R.string.history_search_no_results), color = colorResource(id = R.color.text_secondary))
                    }
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(grouped, key = { _, group -> group.first().sessionId }) { _, group ->
                            val top = group.first()
                            val sessionId = top.sessionId
                            val isExpanded = expandedSessions[sessionId] ?: false

                            SessionResultGroup(
                                group = group,
                                query = query,
                                isExpanded = isExpanded,
                                onHeaderClick = {
                                    expandedSessions[sessionId] = !isExpanded
                                },
                                onChunkClick = { result ->
                                    onResultClick(result.sessionId, result.messageId)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionResultGroup(
    group: List<ChatSessionListViewModel.SearchResult>,
    query: String,
    isExpanded: Boolean,
    onHeaderClick: () -> Unit,
    onChunkClick: (ChatSessionListViewModel.SearchResult) -> Unit
) {
    val top = group.first()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // セッション名ヘッダーカード
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colorResource(id = R.color.surface_card),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onHeaderClick)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = top.sessionName,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorResource(id = R.color.primary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (group.size > 1) {
                    Text(
                        text = if (isExpanded) stringResource(id = R.string.history_search_collapse) else stringResource(id = R.string.history_search_expand_count, group.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = colorResource(id = R.color.text_secondary),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // topチャンクカード（常に表示）
        ChunkCard(result = top, query = query, onClick = { onChunkClick(top) })

        // 展開時に残りチャンク
        if (group.size > 1) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    group.drop(1).forEach { result ->
                        ChunkCard(result = result, query = query, onClick = { onChunkClick(result) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkCard(
    result: ChatSessionListViewModel.SearchResult,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colorResource(id = R.color.surface_card),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ChunkText(result = result, query = query)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(id = R.string.history_search_score_format, "%.2f".format(result.score)),
                style = MaterialTheme.typography.bodySmall,
                color = colorResource(id = R.color.text_secondary)
            )
        }
    }
}

@Composable
private fun ChunkText(
    result: ChatSessionListViewModel.SearchResult,
    query: String
) {
    val annotated = buildAnnotatedString {
        val text = result.chunkText
        val lower = text.lowercase()
        val queryLower = query.lowercase()
        val hitIdx = lower.indexOf(queryLower)

        val windowStart = if (hitIdx >= 0) maxOf(0, hitIdx - 40) else 0
        val windowEnd = if (hitIdx >= 0) minOf(text.length, hitIdx + query.length + 60) else minOf(text.length, 100)
        val windowed = text.substring(windowStart, windowEnd)
        val prefix = if (windowStart > 0) "…" else ""
        val suffix = if (windowEnd < text.length) "…" else ""

        append(prefix)
        val winLower = windowed.lowercase()
        var cursor = 0
        var idx = winLower.indexOf(queryLower)
        while (idx >= 0) {
            append(windowed.substring(cursor, idx))
            withStyle(SpanStyle(
                background = colorResource(id = R.color.primary).copy(alpha = 0.25f),
                fontWeight = FontWeight.Bold
            )) {
                append(windowed.substring(idx, idx + query.length))
            }
            cursor = idx + query.length
            idx = winLower.indexOf(queryLower, cursor)
        }
        append(windowed.substring(cursor))
        append(suffix)
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.bodySmall,
        color = colorResource(id = R.color.text_primary),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis
    )
}
