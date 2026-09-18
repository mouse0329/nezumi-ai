package com.nezumi_ai.data.inference

import android.content.Context
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.utils.GgufMetadataReader
import java.io.File

/**
 * GGUF の手組みフォールバック形式と thinking スタイルを、Android 固有の情報
 * (ユーザー選択テンプレート / GGUF メタデータのアーキテクチャ) を織り込んで解決する
 * app 側ブリッジ (旧 `PromptBuilder.detectGgufFormat` / `resolveThinkingPromptStyle` の移行先)。
 *
 * 純粋なモデル名判定は shared の [ModelNameHeuristics] に集約し、
 * ファイル I/O (GGUF アーキテクチャ読み取り) と Context 依存 (PromptTemplateStore) は
 * ここで吸収してヒントとして渡す。
 */
object GgufFormatResolver {

    /**
     * ユーザーが MODE_AUTO 以外のテンプレートを明示選択しているか。
     * (旧 `PromptBuilder.hasExplicitUserTemplate` と同一。Bug fix(#42) 参照)
     */
    fun hasExplicitUserTemplate(appContext: Context?, modelPath: String): Boolean {
        if (appContext == null || modelPath.isBlank()) return false
        return runCatching {
            val sel = PromptTemplateStore.getSelection(appContext, modelPath)
            sel.mode != PromptTemplateStore.MODE_AUTO
        }.getOrDefault(false)
    }

    /** GGUF メタデータの architecture が gpt2 か (旧 `PromptBuilder.isGpt2Architecture` と同一)。 */
    private fun isGpt2Architecture(modelPath: String): Boolean {
        val lowered = modelPath.lowercase()
        if (!lowered.endsWith(".gguf")) return false
        val file = File(modelPath)
        if (!file.isFile) return false
        return runCatching {
            GgufMetadataReader.readSummary(file).architecture.lowercase()
        }.getOrNull() == "gpt2"
    }

    /**
     * GPT-2 系 (プレーン completion) モデルかどうかを判定する。
     *
     * 旧 `resolveGgufFormat` はモデル名推定による手組みフォールバック形式 (Gemma/ChatML/Llama3)
     * も返していたが、プロンプト生成のネイティブ (minja) 一本化に伴い推定フォールバックは
     * 廃止された。現状の実用上の意味は「GPT-2 かどうか」(保守的ネイティブ生成設定の適用判定)
     * だけなので、残存呼び出し側に合わせてブール判定へ簡素化する。
     */
    fun isPlainCompletionModel(modelPath: String, appContext: Context?): Boolean {
        if (hasExplicitUserTemplate(appContext, modelPath)) return false
        return isGpt2Architecture(modelPath) ||
            ModelNameHeuristics.isGpt2ModelName(
                ModelNameHeuristics.resolveModelNameForCheck(modelPath).lowercase()
            )
    }

    /**
     * thinking 制御スタイルを解決する (旧 `PromptBuilder.resolveThinkingPromptStyle` の移行先)。
     */
    fun resolveThinkingStyle(
        modelPath: String,
        appContext: Context?,
    ): ModelNameHeuristics.ThinkingPromptStyle {
        val userOverride = hasExplicitUserTemplate(appContext, modelPath)
        return ModelNameHeuristics.resolveThinkingPromptStyle(
            modelPathOrName = modelPath,
            hasExplicitUserTemplate = userOverride,
            isGpt2ArchitectureHint = isGpt2Architecture(modelPath),
        )
    }
}
