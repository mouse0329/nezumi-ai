package com.nezumi_ai.presentation.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nezumi_ai.data.model.GroupedChatSessions
import com.nezumi_ai.presentation.ui.model.SharedSessionGroup
import com.nezumi_ai.presentation.ui.model.toSharedSessionSummaries
import com.nezumi_ai.presentation.viewmodel.ChatSessionListViewModel

/**
 * Android adapter for the shared session-list UI.
 *
 * Repository and lifecycle concerns stay in the Android ViewModel; rendering is
 * provided by commonMain so Desktop and Android use the same interaction model.
 */
@Composable
fun SessionListScreen(
    viewModel: ChatSessionListViewModel,
    onSessionClick: (Long) -> Unit,
    onCreateSession: () -> Unit,
    onCreateIncognitoSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onSearchClick: () -> Unit = {},
    currentSessionId: Long?,
) {
    val groupedSessions by viewModel.groupedSessions.collectAsState(emptyList())
    val isLoading by viewModel.isLoading.collectAsState()
    val groups = groupedSessions.toSharedSessionGroups()

    SharedSessionListScreen(
        groups = groups,
        currentSessionId = currentSessionId,
        onSessionClick = onSessionClick,
        isLoading = isLoading,
        onCreateSession = onCreateSession,
        onCreateIncognitoSession = onCreateIncognitoSession,
        onOpenSettings = onOpenSettings,
        onSearchClick = onSearchClick,
        onDeleteSession = { viewModel.deleteSession(it) },
        onTogglePin = { viewModel.togglePinSession(it) },
        onRenameSession = { id, name -> viewModel.renameSession(id, name) },
    )
}

private fun List<GroupedChatSessions>.toSharedSessionGroups(): List<SharedSessionGroup> =
    map { group ->
        SharedSessionGroup(
            label = group.dateLabel,
            sessions = group.sessions.toSharedSessionSummaries()
        )
    }
