package com.nezumi_ai.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DismissibleNavigationDrawer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 左ドロワー＋単一トップバー＋本文のメインシェル（Android / デスクトップ共通 Compose）。
 * プラットフォーム固有の ViewModel 連携は呼び出し側の [onNewChat] や子コンテンツに委譲する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NezumiDrawerMainShell(
    chatContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modelManagementContent: @Composable () -> Unit,
    mcpContent: @Composable () -> Unit,
    onNewChat: () -> Unit,
    historySubtitle: String = "デスクトップ版は単一チャットです。Android 版の履歴一覧は今後接続できます。",
    secretChatEnabled: Boolean = false,
    imageGenEnabled: Boolean = false,
    onSecretChat: () -> Unit = {},
    onImageGen: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(MainDestination.Chat) }

    DismissibleNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(280.dp),
            ) {
                NezumiMainDrawerPanel(
                    onOpenSettings = {
                        destination = MainDestination.Settings
                        scope.launch { drawerState.close() }
                    },
                    onOpenModelManagement = {
                        destination = MainDestination.ModelManagement
                        scope.launch { drawerState.close() }
                    },
                    onOpenMcp = {
                        destination = MainDestination.Mcp
                        scope.launch { drawerState.close() }
                    },
                    onNewChat = {
                        onNewChat()
                        destination = MainDestination.Chat
                        scope.launch { drawerState.close() }
                    },
                    secretChatEnabled = secretChatEnabled,
                    imageGenEnabled = imageGenEnabled,
                    onSecretChat = onSecretChat,
                    onImageGen = onImageGen,
                    historySubtitle = historySubtitle,
                )
            }
        },
        gesturesEnabled = true,
        content = {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = {
                        Text(
                            when (destination) {
                                MainDestination.Chat -> "ネズミAI"
                                MainDestination.Settings -> "設定"
                                MainDestination.ModelManagement -> "モデル管理"
                                MainDestination.Mcp -> "MCP"
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "メニュー")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    when (destination) {
                        MainDestination.Chat -> chatContent()
                        MainDestination.Settings -> settingsContent()
                        MainDestination.ModelManagement -> modelManagementContent()
                        MainDestination.Mcp -> mcpContent()
                    }
                }
            }
        },
    )
}
