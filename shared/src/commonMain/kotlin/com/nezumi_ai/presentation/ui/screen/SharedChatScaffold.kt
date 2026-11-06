package com.nezumi_ai.presentation.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SharedChatScaffold(
    header: @Composable () -> Unit,
    contextMeter: @Composable () -> Unit,
    messageContent: @Composable () -> Unit,
    messageOverlays: @Composable () -> Unit,
    mediaPreview: @Composable () -> Unit,
    inputBar: @Composable () -> Unit,
    loadingOverlay: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            header()
            contextMeter()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                messageContent()
                messageOverlays()
            }
            mediaPreview()
            inputBar()
        }
        loadingOverlay()
    }
}
