package com.nezumi_ai.data.inference

import android.content.Context
import java.io.File

/**
 * モデルごとのカスタム / ビルトインプロンプトテンプレートを保存するストア。
 *
 * - ビルトインテンプレートは [BUILTIN_TEMPLATES] で定義（Llama 3 / Gemma / Qwen / ChatML / Alpaca）。
 * - ユーザーは各モデルに対して以下を選択できる:
 *     - "auto"  : 既存のヒューリスティック判定 (PromptBuilder.detectGgufFormat) に従う
 *     - "<builtin id>" : ビルトインテンプレート（例 "llama3", "gemma_chat"）
 *     - "custom": カスタムテンプレート文字列を保存して利用
 */
object PromptTemplateStore {

    private const val PREF_NAME = "model_prompt_templates"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun normalizeKey(modelPath: String): String {
        return try {
            File(modelPath).canonicalPath
        } catch (_: Exception) {
            modelPath
        }
    }

    private fun modeKey(path: String) = "${normalizeKey(path)}#mode"
    private fun customKey(path: String) = "${normalizeKey(path)}#custom"

    /** モデルテンプレート選択を取得。未設定なら "auto"。 */
    fun getSelection(context: Context, modelPath: String): TemplateSelection {
        val p = prefs(context)
        val mode = p.getString(modeKey(modelPath), MODE_AUTO) ?: MODE_AUTO
        val custom = p.getString(customKey(modelPath), null).orEmpty()
        return TemplateSelection(mode = mode, customTemplate = custom)
    }

    fun setSelection(context: Context, modelPath: String, selection: TemplateSelection) {
        prefs(context).edit()
            .putString(modeKey(modelPath), selection.mode)
            .putString(customKey(modelPath), selection.customTemplate)
            .apply()
    }

    fun clear(context: Context, modelPath: String) {
        prefs(context).edit()
            .remove(modeKey(modelPath))
            .remove(customKey(modelPath))
            .apply()
    }

    /** モデルパス移行用（リネーム時）。 */
    fun migrateModelPath(context: Context, oldPath: String, newPath: String) {
        val sel = getSelection(context, oldPath)
        clear(context, oldPath)
        setSelection(context, newPath, sel)
    }

    /**
     * モデルに対して実際に利用するテンプレート文字列を解決する。
     *
     * - mode == "auto" → null（呼び出し側でビルトイン形式判定を継続）
     * - mode == "custom" → カスタムテンプレ（空なら null フォールバック）
     * - それ以外 → ビルトインテンプレ id とみなして探索（無ければ null）
     */
    fun resolveTemplate(context: Context, modelPath: String): String? {
        val sel = getSelection(context, modelPath)
        return when (sel.mode) {
            MODE_AUTO -> null
            MODE_CUSTOM -> sel.customTemplate.ifBlank { null }
            else -> BUILTIN_TEMPLATES.firstOrNull { it.id == sel.mode }?.template
        }
    }

    const val MODE_AUTO = "auto"
    const val MODE_CUSTOM = "custom"

    data class TemplateSelection(
        val mode: String = MODE_AUTO,
        val customTemplate: String = ""
    )

    data class BuiltinTemplate(
        val id: String,
        val displayName: String,
        val description: String,
        val template: String
    )

    /**
     * ビルトインテンプレート定義。
     *
     * 注: 各テンプレートは末尾でアシスタント開始タグを残し、
     * モデルが応答本文から続けて生成できるようにする
     * （Ollama Modelfile の慣例に従う）。
     */
    val BUILTIN_TEMPLATES: List<BuiltinTemplate> = listOf(
        BuiltinTemplate(
            id = "llama3",
            displayName = "Llama 3 / Llama 3.1 / Llama 3.2",
            description = "<|start_header_id|> 系のヘッダーフォーマット",
            template = """{{ if .System }}<|start_header_id|>system<|end_header_id|>

{{ .System }}<|eot_id|>{{ end }}{{ range .History }}<|start_header_id|>{{ .Role }}<|end_header_id|>

{{ .Content }}<|eot_id|>{{ end }}<|start_header_id|>assistant<|end_header_id|>

"""
        ),
        BuiltinTemplate(
            id = "gemma_chat",
            displayName = "Gemma (chat)",
            description = "<start_of_turn> / <end_of_turn>",
            template = """{{ if .System }}<start_of_turn>user
{{ .System }}
<end_of_turn>
{{ end }}{{ range .History }}<start_of_turn>{{ .Role }}
{{ .Content }}<end_of_turn>
{{ end }}<start_of_turn>model
{{ if .Thinking }}<think>
{{ end }}"""
        ),
        BuiltinTemplate(
            id = "chatml",
            displayName = "ChatML (Qwen / Mistral / Bonsai)",
            description = "<|im_start|> / <|im_end|>",
            template = """{{ if .System }}<|im_start|>system
{{ .System }}<|im_end|>
{{ end }}{{ range .History }}<|im_start|>{{ .Role }}
{{ .Content }}<|im_end|>
{{ end }}<|im_start|>assistant
{{ if .Thinking }}<think>
{{ end }}"""
        ),
        BuiltinTemplate(
            id = "qwen_thinking",
            displayName = "Qwen (thinking / no_think)",
            description = "ChatML + 末尾に /no_think または <think> を制御",
            template = """{{ if .System }}<|im_start|>system
{{ .System }}<|im_end|>
{{ end }}{{ range .History }}<|im_start|>{{ .Role }}
{{ .Content }}<|im_end|>
{{ end }}<|im_start|>assistant
{{ if .Thinking }}<think>
{{ else }}<think>

</think>

{{ end }}"""
        ),
        BuiltinTemplate(
            id = "alpaca",
            displayName = "Alpaca / Instruct",
            description = "### Instruction / ### Response 形式",
            template = """{{ if .System }}{{ .System }}

{{ end }}{{ range .History }}{{ if .Content }}### {{ .Role }}:
{{ .Content }}

{{ end }}{{ end }}### Response:
"""
        ),
        BuiltinTemplate(
            id = "phi3",
            displayName = "Phi-3 / Phi-3.5",
            description = "<|user|> / <|assistant|> / <|end|>",
            template = """{{ if .System }}<|system|>
{{ .System }}<|end|>
{{ end }}{{ range .History }}<|{{ .Role }}|>
{{ .Content }}<|end|>
{{ end }}<|assistant|>
"""
        )
    )
}
