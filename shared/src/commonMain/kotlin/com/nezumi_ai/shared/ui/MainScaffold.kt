package com.nezumi_ai.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

enum class MainDestination {
    Chat,
    Settings,
    ModelManagement,
    Mcp,
}

@Composable
fun NezumiMainScaffold(
    destination: MainDestination,
    onDestinationChange: (MainDestination) -> Unit,
    chatContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modelManagementContent: @Composable () -> Unit,
    mcpContent: @Composable () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            NavigationRailItem(
                icon = { Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = "チャット") },
                label = { Text("チャット") },
                selected = destination == MainDestination.Chat,
                onClick = { onDestinationChange(MainDestination.Chat) },
            )
            NavigationRailItem(
                icon = { Icon(Icons.Outlined.Settings, contentDescription = "設定") },
                label = { Text("設定") },
                selected = destination == MainDestination.Settings,
                onClick = { onDestinationChange(MainDestination.Settings) },
            )
            NavigationRailItem(
                icon = { Icon(Icons.Outlined.CloudDownload, contentDescription = "モデル") },
                label = { Text("モデル") },
                selected = destination == MainDestination.ModelManagement,
                onClick = { onDestinationChange(MainDestination.ModelManagement) },
            )
            NavigationRailItem(
                icon = { Icon(Icons.Outlined.Hub, contentDescription = "MCP") },
                label = { Text("MCP") },
                selected = destination == MainDestination.Mcp,
                onClick = { onDestinationChange(MainDestination.Mcp) },
            )
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (destination) {
                    MainDestination.Chat -> chatContent()
                    MainDestination.Settings -> settingsContent()
                    MainDestination.ModelManagement -> modelManagementContent()
                    MainDestination.Mcp -> mcpContent()
                }
            }
        }
    }
}
