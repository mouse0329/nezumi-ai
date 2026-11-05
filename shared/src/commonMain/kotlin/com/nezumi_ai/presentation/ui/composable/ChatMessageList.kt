package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class SharedChatMessage(
    val id: String,
    val role: SharedMessageRole,
    val text: String,
    val timestamp: Long? = null,
    val isStreaming: Boolean = false
)

@Composable
fun ChatMessageList(
    messages: List<SharedChatMessage>,
    modifier: Modifier = Modifier,
    userLabel: String = "あなた",
    assistantLabel: String = "ネズミAI"
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            ChatMessageBubble(
                text = message.text,
                role = message.role,
                userLabel = userLabel,
                assistantLabel = assistantLabel,
                isStreaming = message.isStreaming
            )
        }
    }
}
