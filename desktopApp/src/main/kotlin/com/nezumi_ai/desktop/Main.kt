package com.nezumi_ai.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nezumi_ai.data.database.availableProcessorCount
import com.nezumi_ai.data.database.randomUuidString

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
    val instanceId = remember { randomUuidString() }
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ネズミAI Desktop")
            Text("KMP shared is connected")
            Text("CPU threads: ${availableProcessorCount()}")
            Text("Instance: $instanceId")
        }
    }
}
