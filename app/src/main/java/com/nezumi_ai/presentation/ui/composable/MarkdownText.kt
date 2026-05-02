package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily

@Composable
fun MarkdownText(
    content: String,
    modifier: Modifier = Modifier
) {
    // Simple text display without full markdown parsing
    // For now, just display the content as-is
    // A full implementation would use a markdown parsing library
    Text(
        text = content,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.onBackground,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}
