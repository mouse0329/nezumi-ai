package com.nezumi_ai.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nezumi_ai.presentation.ui.model.SharedChatState
import com.nezumi_ai.presentation.ui.model.SharedChatEvent
import com.nezumi_ai.presentation.ui.model.reduce
import com.nezumi_ai.presentation.ui.screen.SharedChatScreen

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "ネズミAI Desktop"
    ) {
        DesktopShell()
    }
}

@Composable
private fun DesktopShell() {
    var chatState by remember { mutableStateOf(SharedChatState.initial()) }
    val logoPainter = remember { ColorPainter(Color(0xFF6D4C41)) }
    val menuPainter = remember { ColorPainter(Color(0xFF6D4C41)) }
    MaterialTheme {
        SharedChatScreen(
            title = "新しいセッション",
            modelName = "Desktop / shared UI",
            messages = chatState.messages,
            inputText = chatState.draft,
            onInputTextChange = {
                chatState = chatState.reduce(SharedChatEvent.DraftChanged(it))
            },
            onSendClick = {
                chatState = chatState.reduce(SharedChatEvent.SendRequested)
            },
            sendEnabled = chatState.draft.isNotBlank(),
            thinkingText = chatState.thinkingText,
            activeToolNames = chatState.activeToolNames,
            generationTps = chatState.generationTps,
            error = chatState.error,
            menuIcon = menuPainter,
            logo = logoPainter,
            onBackClick = {},
            onTitleClick = {}
        )
    }
}
