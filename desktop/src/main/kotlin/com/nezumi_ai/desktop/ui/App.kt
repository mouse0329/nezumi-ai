package com.nezumi_ai.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nezumi_ai.desktop.ui.screen.ChatScreen
import com.nezumi_ai.desktop.ui.screen.ModelManagementScreen
import com.nezumi_ai.desktop.ui.screen.SettingsScreen
import com.nezumi_ai.desktop.viewmodel.ChatViewModel
import com.nezumi_ai.desktop.viewmodel.SettingsViewModel
import com.nezumi_ai.shared.ui.NezumiApp
import com.nezumi_ai.shared.ui.NezumiMainChrome
import com.nezumi_ai.shared.ui.screen.McpStatusScreen

@Composable
fun App() {
    val chatVm = remember { ChatViewModel.getInstance() }
    val settingsVm = remember { SettingsViewModel.getInstance() }
    LaunchedEffect(Unit) {
        settingsVm.modelPath.collect { path ->
            if (path.isNotBlank()) {
                chatVm.setSelectedModel(path)
            }
        }
    }
    NezumiApp(
        mainChrome = NezumiMainChrome.NavigationDrawer,
        onNewChat = { chatVm.clearMessages() },
        chatContent = { ChatScreen() },
        settingsContent = { SettingsScreen() },
        modelManagementContent = { ModelManagementScreen() },
        mcpContent = { McpStatusScreen() },
    )
}
