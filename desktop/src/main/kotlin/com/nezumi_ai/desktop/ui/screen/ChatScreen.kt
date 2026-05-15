package com.nezumi_ai.desktop.ui.screen

import androidx.compose.runtime.*
import com.nezumi_ai.desktop.viewmodel.ChatViewModel
import com.nezumi_ai.shared.ui.screen.ChatScreen as SharedChatScreen

@Composable
fun ChatScreen() {
    val viewModel = ChatViewModel.getInstance() // シングルトンを使用
    val messages by viewModel.messages.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    
    SharedChatScreen(
        messages = messages,
        isGenerating = isGenerating,
        onSendMessage = { viewModel.sendMessage(it) },
        showTopAppBar = false,
    )
}
