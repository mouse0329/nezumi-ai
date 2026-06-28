package com.nezumi_ai.data.inference

import android.content.Context
import com.nezumi_ai.data.database.entity.MessageEntity

object PromptBuilder {
    private const val COMPRESSED_CONTEXT_HEADER = "以下は過去会話の圧縮コンテキストです:"
    private const val GEMMA_THINK_PREFIX = "<|think|>\n"
    private const val ASSISTANT_THINK_PREFILL = "<think>\n"
    private const val QWEN_THINK_COMMAND = "/think"
    private const val QWEN_NO_THINK_COMMAND = "/no_think"

    enum class ThinkingPromptStyle {
        GEMMA_PREFIX,
        QWEN_COMMAND,
        ASSISTANT_TAG
    }

    /** GGUF モデルのプロンプトフォーマット */
    enum class GgufPromptFormat {
        /** Gemma 系: <start_of_turn> / <end_of_turn> */
        GEMMA_CHAT,
        /** Llama 3 / Mistral / Bonsai 等: <|im_start|> / <|im_end|> ChatML */
        CHATML,
    }

    fun detectGgufFormat(modelPath: String): GgufPromptFormat {
        val name = modelPath.lowercase()
        return if ("gemma" in name) GgufPromptFormat.GEMMA_CHAT else GgufPromptFormat.CHATML
    }

    fun resolveThinkingPromptStyle(modelPath: String): ThinkingPromptStyle {
        val name = modelPath.lowercase()
        return when {
            Regex("(^|[^a-z0-9])(qwen|qwq)([^a-z0-9]|$)").containsMatchIn(name) -> ThinkingPromptStyle.QWEN_COMMAND
            "gemma" in name -> ThinkingPromptStyle.GEMMA_PREFIX
            else -> ThinkingPromptStyle.ASSISTANT_TAG
        }
    }

    fun usesAssistantThinkingPrefill(modelPath: String): Boolean {
        return resolveThinkingPromptStyle(modelPath) == ThinkingPromptStyle.ASSISTANT_TAG
    }

