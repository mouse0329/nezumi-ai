package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class SharedMessageRole {
    USER,
    ASSISTANT
}

@Composable
fun ChatMessageBubble(
    text: String,
    role: SharedMessageRole,
    modifier: Modifier = Modifier,
    userLabel: String = "あなた",
    assistantLabel: String = "ネズミAI",
    isStreaming: Boolean = false,
    userColor: Color = MaterialTheme.colorScheme.primaryContainer,
    assistantColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val bubbleColor = if (role == SharedMessageRole.USER) userColor else assistantColor
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = if (role == SharedMessageRole.USER) userLabel else assistantLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (isStreaming) {
            Text(
                text = "生成中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Text(
            text = text,
            modifier = Modifier
                .padding(top = 4.dp)
                .background(bubbleColor, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
