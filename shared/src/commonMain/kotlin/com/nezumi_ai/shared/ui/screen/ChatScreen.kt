package com.nezumi_ai.shared.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.shared.ui.components.ChatInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onSendMessage: (String) -> Unit,
    showTopAppBar: Boolean = true,
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (showTopAppBar) {
            TopAppBar(
                title = { Text("ネズミAI") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        }

        NezumiChatMessageList(
            messages = messages,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            lazyListState = listState,
        )

        ChatInput(
            value = inputText,
            onValueChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            enabled = !isGenerating,
        )
    }
}