    fun buildForLiteRt(
        messages: List<MessageEntity>,
        systemPrompt: String,
        injectGemmaThinkTrigger: Boolean,
        compressedSummary: String? = null,
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context? = null,
        modelPath: String = ""
    ): String {
        // ユーザーがカスタム / ビルトインテンプレートを設定している場合はそちらを優先
        val customTemplate = appContext?.let { ctx ->
            if (modelPath.isNotBlank()) PromptTemplateStore.resolveTemplate(ctx, modelPath) else null
        }
        if (customTemplate != null) {
            return buildWithCustomTemplate(
                template = customTemplate,
                messages = messages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                enableThinking = injectGemmaThinkTrigger,
                sanitizeMessageContent = sanitizeMessageContent,
                modelPath = modelPath
            )
        }

        val contextBuilder = StringBuilder()
        if (injectGemmaThinkTrigger) {
            contextBuilder.append(GEMMA_THINK_PREFIX)
        }
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        if (!compressedSummary.isNullOrBlank()) {
            contextBuilder.append(COMPRESSED_CONTEXT_HEADER)
            contextBuilder.append('\n')
            contextBuilder.append(compressedSummary)
            contextBuilder.append("\n\n")
        }

        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(content)
                .append('\n')
        }
        contextBuilder.append("Assistant:")
        return contextBuilder.toString()
    }

    fun buildForGguf(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String? = null,
        format: GgufPromptFormat = GgufPromptFormat.CHATML,
        enableThinking: Boolean = false,
        modelPath: String = "",
        sanitizeMessageContent: (MessageEntity) -> String,
        appContext: Context? = null
    ): String {
        // ユーザー設定のテンプレ（カスタム / ビルトイン）があればそれを優先
        val customTemplate = appContext?.let { ctx ->
            if (modelPath.isNotBlank()) PromptTemplateStore.resolveTemplate(ctx, modelPath) else null
        }
        if (customTemplate != null) {
            return buildWithCustomTemplate(
                template = customTemplate,
                messages = messages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                enableThinking = enableThinking,
                sanitizeMessageContent = sanitizeMessageContent,
                modelPath = modelPath
            )
        }

        return when (format) {
            GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
            GgufPromptFormat.CHATML -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
        }
    }

    /**
     * カスタム / ビルトインテンプレートで実際にプロンプトを構築する共通ルート。
     *
     * ここで圧縮済みサマリーをシステムプロンプトに前置きし、
     * 履歴メッセージは role + sanitized content の形で [PromptTemplateEngine] に渡す。
     */
    private fun buildWithCustomTemplate(
        template: String,
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        sanitizeMessageContent: (MessageEntity) -> String,
        modelPath: String
    ): String {
        val systemFinal = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        val style = resolveThinkingPromptStyle(modelPath)
        val rawHistory = messages.mapNotNull { msg ->
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) return@mapNotNull null
            val role = if (msg.role == "assistant") "assistant" else "user"
            PromptTemplateEngine.HistoryMessage(role = role, content = content)
        }
        val history = decorateHistoryForThinkingStyle(rawHistory, style, enableThinking)
        val lastUserContent = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val lastAssistantContent = history.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        val ctx = PromptTemplateEngine.PromptContext(
            system = systemFinal,
            prompt = lastUserContent,
            response = lastAssistantContent,
            thinking = enableThinking && style == ThinkingPromptStyle.ASSISTANT_TAG,
            history = history
        )
        return try {
            thinkingGlobalPrefix(style, enableThinking) + PromptTemplateEngine.render(template, ctx)
        } catch (e: Exception) {
            // フォールバック: テンプレ崩壊時は ChatML で構築（最悪でもプロンプトが消えないようにする）
            buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
        }
    }

    private fun thinkingGlobalPrefix(style: ThinkingPromptStyle, enableThinking: Boolean): String {
        return if (enableThinking && style == ThinkingPromptStyle.GEMMA_PREFIX) GEMMA_THINK_PREFIX else ""
    }

    private fun decorateHistoryForThinkingStyle(
        history: List<PromptTemplateEngine.HistoryMessage>,
        style: ThinkingPromptStyle,
        enableThinking: Boolean
    ): List<PromptTemplateEngine.HistoryMessage> {
        if (style != ThinkingPromptStyle.QWEN_COMMAND) return history
        val lastUserIndex = history.indexOfLast { it.role == "user" && it.content.isNotBlank() }
        if (lastUserIndex < 0) return history
        val directive = if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND
        return history.mapIndexed { index, msg ->
            if (index == lastUserIndex) msg.copy(content = appendDirectiveOnce(msg.content, directive)) else msg
        }
    }

    private fun appendDirectiveOnce(content: String, directive: String): String {
        val trimmed = content.trimEnd()
        val directives = listOf(QWEN_THINK_COMMAND, QWEN_NO_THINK_COMMAND)
        if (directives.any { trimmed.endsWith(it) }) return trimmed
        return "$trimmed\n$directive"
    }

    private fun buildForGgufGemma(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val style = resolveThinkingPromptStyle(modelPath)
        val sb = StringBuilder()
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
        sb.append(thinkingGlobalPrefix(style, enableThinking))
        val hasPrelude = systemPrompt.isNotEmpty() || !compressedSummary.isNullOrBlank()
        if (hasPrelude) {
            sb.append("<start_of_turn>user\n")
            if (systemPrompt.isNotEmpty()) sb.append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (systemPrompt.isNotEmpty()) sb.append("\n\n")
                sb.append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
            sb.append('\n').append("<end_of_turn>\n")
        }
        for (index in messages.indices) {
            val msg = messages[index]
            var content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            if (style == ThinkingPromptStyle.QWEN_COMMAND && index == lastUserMessage) {
                content = appendDirectiveOnce(content, if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND)
            }
            val role = if (msg.role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(content).append('\n').append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        if (enableThinking && style == ThinkingPromptStyle.ASSISTANT_TAG) {
            sb.append(ASSISTANT_THINK_PREFILL)
        }
        return sb.toString()
    }

    private fun buildForGgufChatMl(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val style = resolveThinkingPromptStyle(modelPath)
        val sb = StringBuilder()
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
        sb.append(thinkingGlobalPrefix(style, enableThinking))
        val systemContent = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (systemPrompt.isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        if (systemContent.isNotEmpty()) {
            sb.append("<|im_start|>system\n").append(systemContent).append("\n<|im_end|>\n")
        }
        for (index in messages.indices) {
            val msg = messages[index]
            var content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            if (style == ThinkingPromptStyle.QWEN_COMMAND && index == lastUserMessage) {
                content = appendDirectiveOnce(content, if (enableThinking) QWEN_THINK_COMMAND else QWEN_NO_THINK_COMMAND)
            }
            val role = if (msg.role == "assistant") "assistant" else "user"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        if (enableThinking && style == ThinkingPromptStyle.ASSISTANT_TAG) {
            sb.append(ASSISTANT_THINK_PREFILL)
        }
        return sb.toString()
    }
}
