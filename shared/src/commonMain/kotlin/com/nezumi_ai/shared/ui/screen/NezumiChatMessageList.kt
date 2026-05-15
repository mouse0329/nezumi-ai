package com.nezumi_ai.shared.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.shared.ui.components.MessageBubble
import kotlinx.coroutines.delay

/**
 * チャット本文リスト（デスクトップ / Android 共通）。
 * [ChatScreen] はここに入力欄と任意の TopAppBar を足した構成。
 */
@Composable
fun NezumiChatMessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
) {
    LaunchedEffect(messages.size, messages.lastOrNull()?.id) {
        if (messages.isEmpty()) return@LaunchedEffect
        delay(32)
        val count = lazyListState.layoutInfo.totalItemsCount
        if (count <= 0) return@LaunchedEffect
        val target = (count - 1).coerceAtLeast(0)
        runCatching { lazyListState.scrollToItem(target) }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
        state = lazyListState,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
    ) {
        items(
            items = messages,
            key = { it.id },
        ) { message ->
            MessageBubble(message)
        }
    }
}
