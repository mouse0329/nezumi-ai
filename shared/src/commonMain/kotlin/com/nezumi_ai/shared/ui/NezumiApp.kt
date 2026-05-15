package com.nezumi_ai.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nezumi_ai.shared.ui.theme.NezumiTheme

enum class NezumiMainChrome {
    /** 左ナビレール（幅の狭いウィンドウ向け） */
    NavigationRail,

    /** 左ドロワー（Android MainActivity 相当） */
    NavigationDrawer,
}

@Composable
fun NezumiApp(
    darkTheme: Boolean = true,
    mainChrome: NezumiMainChrome = NezumiMainChrome.NavigationRail,
    /** [NezumiMainChrome.NavigationDrawer] のとき「新しいチャット」で呼ぶ（例: 履歴クリア） */
    onNewChat: () -> Unit = {},
    historySubtitle: String = "デスクトップ版は単一チャットです。Android 版の履歴一覧は今後接続できます。",
    secretChatEnabled: Boolean = false,
    imageGenEnabled: Boolean = false,
    onSecretChat: () -> Unit = {},
    onImageGen: () -> Unit = {},
    chatContent: @Composable () -> Unit,
    settingsContent: @Composable () -> Unit,
    modelManagementContent: @Composable () -> Unit,
    mcpContent: @Composable () -> Unit,
) {
    NezumiTheme(darkTheme = darkTheme) {
        when (mainChrome) {
            NezumiMainChrome.NavigationRail -> {
                var currentScreen by remember { mutableStateOf(MainDestination.Chat) }
                NezumiMainScaffold(
                    destination = currentScreen,
                    onDestinationChange = { currentScreen = it },
                    chatContent = chatContent,
                    settingsContent = settingsContent,
                    modelManagementContent = modelManagementContent,
                    mcpContent = mcpContent,
                )
            }
            NezumiMainChrome.NavigationDrawer -> {
                NezumiDrawerMainShell(
                    chatContent = chatContent,
                    settingsContent = settingsContent,
                    modelManagementContent = modelManagementContent,
                    mcpContent = mcpContent,
                    onNewChat = onNewChat,
                    historySubtitle = historySubtitle,
                    secretChatEnabled = secretChatEnabled,
                    imageGenEnabled = imageGenEnabled,
                    onSecretChat = onSecretChat,
                    onImageGen = onImageGen,
                )
            }
        }
    }
}
