package com.nezumi_ai.desktop.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import com.nezumi_ai.desktop.data.DesktopSettingsEnvelope
import com.nezumi_ai.desktop.data.DesktopSettingsStore
import com.nezumi_ai.desktop.viewmodel.ChatViewModel
import com.nezumi_ai.desktop.viewmodel.SettingsViewModel
import com.nezumi_ai.shared.settings.NezumiSettingsFormState
import com.nezumi_ai.shared.ui.screen.DownloadProgress
import com.nezumi_ai.shared.ui.screen.ModelInfo
import com.nezumi_ai.shared.ui.screen.NezumiModelManagementLazyContent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ModelManagementScreen() {
    val viewModel = remember { SettingsViewModel.getInstance() }
    val chatViewModel = ChatViewModel.getInstance()
    val isLibraryAvailable by viewModel.isLibraryAvailable.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val modelPath by viewModel.modelPath.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val isModelLoaded by viewModel.isModelLoaded.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()

    val maxThreadsRuntime = remember { DesktopSettingsStore.desktopMaxThreads() }
    LaunchedEffect(Unit) {
        snapshotFlow { modelPath to backend }
            .distinctUntilChanged()
            .debounce(700L)
            .collectLatest { (path, b) ->
                val env = DesktopSettingsStore.load()
                val form = env?.let { DesktopSettingsStore.normalizeFormForRuntime(it.form) }
                    ?: NezumiSettingsFormState.default(maxThreadsRuntime)
                DesktopSettingsStore.save(
                    DesktopSettingsEnvelope(
                        form = DesktopSettingsStore.normalizeFormForRuntime(form),
                        lastModelPath = path,
                        backendLabel = b,
                    ),
                )
            }
    }

    NezumiModelManagementLazyContent(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        libraryDownloaded = isLibraryAvailable,
        onDownloadLibrary = { viewModel.downloadLlamaCpp() },
        availableModels = availableModels.map { model ->
            ModelInfo(
                name = model.displayName,
                size = model.size,
                downloaded = downloadedModels.any {
                    it.name.contains(model.displayName.split(" ").first())
                },
            )
        },
        onDownloadModel = { modelName ->
            availableModels.find { it.displayName == modelName }?.let {
                viewModel.downloadModel(it)
            }
        },
        downloadProgress = if (isDownloading && downloadProgress > 0) {
            DownloadProgress(
                modelName = "Model",
                progress = downloadProgress / 100f,
            )
        } else {
            null
        },
        useGpu = backend != "CPU",
        onGpuToggle = { useGpu ->
            viewModel.updateBackend(if (useGpu) "GPU (CUDA)" else "CPU")
        },
        downloadedModels = downloadedModels.map { it.absolutePath },
        onLoadModel = { path ->
            viewModel.updateModelPath(path)
            viewModel.loadModel()
            chatViewModel.setSelectedModel(path)
        },
        currentModel = if (isModelLoaded) modelPath.takeIf { it.isNotEmpty() } else null,
        statusMessage = statusMessage,
        onRefreshModels = { viewModel.refreshDownloadedModels() },
        showTitle = true,
        showLibrarySection = true,
        showGpuCard = true,
    )
}
