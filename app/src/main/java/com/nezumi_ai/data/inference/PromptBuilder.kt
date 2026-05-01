package com.nezumi_ai.data.inference

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
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
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
        sanitizeMessageContent: (MessageEntity) -> String
    ): String = when (format) {
        GgufPromptFormat.GEMMA_CHAT -> buildForGgufGemma(messages, systemPrompt, compressedSummary, sanitizeMessageContent)
        GgufPromptFormat.CHATML     -> buildForGgufChatMl(messages, systemPrompt, compressedSummary, sanitizeMessageContent)
    }

    private fun buildForGgufGemma(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val sb = StringBuilder()
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
        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "model" else "user"
            sb.append("<start_of_turn>").append(role).append('\n')
                .append(content).append('\n').append("<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun buildForGgufChatMl(
        messages: List<MessageEntity>,
        systemPrompt: String,
        compressedSummary: String?,
        sanitizeMessageContent: (MessageEntity) -> String
    ): String {
        val sb = StringBuilder()
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
        for (msg in messages) {
            val content = sanitizeMessageContent(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "assistant" else "user"
            sb.append("<|im_start|>").append(role).append('\n')
                .append(content).append("\n<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
