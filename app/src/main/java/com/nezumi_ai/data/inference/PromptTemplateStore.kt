package com.nezumi_ai.data.inference

import android.content.Context
import java.io.File

/**
 * モデルごとのカスタム / ビルトインプロンプトテンプレートを保存するストア。
 *
 * - ビルトインテンプレートは [BUILTIN_TEMPLATES] で定義（Llama 3 / Gemma / Qwen / ChatML / Alpaca / GPT-2 completion）。
 *   テンプレート本文は Hugging Face chat_template 互換の Jinja 構文 (llama.cpp minja) で記述し、
 *   ネイティブ側 (llama.cpp) の Jinja エンジンでレンダリングされる。
 * - ユーザーは各モデルに対して以下を選択できる:
 *     - "auto"  : GGUF 内蔵の chat_template を読み込んで適用 (読み取れない場合はモデル名判定にフォールバック)
 *     - "<builtin id>" : ビルトインテンプレート（例 "llama3", "gemma_chat"）
 *     - "custom": カスタム Jinja テンプレート文字列を保存して利用
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
     * 各テンプレートは Hugging Face chat_template 互換の Jinja 構文 (llama.cpp minja) で記述する。
     * 利用可能な変数:
     *   - `messages` : {role, content} の配列 (system も含む)
     *   - `add_generation_prompt` : 末尾に assistant 開始タグを残すかどうか
     *   - `enable_thinking` : Thinking モードが有効かどうか (llama.cpp が露出)
     * 各テンプレートは末尾でアシスタント開始タグを残し、モデルが応答本文から
     * 続けて生成できるようにする。
     */
    val BUILTIN_TEMPLATES: List<BuiltinTemplate> = listOf(
        BuiltinTemplate(
            id = "llama3",
            displayName = "Llama 3 / Llama 3.1 / Llama 3.2",
            description = "<|start_header_id|> 系のヘッダーフォーマット (Jinja)",
            template = """{% for message in messages %}<|start_header_id|>{{ message['role'] }}<|end_header_id|>

{{ message['content'] }}<|eot_id|>{% endfor %}{% if add_generation_prompt %}<|start_header_id|>assistant<|end_header_id|>

{% endif %}"""
        ),
        BuiltinTemplate(
            id = "gemma_chat",
            displayName = "Gemma (chat)",
            description = "<start_of_turn> / <end_of_turn>。Thinking ON 時は model ターンに <think> をプレフィル (Jinja)",
            template = """{% for message in messages %}{% if message['role'] == 'system' %}<start_of_turn>user
{{ message['content'] }}
<end_of_turn>
{% else %}<start_of_turn>{{ 'model' if message['role'] == 'assistant' else 'user' }}
{{ message['content'] }}<end_of_turn>
{% endif %}{% endfor %}<start_of_turn>model
{% if enable_thinking %}<think>
{% endif %}"""
        ),
        BuiltinTemplate(
            id = "gemma4_thinking",
            displayName = "Gemma 4 (thinking)",
            description = "Gemma 4 公式 thinking 仕様。Thinking ON 時にシステムターンへ <|think|> を振り分け、" +
                " llama.cpp の <think>...</think> 出力に合わせて assistant 側に <think> をプレフィルする (Jinja)。",
            template = """{% for message in messages %}{% if message['role'] == 'system' %}<start_of_turn>user
{% if enable_thinking %}<|think|>
{% endif %}{{ message['content'] }}
<end_of_turn>
{% else %}<start_of_turn>{{ 'model' if message['role'] == 'assistant' else 'user' }}
{{ message['content'] }}<end_of_turn>
{% endif %}{% endfor %}<start_of_turn>model
{% if enable_thinking %}<think>
{% endif %}"""
        ),
        BuiltinTemplate(
            id = "chatml",
            displayName = "ChatML (Qwen / Mistral / Bonsai)",
            description = "<|im_start|> / <|im_end|>。Thinking ON 時は assistant ターンに <think> をプレフィル (Jinja)",
            template = """{% for message in messages %}<|im_start|>{{ message['role'] }}
{{ message['content'] }}<|im_end|>
{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant
{% if enable_thinking %}<think>
{% endif %}{% endif %}"""
        ),
        BuiltinTemplate(
            id = "qwen_thinking",
            displayName = "Qwen (thinking / no_think)",
            description = "ChatML 本体。Thinking OFF 時は公式 non-thinking jinja 相当の空 <think></think> をプレフィル (Jinja)",
            template = """{% for message in messages %}<|im_start|>{{ message['role'] }}
{{ message['content'] }}<|im_end|>
{% endfor %}{% if add_generation_prompt %}<|im_start|>assistant
{% if enable_thinking is defined and enable_thinking is false %}<think>

</think>

{% endif %}{% endif %}"""
        ),
        BuiltinTemplate(
            id = "alpaca",
            displayName = "Alpaca / Instruct",
            description = "### Instruction / ### Response 形式 (Jinja)",
            template = """{% for message in messages %}{% if message['role'] == 'system' %}{{ message['content'] }}

{% else %}### {{ message['role'] }}:
{{ message['content'] }}

{% endif %}{% endfor %}{% if add_generation_prompt %}### Response:
{% endif %}"""
        ),
        BuiltinTemplate(
            id = "gpt2_completion",
            displayName = "GPT-2 / completion",
            description = "プレーンテキスト completion。chat/thinking 制御タグを使わない (Jinja)。",
            template = """{% for message in messages %}{% if message['role'] == 'system' %}{{ message['content'] }}

{% else %}{{ message['role'] }}: {{ message['content'] }}

{% endif %}{% endfor %}{% if add_generation_prompt %}assistant:{% endif %}"""
        ),
        BuiltinTemplate(
            id = "phi3",
            displayName = "Phi-3 / Phi-3.5",
            description = "<|user|> / <|assistant|> / <|end|> (Jinja)",
            template = """{% for message in messages %}<|{{ message['role'] }}|>
{{ message['content'] }}<|end|>
{% endfor %}{% if add_generation_prompt %}<|assistant|>
{% endif %}"""
        )
    )

    /**
     * Jinja テンプレートの軽量バリデーション (カスタムテンプレート UI 用)。
     * 正常時 null、失敗時はエラーメッセージを返す。
     *
     * 完全な構文解析は行わず、`{% if %}`/`{% endif %}`、`{% for %}`/`{% endfor %}` の
     * 対応数と `{{`/`}}` の閉じだけを確認する (実レンダリングは llama.cpp 側で行われる)。
     */
    fun lintJinjaTemplate(template: String): String? {
        if (template.isBlank()) return "テンプレートが空です"

        val varOpen = Regex("\\{\\{").findAll(template).count()
        val varClose = Regex("\\}\\}").findAll(template).count()
        if (varOpen != varClose) {
            return "`{{` と `}}` の数が一致しません ($varOpen / $varClose)"
        }

        val ifOpen = Regex("\\{%[-+]?\\s*if\\b").findAll(template).count()
        val ifClose = Regex("\\{%[-+]?\\s*endif\\s*[-+]?%\\}").findAll(template).count()
        if (ifOpen != ifClose) {
            return "`{% if %}` と `{% endif %}` の数が一致しません ($ifOpen / $ifClose)"
        }

        val forOpen = Regex("\\{%[-+]?\\s*for\\b").findAll(template).count()
        val forClose = Regex("\\{%[-+]?\\s*endfor\\s*[-+]?%\\}").findAll(template).count()
        if (forOpen != forClose) {
            return "`{% for %}` と `{% endfor %}` の数が一致しません ($forOpen / $forClose)"
        }

        return null
    }
}
