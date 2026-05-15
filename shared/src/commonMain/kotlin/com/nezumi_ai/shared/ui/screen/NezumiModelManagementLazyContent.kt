package com.nezumi_ai.shared.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nezumi_ai.shared.ui.desktopmobile.NezumiDesktopMobileModelManagement

/**
 * ルートが [LazyColumn] のときに使う llama.cpp モデル管理（1 アイテム内でネストスクロールしない）。
 */
@Composable
fun NezumiModelManagementLazyContent(
    libraryDownloaded: Boolean,
    onDownloadLibrary: () -> Unit,
    availableModels: List<ModelInfo>,
    onDownloadModel: (String) -> Unit,
    downloadProgress: DownloadProgress?,
    useGpu: Boolean,
    onGpuToggle: (Boolean) -> Unit,
    downloadedModels: List<String>,
    onLoadModel: (String) -> Unit,
    currentModel: String?,
    statusMessage: String,
    onRefreshModels: () -> Unit,
    showTitle: Boolean = true,
    showLibrarySection: Boolean = true,
    showGpuCard: Boolean = true,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            NezumiDesktopMobileModelManagement(
                libraryDownloaded = libraryDownloaded,
                onDownloadLibrary = onDownloadLibrary,
                availableModels = availableModels,
                onDownloadModel = onDownloadModel,
                downloadProgress = downloadProgress,
                useGpu = useGpu,
                onGpuToggle = onGpuToggle,
                downloadedModels = downloadedModels,
                onLoadModel = onLoadModel,
                currentModel = currentModel,
                statusMessage = statusMessage,
                onRefreshModels = onRefreshModels,
                showTitle = showTitle,
                showLibrarySection = showLibrarySection,
                showGpuCard = showGpuCard,
            )
        }
    }
}
