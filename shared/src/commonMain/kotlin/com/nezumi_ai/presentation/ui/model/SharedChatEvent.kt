package com.nezumi_ai.presentation.ui.model

sealed interface SharedChatEvent {
    data class DraftChanged(val value: String) : SharedChatEvent
    data object SendRequested : SharedChatEvent
    data object AssistantResponseStarted : SharedChatEvent
    data class ThinkingChunkReceived(val chunk: String) : SharedChatEvent
    data class ToolNamesReceived(val names: List<String>) : SharedChatEvent
    data class GenerationTpsReceived(val tps: Float) : SharedChatEvent
    data class AssistantChunkReceived(val chunk: String) : SharedChatEvent
    data object AssistantResponseFinished : SharedChatEvent
    data class AssistantResponseFailed(val message: String) : SharedChatEvent
}

fun SharedChatState.reduce(event: SharedChatEvent): SharedChatState =
    when (event) {
        is SharedChatEvent.DraftChanged -> withDraft(event.value)
        SharedChatEvent.SendRequested -> appendUserMessage()
        SharedChatEvent.AssistantResponseStarted -> beginAssistantResponse()
        is SharedChatEvent.ThinkingChunkReceived -> appendThinkingChunk(event.chunk)
        is SharedChatEvent.ToolNamesReceived -> setActiveTools(event.names)
        is SharedChatEvent.GenerationTpsReceived -> setGenerationTps(event.tps)
        is SharedChatEvent.AssistantChunkReceived -> appendAssistantChunk(event.chunk)
        SharedChatEvent.AssistantResponseFinished -> finishAssistantResponse()
        is SharedChatEvent.AssistantResponseFailed -> failAssistantResponse(event.message)
    }
