package com.nezumi_ai.desktop.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.nezumi_ai.desktop.data.DesktopSettingsEnvelope
import com.nezumi_ai.desktop.data.DesktopSettingsStore
import com.nezumi_ai.desktop.viewmodel.SettingsViewModel
import com.nezumi_ai.shared.settings.NezumiSettingsFormState
import com.nezumi_ai.shared.ui.screen.NezumiUnifiedSettingsScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun SettingsScreen() {
    val viewModel = remember { SettingsViewModel.getInstance() }
    val modelPath by viewModel.modelPath.collectAsState()
    val backend by viewModel.backend.collectAsState()

    val maxThreadsRuntime = remember { DesktopSettingsStore.desktopMaxThreads() }
    var form by remember {
        mutableStateOf(
            DesktopSettingsStore.load()?.let { DesktopSettingsStore.normalizeFormForRuntime(it.form) }
                ?: NezumiSettingsFormState.default(maxThreadsRuntime),
        )
    }

    LaunchedEffect(backend) {
        val chip = when {
            backend.contains("CUDA", ignoreCase = true) ||
                backend.contains("Metal", ignoreCase = true) ||
                backend.contains("GPU", ignoreCase = true) -> "GPU"
            else -> "CPU"
        }
        if (form.backendType != chip) {
            form = form.copy(backendType = chip)
        }
    }

    LaunchedEffect(Unit) {
        snapshotFlow { Triple(form, modelPath, backend) }
            .distinctUntilChanged()
            .debounce(700L)
            .collectLatest { (f, p, b) ->
                DesktopSettingsStore.save(
                    DesktopSettingsEnvelope(
                        form = DesktopSettingsStore.normalizeFormForRuntime(f),
                        lastModelPath = p,
                        backendLabel = b,
                    ),
                )
            }
    }

    NezumiUnifiedSettingsScreen(
        state = form,
        onStateChange = { new ->
            val prevBackend = form.backendType
            form = new
            if (new.backendType != prevBackend) {
                when (new.backendType) {
                    "GPU" -> viewModel.updateBackend("GPU (CUDA)")
                    "NPU" -> viewModel.updateBackend("CPU")
                    else -> viewModel.updateBackend("CPU")
                }
            }
        },
        showTopAppBar = false,
        showAndroidStyleFooter = false,
        engineVersionInfo = null,
    )
}
