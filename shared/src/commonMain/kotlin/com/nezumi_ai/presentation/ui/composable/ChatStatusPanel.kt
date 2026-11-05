package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatStatusPanel(
    thinkingText: String,
    activeToolNames: List<String>,
    generationTps: Float?,
    error: String?,
    modifier: Modifier = Modifier,
    thinkingLabel: String = "Thinking",
    toolsLabel: String = "Tools",
    speedLabel: String = "速度",
    errorLabel: String = "エラー"
) {
    if (
        thinkingText.isBlank() &&
        activeToolNames.isEmpty() &&
        generationTps == null &&
        error.isNullOrBlank()
    ) {
        return
    }

    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (thinkingText.isNotBlank()) {
            Text(
                text = "$thinkingLabel: $thinkingText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (activeToolNames.isNotEmpty()) {
            Text(
                text = "$toolsLabel: ${activeToolNames.joinToString()}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        generationTps?.let {
            Text(
                text = "$speedLabel: %.1f tok/s".format(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!error.isNullOrBlank()) {
            Text(
                text = "$errorLabel: $error",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
