package com.nezumi_ai.shared.ui.desktopmobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nezumi_ai.shared.ui.screen.DownloadProgress
import com.nezumi_ai.shared.ui.screen.ModelInfo

/**
 * デスクトップ / Android 共通の **llama.cpp モデル管理** UI（1 ファイルに集約）。
 *
 * - デスクトップ: [com.nezumi_ai.shared.ui.screen.NezumiModelManagementLazyContent] または設定ヘッダから呼ぶ。
 * - Android: アプリのモデル設定画面の LLM タブなどで [showLibrarySection]=false で組み込み可能。
 *
 * 親が [androidx.compose.foundation.lazy.LazyColumn] の item 内のときは [verticalScroll] を付けないこと。
 */
@Composable
fun NezumiDesktopMobileModelManagement(
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
    showTitle: Boolean = false,
    showLibrarySection: Boolean = true,
    showGpuCard: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showTitle) {
            Text(
                text = "モデル管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        if (showLibrarySection) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("llama.cpp ライブラリ", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (libraryDownloaded) "✓ インストール済み" else "未インストール",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!libraryDownloaded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = onDownloadLibrary) {
                            Text("ダウンロード")
                        }
                    }
                }
            }
        }
        if (showGpuCard) {
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("GPU使用", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = useGpu, onCheckedChange = onGpuToggle)
                }
            }
        }
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("モデルダウンロード", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        for (model in availableModels) {
            Card {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyLarge)
                        Text(model.size, style = MaterialTheme.typography.bodySmall)
                    }
                    if (model.downloaded) {
                        Text("✓", style = MaterialTheme.typography.titleLarge)
                    } else {
                        Button(onClick = { onDownloadModel(model.name) }) {
                            Text("DL")
                        }
                    }
                }
            }
        }
        downloadProgress?.let { progress ->
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${progress.modelName} ダウンロード中...")
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(progress.progress * 100).toInt()}%")
                }
            }
        }
        if (downloadedModels.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("ダウンロード済みモデル", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = onRefreshModels) {
                            Text("更新")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (statusMessage.isNotEmpty()) {
                        Text(
                            statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                statusMessage.startsWith("✓") -> MaterialTheme.colorScheme.primary
                                statusMessage.startsWith("⚠") || statusMessage.startsWith("Error") ->
                                    MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
            for (modelPath in downloadedModels) {
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                modelPath.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (modelPath == currentModel) {
                                Text("(使用中)", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (modelPath != currentModel) {
                            Button(onClick = { onLoadModel(modelPath) }) {
                                Text("読込")
                            }
                        }
                    }
                }
            }
        }
    }
}
