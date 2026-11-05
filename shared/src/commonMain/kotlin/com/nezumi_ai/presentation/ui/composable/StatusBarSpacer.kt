package com.nezumi_ai.presentation.ui.composable

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun StatusBarSpacer() {
    Spacer(modifier = Modifier.statusBarsPadding())
}
