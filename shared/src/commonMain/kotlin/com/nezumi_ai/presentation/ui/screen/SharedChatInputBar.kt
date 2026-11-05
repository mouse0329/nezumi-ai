package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp

@Composable
fun SharedChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    sendEnabled: Boolean,
    hint: String,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.surface,
    sendColor: Color = MaterialTheme.colorScheme.primary,
    sendIcon: Painter? = null,
    sendContentDescription: String? = null,
    fallbackSendLabel: String = "↑",
    leadingIcon: Painter? = null,
    leadingContentDescription: String? = null,
    onLeadingClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(barColor)
            .padding(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
                .padding(start = 10.dp, end = 4.dp)
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(hint) },
                maxLines = 6,
                singleLine = false
            )
        }
        if (leadingIcon != null && onLeadingClick != null) {
            IconButton(
                onClick = onLeadingClick,
                modifier = Modifier.requiredSize(40.dp)
            ) {
                Icon(
                    painter = leadingIcon,
                    contentDescription = leadingContentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(
            onClick = onSendClick,
            enabled = sendEnabled,
            modifier = Modifier.requiredSize(40.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        if (sendEnabled) sendColor else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (sendIcon != null) {
                    Icon(
                        painter = sendIcon,
                        contentDescription = sendContentDescription,
                        tint = if (sendEnabled) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = fallbackSendLabel,
                        color = if (sendEnabled) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
