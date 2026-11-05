package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.data.inference.InferenceStreamProtocol

fun SharedChatState.reduceRawStreamChunk(
    chunk: String
): SharedChatState {
    var state = this
    for (segment in InferenceStreamProtocol.splitStreamChunks(chunk)) {
        val finalText = InferenceStreamProtocol.decodeFinal(segment)
        if (finalText != null) {
            state = state
                .reduce(SharedChatEvent.AssistantChunkReceived(finalText))
                .reduce(SharedChatEvent.AssistantResponseFinished)
            continue
        }
        InferenceStreamProtocol.decodeThinkChunk(segment)?.let {
            state = state.reduce(SharedChatEvent.ThinkingChunkReceived(it))
            continue
        }
        InferenceStreamProtocol.decodeToolCallChunk(segment)?.let {
            state = state.reduce(
                SharedChatEvent.ToolNamesReceived(
                    it.split(",").map(String::trim).filter(String::isNotEmpty)
                )
            )
            continue
        }
        if (InferenceStreamProtocol.decodeToolResultChunk(segment) != null) {
            continue
        }
        if (InferenceStreamProtocol.decodeToolResults(segment) != null) {
            continue
        }
        if (InferenceStreamProtocol.decodeExecutedToolsList(segment) != null) {
            continue
        }
        InferenceStreamProtocol.decodeTps(segment)?.let {
            state = state.reduce(SharedChatEvent.GenerationTpsReceived(it))
            continue
        }
        if (InferenceStreamProtocol.decodeMediaProgress(segment) != null) {
            continue
        }
        if (InferenceStreamProtocol.decodeToolRoundLimit(segment) != null) {
            continue
        }
        state = state.reduce(SharedChatEvent.AssistantChunkReceived(segment))
    }
    return state
}
