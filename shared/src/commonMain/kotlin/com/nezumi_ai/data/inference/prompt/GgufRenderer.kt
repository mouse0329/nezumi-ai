package com.nezumi_ai.data.inference.prompt

import com.nezumi_ai.data.inference.ToolCallTags

/**
 * GGUF (llama.cpp) 向けレンダラー (計画書 Phase 3)。
 *
 * [ConversationInput] と [PromptFormat] から最終プロンプト文字列を組み立てる
 * 純粋関数。テンプレート文字列を手書きするのはこのファイルだけ
 * (旧 `PromptBuilder.buildForGguf` 系の移行先)。
 *
 * ネイティブ minja (llama.cpp) によるレンダリングはここでは行わない。
 * ユーザー指定テンプレート / GGUF 内蔵テンプレートの優先経路は呼び出し側
 * (Phase 6 の ChatViewModel) が先に試し、使えない場合のみこのレンダラーの
 * 推定フォールバック経路に来る (FormatResolver 参照)。
 */
object GgufRenderer {

    /**
     * [ConversationInput] を [format] の書式で文字列化する。
     *
     * - [ConversationInput.systemInstruction] と [ConversationInput.toolsBlock] は
     *   システム (Gemma では先頭 user) ターンに連結して埋め込む。
     * - thinking 制御は [modelPathOrName] から [ModelNameHeuristics.resolveThinkingPromptStyle]
     *   で解決したスタイルと [ThinkingStyleSpec] に従う (旧 buildForGguf 系と同一挙動)。
     * - [PromptFormat.CustomJinja] はネイティブ minja が失敗した場合のフォールバック
     *   としてのみ到達する想定で、[PromptFormat.CustomJinja.fallback] で再帰描画する。
     *
     * @param modelPathOrName モデルパス / モデル名 (thinking スタイル解決用)。
     *        空文字の場合はスタイル解決を行わず ASSISTANT_TAG 相当の汎用制御になる。
     * @param hasExplicitUserTemplate ユーザーが MODE_AUTO 以外のテンプレートを選択済みか。
     *        Bug fix(#42) parity: true の場合は GPT-2 でも thinking 制御を
     *        PLAIN_COMPLETION に強制しない (旧 PromptBuilder.buildForGguf と同一)。
     */
    fun render(
        input: ConversationInput,
        format: PromptFormat,
        modelPathOrName: String = "",
        hasExplicitUserTemplate: Boolean = false,
    ): String {
        val effectiveFormat = when (format) {
            is PromptFormat.CustomJinja -> format.fallback
            else -> format
        }
        // CustomJinja の fallback が入れ子の CustomJinja になることはない
        // (PromptFormat.CustomJinja のデフォルトは ChatMl) が、防御的に一段だけほぐす。
        return when (effectiveFormat) {
            is PromptFormat.CustomJinja -> render(input, effectiveFormat.fallback, modelPathOrName, hasExplicitUserTemplate)
            PromptFormat.GemmaChat -> renderGemmaChat(input, modelPathOrName, hasExplicitUserTemplate)
            PromptFormat.ChatMl -> renderChatMl(input, modelPathOrName, hasExplicitUserTemplate)
            PromptFormat.Llama3 -> renderLlama3(input)
            PromptFormat.PlainCompletion -> renderPlainCompletion(input)
        }
    }

    /** システムターンに埋め込む本文 (systemInstruction + toolsBlock)。 */
    private fun systemContent(input: ConversationInput): String = buildString {
        if (input.systemInstruction.isNotBlank()) append(input.systemInstruction.trim())
        if (!input.toolsBlock.isNullOrBlank()) {
            if (isNotEmpty()) append("\n")
            append(input.toolsBlock)
        }
    }

    /** thinking 制御スタイル (旧 PromptBuilder 内での resolveThinkingPromptStyle 呼び出しに相当)。 */
    private fun resolveStyle(
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
    ): ModelNameHeuristics.ThinkingPromptStyle =
        if (modelPathOrName.isBlank()) {
            ModelNameHeuristics.ThinkingPromptStyle.ASSISTANT_TAG
        } else {
            ModelNameHeuristics.resolveThinkingPromptStyle(
                modelPathOrName,
                hasExplicitUserTemplate = hasExplicitUserTemplate,
            )
        }

