package com.nezumi_ai.shared.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun McpStatusScreen() {
    Box(modifier = Modifier.fillMaxSize().padding(PaddingValues(16.dp))) {
        Column {
            Text("MCP", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "http://localhost:3000 でサーバーを起動する想定です。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("利用可能なツール（例）", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("• generate_text — LLM推論", style = MaterialTheme.typography.bodyMedium)
            Text("• get_context — チャット履歴取得", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
