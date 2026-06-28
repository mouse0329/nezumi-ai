package com.nezumi_ai.data.inference

import android.content.Context
import com.nezumi_ai.data.database.entity.MessageEntity

object PromptBuilder {
    private const val COMPRESSED_CONTEXT_HEADER = "以下は過去会話の圧縮コンテキストです:"

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
                sanitizeMessageContent = sanitizeMessageContent
            )
        }

        val contextBuilder = StringBuilder()
        if (injectGemmaThinkTrigger) {
            contextBuilder.append("<|think|>\n")
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
                sanitizeMessageContent = sanitizeMessageContent
            )
        }

        return when (format) {
            GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
            GgufPromptFormat.CHATML     -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, modelPath, sanitizeMessageContent)
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
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val systemFinal = buildString {
            if (systemPrompt.isNotEmpty()) append(systemPrompt)
            if (!compressedSummary.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(COMPRESSED_CONTEXT_HEADER).append('\n').append(compressedSummary)
            }
        }
        val history = messages.mapNotNull { msg ->
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) return@mapNotNull null
            val role = if (msg.role == "assistant") "assistant" else "user"
            PromptTemplateEngine.HistoryMessage(role = role, content = content)
        }
        val lastUserContent = history.lastOrNull { it.role == "user" }?.content.orEmpty()
        val lastAssistantContent = history.lastOrNull { it.role == "assistant" }?.content.orEmpty()
        val ctx = PromptTemplateEngine.PromptContext(
            system = systemFinal,
            prompt = lastUserContent,
            response = lastAssistantContent,
            thinking = enableThinking,
            history = history
        )
        return try {
            PromptTemplateEngine.render(template, ctx)
        } catch (e: Exception) {
            // フォールバック: テンプレ崩壊時は ChatML で構築（最悪でもプロンプトが消えないようにする）
            buildForGgufChatMl(messages, systemPrompt, compressedSummary, enableThinking, "", sanitizeMessageContent)
        }
    }

    private fun shouldUseQwenInstantDirective(modelPath: String): Boolean {
        val name = modelPath.lowercase()
        return Regex("(^|[^a-z0-9])qwen([0-9._-]*)([^a-z0-9]|$)").containsMatchIn(name)
    }

    private fun buildForGgufGemma(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        enableThinking: Boolean,
        modelPath: String,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val sb = StringBuilder()
        val instantDirective = !enableThinking && shouldUseQwenInstantDirective(modelPath)
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
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
            if (instantDirective && index == lastUserMessage) {
                content = "$content\n/no_think"
            }
            val role = if (msg.role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(content).append('\n').append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        when {
            enableThinking -> sb.append("<think>\n")
            instantDirective -> sb.append("<think>\n\n</think>\n\n")
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
        val sb = StringBuilder()
        val instantDirective = !enableThinking && shouldUseQwenInstantDirective(modelPath)
        val lastUserMessage = messages.indexOfLast { it.role == "user" && sanitizeMessageContent(it).isNotBlank() }
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
            if (instantDirective && index == lastUserMessage) {
                content = "$content\n/no_think"
            }
            val role = if (msg.role == "assistant") "assistant" else "user"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        when {
            enableThinking -> sb.append("<think>\n")
            instantDirective -> sb.append("<think>\n\n</think>\n\n")
        }
        return sb.toString()
    }
}
