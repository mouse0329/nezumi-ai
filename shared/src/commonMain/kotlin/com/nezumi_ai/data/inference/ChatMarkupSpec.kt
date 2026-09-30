package com.nezumi_ai.data.inference

import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ThinkingPromptStyle
import com.nezumi_ai.data.inference.prompt.ModelNameHeuristics.ToolCallFormat

/**
 * GGUF `tokenizer.chat_template` (またはユーザー選択 Jinja) から読み取った
 * Thinking / Tool-call の区切り仕様。
 *
 * モデル名ハードコードの代わりに、テンプレートに実際に書かれているタグと制御変数を使う。
 * テンプレートが空 / 未設定のときは Gemma 4 用 [DEFAULT] ではなく
 * [NONE]（GPT-2 相当のプレーン completion、thinking 制御なし）を使う。
 */
data class ChatMarkupSpec(
    val thinkingPairs: List<Pair<String, String>>,
    val toolCallPairs: List<Pair<String, String>>,
    val channelOpen: String? = null,
    val channelClose: String? = null,
    val thoughtLabel: String? = null,
    val thinkingStyle: ThinkingPromptStyle? = null,
    val toolCallFormat: ToolCallFormat? = null,
    val supportsThinking: Boolean = false,
    val supportsTools: Boolean = false,
) {
    val thinkingOpenTags: List<String> get() = thinkingPairs.map { it.first }.distinct()
    val thinkingCloseTags: List<String> get() = thinkingPairs.map { it.second }.distinct()

    fun containsThinkingOpen(text: String): Boolean =
        thinkingOpenTags.any { it.isNotEmpty() && it in text } ||
            (channelOpen != null && thoughtLabel != null && (channelOpen + thoughtLabel) in text) ||
            (channelOpen != null && channelOpen in text)

    fun containsThinkingClose(text: String): Boolean =
        thinkingCloseTags.any { it.isNotEmpty() && it in text } ||
            (channelClose != null && channelClose in text)

    companion object {
        val DEFAULT: ChatMarkupSpec = ChatMarkupSpec(
            thinkingPairs = listOf(
                ToolCallTags.THINK_OPEN to ToolCallTags.THINK_CLOSE,
                ToolCallTags.GEMMA_THINK_TRIGGER to ToolCallTags.THINK_CLOSE_ALT,
            ),
            toolCallPairs = listOf(
                ToolCallTags.TOOL_CALL_OPEN to ToolCallTags.TOOL_CALL_CLOSE,
                ToolCallTags.GEMMA4_TOOL_CALL_OPEN to ToolCallTags.GEMMA4_TOOL_CALL_CLOSE,
            ),
            channelOpen = ToolCallTags.CHANNEL_OPEN,
            channelClose = ToolCallTags.CHANNEL_CLOSE,
            thoughtLabel = ToolCallTags.THOUGHT_LABEL,
            thinkingStyle = null,
            toolCallFormat = null,
            supportsThinking = true,
            supportsTools = true,
        )

        /**
         * テンプレート未設定 / 空文字用。chat / thinking 制御タグを一切仮定しない
         * (GPT-2 completion と同じ扱い)。
         */
        val NONE: ChatMarkupSpec = ChatMarkupSpec(
            thinkingPairs = emptyList(),
            toolCallPairs = emptyList(),
            channelOpen = null,
            channelClose = null,
            thoughtLabel = null,
            thinkingStyle = ThinkingPromptStyle.PLAIN_COMPLETION,
            toolCallFormat = null,
            supportsThinking = false,
            supportsTools = false,
        )

        /**
         * Jinja / chat_template 文字列を静的スキャンして仕様を組み立てる。
         * 空テンプレートは Gemma 4 用 [DEFAULT] ではなく [NONE] を返す。
         */
        fun fromChatTemplate(template: String?): ChatMarkupSpec {
            if (template.isNullOrBlank()) return NONE
            return ChatMarkupSpecExtractor.extract(template)
        }
    }
}

/**
 * chat_template から Thinking / Tool-call のタグと制御スタイルを読む。
 */
object ChatMarkupSpecExtractor {