    /**
     * Gemma 系: `<start_of_turn>` / `<end_of_turn>`。
     * Gemma 4 (GEMMA4_CHANNEL) では thinking ON 時にシステムターン内へ `<|think|>` を埋め、
     * model ターン直後に `<think>\n` をプレフィルする (旧 buildForGgufGemma と同一)。
     */
    private fun renderGemmaChat(
        input: ConversationInput,
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
    ): String {
        val style = resolveStyle(modelPathOrName, hasExplicitUserTemplate)
        val sb = StringBuilder()
        val lastUserIndex = input.history.indexOfLast {
            it.role == ConversationTurn.Role.USER && it.content.isNotBlank()
        }
        sb.append(ThinkingStyleSpec.globalPrefix(style, input.enableThinking))
        val systemText = systemContent(input)
        // Gemma4: Google 公式テンプレ仕様に従い、thinking ON 時はシステムターンの先頭に
        // `<|think|>` を埋め込む (システムプロンプトが空でも専用システムターンを生成する)。
        val injectGemma4SystemThink =
            ThinkingStyleSpec.injectsGemma4SystemThinkTrigger(style, input.enableThinking)
        if (systemText.isNotEmpty() || injectGemma4SystemThink) {
            sb.append("<start_of_turn>user\n")
            if (injectGemma4SystemThink) {
                sb.append(ToolCallTags.GEMMA_THINK_TRIGGER)
                if (systemText.isNotEmpty()) sb.append('\n')
            }
            if (systemText.isNotEmpty()) sb.append(systemText)
            sb.append('\n').append("<end_of_turn>\n")
        }
        val usesQwenSoftSwitch = ThinkingStyleSpec.usesQwenSoftSwitch(style)
        val qwenDirective = ThinkingStyleSpec.qwenSoftSwitchDirective(input.enableThinking)
        input.history.forEachIndexed { index, turn ->
            if (turn.role == ConversationTurn.Role.SYSTEM) return@forEachIndexed
            var content = turn.content
            if (content.isBlank()) return@forEachIndexed
            if (usesQwenSoftSwitch && index == lastUserIndex) {
                content = appendDirectiveOnce(content, qwenDirective)
            }
            val role = if (turn.role == ConversationTurn.Role.ASSISTANT) "model" else "user"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(content).append('\n').append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        // Qwen OFF 時の「空 <think></think>」も含めて Spec 経由で適用。
        sb.append(ThinkingStyleSpec.assistantPrefill(style, input.enableThinking))
        return sb.toString()
    }

    /**
     * ChatML 系: `<|im_start|>` / `<|im_end|>` (Qwen / Mistral / Llama 3 等)。
     * 旧 buildForGgufChatMl と同一挙動。
     */
    private fun renderChatMl(
        input: ConversationInput,
        modelPathOrName: String,
        hasExplicitUserTemplate: Boolean = false,
    ): String {
        val style = resolveStyle(modelPathOrName, hasExplicitUserTemplate)
        val sb = StringBuilder()
        val lastUserIndex = input.history.indexOfLast {
            it.role == ConversationTurn.Role.USER && it.content.isNotBlank()
        }
        sb.append(ThinkingStyleSpec.globalPrefix(style, input.enableThinking))
        val systemText = systemContent(input)
        // Gemma4 が ChatML 経由 (ユーザーが手動で chatml 選択した場合等) で来た場合も
        // システムターンに <|think|> を埋め込んで thinking を発火させる。
        val injectGemma4SystemThink =
            ThinkingStyleSpec.injectsGemma4SystemThinkTrigger(style, input.enableThinking)
        if (systemText.isNotEmpty() || injectGemma4SystemThink) {
            sb.append("<|im_start|>system\n")
            if (injectGemma4SystemThink) {
                sb.append(ToolCallTags.GEMMA_THINK_TRIGGER)
                if (systemText.isNotEmpty()) sb.append('\n')
            }
            if (systemText.isNotEmpty()) sb.append(systemText)
            sb.append("\n<|im_end|>\n")
        }
        val usesQwenSoftSwitch = ThinkingStyleSpec.usesQwenSoftSwitch(style)
        val qwenDirective = ThinkingStyleSpec.qwenSoftSwitchDirective(input.enableThinking)
        input.history.forEachIndexed { index, turn ->
            if (turn.role == ConversationTurn.Role.SYSTEM) return@forEachIndexed
            var content = turn.content
            if (content.isBlank()) return@forEachIndexed
            if (usesQwenSoftSwitch && index == lastUserIndex) {
                content = appendDirectiveOnce(content, qwenDirective)
            }
            val role = if (turn.role == ConversationTurn.Role.ASSISTANT) "assistant" else "user"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        sb.append(ThinkingStyleSpec.assistantPrefill(style, input.enableThinking))
        return sb.toString()
    }

    /**
     * Llama 3 系ネイティブ: `<|start_header_id|>role<|end_header_id|>`。
     * thinking 制御タグは注入しない (内蔵テンプレートに任せる想定のフォールバック)。
     */
    private fun renderLlama3(input: ConversationInput): String {
        val sb = StringBuilder()
        val systemText = systemContent(input)
        if (systemText.isNotEmpty()) {
            sb.append("<|start_header_id|>system<|end_header_id|>\n\n")
                .append(systemText).append("<|eot_id|>")
        }
        input.history.forEach { turn ->
            if (turn.content.isBlank()) return@forEach
            val role = when (turn.role) {
                ConversationTurn.Role.SYSTEM -> "system"
                ConversationTurn.Role.USER -> "user"
                ConversationTurn.Role.ASSISTANT -> "assistant"
            }
            sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                .append(turn.content).append("<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    /**
     * GPT-2 等の plain completion モデル。chat/thinking 制御タグは一切注入しない
     * (旧 buildForGgufPlainCompletion と同一)。
     */
    private fun renderPlainCompletion(input: ConversationInput): String {
        val sb = StringBuilder()
        val systemText = systemContent(input)
        if (systemText.isNotBlank()) {
            sb.append(systemText.trim()).append("\n\n")
        }
        input.history.forEach { turn ->
            if (turn.content.isBlank()) return@forEach
            val role = if (turn.role == ConversationTurn.Role.ASSISTANT) "assistant" else "user"
            sb.append(role).append(": ").append(turn.content.trim()).append("\n\n")
        }
        sb.append("assistant:")
        return sb.toString()
    }

    private fun appendDirectiveOnce(content: String, directive: String): String {
        val trimmed = content.trimEnd()
        val directives = listOf(ToolCallTags.QWEN_THINK_COMMAND, ToolCallTags.QWEN_NO_THINK_COMMAND)
        if (directives.any { trimmed.endsWith(it) }) return trimmed
        if (directive in content) return trimmed
        return "$trimmed\n$directive"
    }
}
