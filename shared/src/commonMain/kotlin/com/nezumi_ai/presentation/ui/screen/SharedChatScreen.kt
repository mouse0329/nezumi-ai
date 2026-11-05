package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import com.nezumi_ai.presentation.ui.composable.ChatMessageList
import com.nezumi_ai.presentation.ui.composable.ChatStatusPanel
import com.nezumi_ai.presentation.ui.composable.SharedChatMessage

@Composable
fun SharedChatScreen(
    title: String,
    modelName: String,
    messages: List<SharedChatMessage>,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    sendEnabled: Boolean,
    menuIcon: Painter,
    logo: Painter,
    onBackClick: () -> Unit,
    onTitleClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuContentDescription: String = "メニュー",
    logoContentDescription: String = "ネズミAI",
    inputHint: String = "メッセージを入力",
    userLabel: String = "あなた",
    assistantLabel: String = "ネズミAI",
    thinkingText: String = "",
    activeToolNames: List<String> = emptyList(),
    generationTps: Float? = null,
    error: String? = null,
    headerActions: @Composable () -> Unit = {}
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            SharedChatHeader(
                title = title,
                titleContentDescription = null,
                modelName = modelName,
                headerColor = MaterialTheme.colorScheme.surface,
                backButtonColor = MaterialTheme.colorScheme.primaryContainer,
                borderColor = MaterialTheme.colorScheme.outline,
                textColor = MaterialTheme.colorScheme.onSurface,
                secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                menuIcon = menuIcon,
                logo = logo,
                menuContentDescription = menuContentDescription,
                logoContentDescription = logoContentDescription,
                onBackClick = onBackClick,
                onTitleClick = onTitleClick,
                actions = headerActions
            )
            ChatMessageList(
                messages = messages,
                modifier = Modifier.weight(1f),
                userLabel = userLabel,
                assistantLabel = assistantLabel
            )
            ChatStatusPanel(
                thinkingText = thinkingText,
                activeToolNames = activeToolNames,
                generationTps = generationTps,
                error = error
            )
            SharedChatInputBar(
                text = inputText,
                onTextChange = onInputTextChange,
                onSendClick = onSendClick,
                sendEnabled = sendEnabled,
                hint = inputHint
            )
        }
    }
}
