package com.nezumi_ai.shared.ui.screen

/** デスクトップのモデル一覧行などで使用 */
data class ModelInfo(
    val name: String,
    val size: String,
    val downloaded: Boolean,
)

data class DownloadProgress(
    val modelName: String,
    val progress: Float,
)
