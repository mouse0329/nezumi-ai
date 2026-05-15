package com.nezumi_ai.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * メイン画面左ドロワーの中身（デスクトップのドロワーシェル / Android のドロワー Compose 化で再利用）。
 */
@Composable
fun NezumiMainDrawerPanel(
    onOpenSettings: () -> Unit,
    onOpenModelManagement: () -> Unit,
    onOpenMcp: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
        .fillMaxHeight()
        .padding(horizontal = 16.dp, vertical = 20.dp),
    secretChatEnabled: Boolean = false,
    imageGenEnabled: Boolean = false,
    onSecretChat: () -> Unit = {},
    onImageGen: () -> Unit = {},
    historySubtitle: String = "デスクトップ版は単一チャットです。Android 版の履歴一覧は今後接続できます。",
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "ネズミAI",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) { Text("設定", maxLines = 1) }
            OutlinedButton(
                onClick = onOpenModelManagement,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) { Text("モデル", maxLines = 1) }
            OutlinedButton(
                onClick = onOpenMcp,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) { Text("ツール", maxLines = 1) }
        }
        Button(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("新しいチャット") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = onSecretChat,
                enabled = secretChatEnabled,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) { Text("シークレット", maxLines = 1) }
            OutlinedButton(
                onClick = onImageGen,
                enabled = imageGenEnabled,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            ) { Text("画像生成", maxLines = 1) }
        }
        Text(
            text = "チャット履歴",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Text(
            text = historySubtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
