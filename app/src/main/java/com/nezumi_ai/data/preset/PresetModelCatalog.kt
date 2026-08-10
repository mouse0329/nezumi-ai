package com.nezumi_ai.data.preset

import android.content.Context
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry

data class PresetModelOption(
    val id: String,
    val label: String
)

object PresetModelCatalog {
    fun downloadedModels(context: Context): List<PresetModelOption> {
        val options = mutableListOf<PresetModelOption>()
        if (ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA3N_2B)) {
            options += PresetModelOption("Gemma3n-2B", "Gemma 3n 2B")
        }
        if (ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA3N_4B)) {
            options += PresetModelOption("Gemma3n-4B", "Gemma 3n 4B")
        }
        if (ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA4_2B)) {
            options += PresetModelOption("Gemma4-2B", "Gemma 4 2B")
        }
        if (ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA4_4B)) {
            options += PresetModelOption("Gemma4-4B", "Gemma 4 4B")
        }
        ModelFileManager.listImportedTaskModels(context).forEach { imported ->
            val label = com.nezumi_ai.utils.ImportedModelCapabilityStore.resolveDisplayName(
                context, imported.path, imported.shortDisplayName
            )
            options += PresetModelOption(imported.path, label)
        }
        // ユーザーが追加したクラウドモデルをプリセット選択肢に流し込む。
        // モデル個別設定 (API キー / Base URL のモデル単位オーバーライド) も含めて
        // 「利用可能に構成済み」のものだけを出す。
        // (未設定のモデルを見せても選択した瞬間に失敗するだけなので、面倒でも
        //  設定ページを先に確認させる方針)
        CloudUserModelRegistry.list(context).forEach { modelId ->
            if (!CloudUserModelRegistry.isConfigured(context, modelId)) return@forEach
            options += PresetModelOption(modelId, CloudModelId.displayLabel(modelId))
        }
        return options
    }

    fun isDownloaded(context: Context, modelId: String): Boolean =
        downloadedModels(context).any { it.id == modelId }

    fun labelFor(context: Context, modelId: String): String =
        downloadedModels(context).firstOrNull { it.id == modelId }?.label
            ?: if (CloudModelId.isCloud(modelId)) CloudModelId.displayLabel(modelId) else modelId
}