    fun extract(template: String): ChatMarkupSpec {
        val thinkingPairs = mutableListOf<Pair<String, String>>()
        if (ToolCallTags.THINK_OPEN in template && ToolCallTags.THINK_CLOSE in template) {
            thinkingPairs += ToolCallTags.THINK_OPEN to ToolCallTags.THINK_CLOSE
        }
        if (ToolCallTags.GEMMA_THINK_TRIGGER in template && ToolCallTags.THINK_CLOSE_ALT in template) {
            thinkingPairs += ToolCallTags.GEMMA_THINK_TRIGGER to ToolCallTags.THINK_CLOSE_ALT
        }
        if (ToolCallTags.GEMMA_THINK_TRIGGER in template &&
            thinkingPairs.none { it.first == ToolCallTags.GEMMA_THINK_TRIGGER }
        ) {
            // 開きだけ宣言されているテンプレ (Gemma 4 のシステム側 <|think|>) も記録する。
            thinkingPairs += ToolCallTags.GEMMA_THINK_TRIGGER to ToolCallTags.THINK_CLOSE_ALT
        }
        if (thinkingPairs.isEmpty()) {
            thinkingPairs += ToolCallTags.THINK_OPEN to ToolCallTags.THINK_CLOSE
        }

        val toolCallPairs = mutableListOf<Pair<String, String>>()
        if (ToolCallTags.GEMMA4_TOOL_CALL_OPEN in template &&
            ToolCallTags.GEMMA4_TOOL_CALL_CLOSE in template
        ) {
            toolCallPairs += ToolCallTags.GEMMA4_TOOL_CALL_OPEN to ToolCallTags.GEMMA4_TOOL_CALL_CLOSE
        }
        if (ToolCallTags.TOOL_CALL_OPEN in template && ToolCallTags.TOOL_CALL_CLOSE in template) {
            toolCallPairs += ToolCallTags.TOOL_CALL_OPEN to ToolCallTags.TOOL_CALL_CLOSE
        }
        if (toolCallPairs.isEmpty() && ModelNameHeuristics.templateDeclaresToolSupport(template)) {
            toolCallPairs += ToolCallTags.TOOL_CALL_OPEN to ToolCallTags.TOOL_CALL_CLOSE
        }

        val hasChannel =
            ToolCallTags.CHANNEL_OPEN in template && ToolCallTags.CHANNEL_CLOSE in template

        val supportsThinking = ModelNameHeuristics.templateDeclaresThinking(template)

        val supportsTools = ModelNameHeuristics.templateDeclaresToolSupport(template)

        return ChatMarkupSpec(
            thinkingPairs = thinkingPairs.distinct(),
            toolCallPairs = toolCallPairs.distinct(),
            channelOpen = if (hasChannel) ToolCallTags.CHANNEL_OPEN else null,
            channelClose = if (hasChannel) ToolCallTags.CHANNEL_CLOSE else null,
            thoughtLabel = if (hasChannel) ToolCallTags.THOUGHT_LABEL else null,
            thinkingStyle = inferThinkingStyle(template, hasChannel),
            toolCallFormat = inferToolCallFormat(template),
            supportsThinking = supportsThinking,
            supportsTools = supportsTools,
        )
    }

    fun inferThinkingStyle(template: String, hasChannel: Boolean = ToolCallTags.CHANNEL_OPEN in template): ThinkingPromptStyle? {
        if (template.isBlank()) return null
        val usesSoftSwitch =
            ToolCallTags.QWEN_THINK_COMMAND in template &&
                ToolCallTags.QWEN_NO_THINK_COMMAND in template
        val usesEmptyThinkPrefill =
            template.contains("<think>") && template.contains("</think>") &&
                (template.contains("enable_thinking") || template.contains("thinking"))
        val usesAssistantThinkPrefill =
            template.contains(ToolCallTags.THINK_OPEN) && template.contains("add_generation_prompt")
        return when {
            hasChannel && ToolCallTags.GEMMA_THINK_TRIGGER in template ->
                ThinkingPromptStyle.GEMMA4_CHANNEL
            ToolCallTags.GEMMA_THINK_TRIGGER in template && !usesSoftSwitch ->
                ThinkingPromptStyle.GEMMA_PREFIX
            usesSoftSwitch && !template.contains("<|/think|>") ->
                ThinkingPromptStyle.QWEN_COMMAND
            usesEmptyThinkPrefill && template.contains("enable_thinking") ->
                ThinkingPromptStyle.QWEN_ASSISTANT_PREFILL
            usesAssistantThinkPrefill || template.contains(ToolCallTags.THINK_OPEN) ->
                ThinkingPromptStyle.ASSISTANT_TAG
            else -> null
        }
    }

    fun inferToolCallFormat(template: String): ToolCallFormat? {
        if (template.isBlank()) return null
        return when {
            ToolCallTags.GEMMA4_TOOL_CALL_OPEN in template &&
                ToolCallTags.GEMMA4_TOOL_CALL_CLOSE in template ->
                ToolCallFormat.GEMMA4
            template.contains("<function=") || template.contains("<function =") ->
                ToolCallFormat.GRANITE
            ModelNameHeuristics.templateDeclaresToolSupport(template) ->
                ToolCallFormat.GENERIC
            else -> null
        }
    }
}
