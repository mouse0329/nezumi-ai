package com.nezumi_ai.data.preset

import android.content.Context
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.cloud.CloudModelId
import com.nezumi_ai.data.inference.cloud.CloudUserModelRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicReference

data class PresetModelOption(
    val id: String,
    val label: String
)

object PresetModelCatalog {
    /**
     * ダウンロード済み / インポート済み / 設定済みクラウドモデル一覧のプロセス内キャッシュ。
     * listFiles + ファイルヘッダ検証 + SharedPreferences 読み出しはメインスレッドで
     * 繰り返すとプリセット画面・チャット画面の開閉が著しく重くなるため、結果を保持する。
     * モデルの追加・削除・ダウンロード完了時に [invalidateCache] を呼ぶこと。
     */
    private val cachedOptions = AtomicReference<List<PresetModelOption>?>(null)

    /** キャッシュを破棄する。モデルファイルやクラウド設定が変わった直後に呼ぶ。 */
    fun invalidateCache() {
        cachedOptions.set(null)
        ModelFileManager.invalidateImportedListCache()
    }

    fun downloadedModels(context: Context): List<PresetModelOption> {
        cachedOptions.get()?.let { return it }
        val options = buildDownloadedModels(context)
        // 他スレッドが先に入れた値があればそちらを優先（無駄な再計算は許容）
        cachedOptions.compareAndSet(null, options)
        return cachedOptions.get() ?: options
    }

    private fun buildDownloadedModels(context: Context): List<PresetModelOption> {
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

    /**
     * 個別モデルの利用可否判定。
     * 以前は [downloadedModels] を毎回組み立てて any していたため、
     * Flow の map や getCurrentPreset のたびに listFiles + 検証が走り、
     * プリセット画面・チャット画面の表示がメインスレッドを長時間ブロックしていた。
     * ここではモデル種別ごとに最小限の I/O だけを行う。
     */
    fun isDownloaded(context: Context, modelId: String): Boolean {
        return when (modelId) {
            "Gemma3n-2B" -> ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA3N_2B)
            "Gemma3n-4B" -> ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA3N_4B)
            "Gemma4-2B" -> ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA4_2B)
            "Gemma4-4B" -> ModelFileManager.isDownloaded(context, ModelFileManager.LocalModel.GEMMA4_4B)
            else -> {
                if (CloudModelId.isCloud(modelId)) {
                    CloudUserModelRegistry.isConfigured(context, modelId)
                } else {
                    // インポート済みモデル: modelId はファイルの絶対パス
                    val file = File(modelId)
                    file.isFile && file.length() > 0L &&
                        ModelFileManager.validateImportedTaskFile(file).isSuccess
                }
            }
        }
    }

    fun labelFor(context: Context, modelId: String): String =
        downloadedModels(context).firstOrNull { it.id == modelId }?.label
            ?: if (CloudModelId.isCloud(modelId)) CloudModelId.displayLabel(modelId) else modelId
}
