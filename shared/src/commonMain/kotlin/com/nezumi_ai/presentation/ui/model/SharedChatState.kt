package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.presentation.ui.composable.SharedChatMessage
import com.nezumi_ai.presentation.ui.composable.SharedMessageRole

data class SharedChatState(
    val messages: List<SharedChatMessage> = emptyList(),
    val draft: String = "",
    val thinkingText: String = "",
    val activeToolNames: List<String> = emptyList(),
    val generationTps: Float? = null,
    val error: String? = null
) {
    fun withDraft(value: String): SharedChatState = copy(draft = value)

    fun appendUserMessage(): SharedChatState {
        val text = draft.trim()
        if (text.isEmpty()) return this
        return copy(
            messages = messages + SharedChatMessage(
                id = "user-${messages.size}",
                role = SharedMessageRole.USER,
                text = text
            ),
            draft = ""
        )
    }

    fun beginAssistantResponse(): SharedChatState {
        val assistantId = "assistant-${messages.size}"
        return copy(
            messages = messages + SharedChatMessage(
                id = assistantId,
                role = SharedMessageRole.ASSISTANT,
                text = "",
                isStreaming = true
            ),
            thinkingText = "",
            activeToolNames = emptyList(),
            generationTps = null,
            error = null
        )
    }

    fun appendThinkingChunk(chunk: String): SharedChatState =
        if (chunk.isEmpty()) this else copy(thinkingText = thinkingText + chunk)

    fun setActiveTools(names: List<String>): SharedChatState =
        copy(activeToolNames = names.filter { it.isNotBlank() })

    fun setGenerationTps(tps: Float): SharedChatState =
        copy(generationTps = tps.takeIf { it.isFinite() && it >= 0f })

    fun appendAssistantChunk(chunk: String): SharedChatState {
        if (chunk.isEmpty()) return this
        val index = messages.indexOfLast {
            it.role == SharedMessageRole.ASSISTANT && it.isStreaming
        }
        if (index < 0) return this
        val current = messages[index]
        val updated = current.copy(text = current.text + chunk)
        return copy(messages = messages.toMutableList().also { it[index] = updated })
    }

    fun finishAssistantResponse(): SharedChatState {
        val index = messages.indexOfLast {
            it.role == SharedMessageRole.ASSISTANT && it.isStreaming
        }
        if (index < 0) return this
        val current = messages[index]
        return copy(
            messages = messages.toMutableList().also {
                it[index] = current.copy(isStreaming = false)
            },
            activeToolNames = emptyList()
        )
    }

    fun failAssistantResponse(message: String): SharedChatState {
        val errorText = message.trim()
        if (errorText.isEmpty()) return finishAssistantResponse()
        val index = messages.indexOfLast {
            it.role == SharedMessageRole.ASSISTANT && it.isStreaming
        }
        if (index < 0) {
            return copy(
                messages = messages + SharedChatMessage(
                    id = "assistant-error-${messages.size}",
                    role = SharedMessageRole.ASSISTANT,
                    text = errorText,
                    isStreaming = false
                ),
                error = errorText,
                activeToolNames = emptyList()
            )
        }
        val current = messages[index]
        return copy(
            messages = messages.toMutableList().also {
                it[index] = current.copy(
                    text = if (current.text.isBlank()) errorText else "${current.text}\n$errorText",
                    isStreaming = false
                )
            },
            error = errorText,
            activeToolNames = emptyList()
        )
    }

    companion object {
        fun initial(): SharedChatState = SharedChatState(
            messages = listOf(
                SharedChatMessage(
                    id = "welcome",
                    role = SharedMessageRole.ASSISTANT,
                    text = "Desktop版の共通UIへようこそ。メッセージを入力してください。"
                )
            )
        )
    }
}
