package com.nezumi_ai.presentation.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nezumi_ai.presentation.ui.model.SharedHistorySearchResult
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel

@Composable
fun HistorySearchModal(
    viewModel: ChatSessionListViewModel,
    onResultClick: (sessionId: Long, messageId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    SharedHistorySearchModal(
        query = query,
        results = results.map(ChatSessionListViewModel.SearchResult::toShared),
        isSearching = isSearching,
        onQueryChange = viewModel::onSearchQueryChange,
        onResultClick = onResultClick,
        onDismiss = onDismiss
    )
}

private fun ChatSessionListViewModel.SearchResult.toShared() =
    SharedHistorySearchResult(
        sessionId = sessionId,
        sessionName = sessionName,
        messageId = messageId,
        chunkId = chunkId,
        chunkText = chunkText,
        score = score
    )
