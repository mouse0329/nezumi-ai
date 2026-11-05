package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.data.inference.InferenceStreamProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedChatStateTest {
    @Test
    fun sendRequestAddsTrimmedUserMessageAndClearsDraft() {
        val state = SharedChatState.initial()
            .reduce(SharedChatEvent.DraftChanged("  hello  "))
            .reduce(SharedChatEvent.SendRequested)

        assertEquals("", state.draft)
        assertEquals("hello", state.messages.last().text)
        assertEquals("user", state.messages.last().role.name.lowercase())
    }

    @Test
    fun assistantChunksAreAccumulatedAndFinished() {
        val state = SharedChatState.initial()
            .reduce(SharedChatEvent.AssistantResponseStarted)
            .reduce(SharedChatEvent.AssistantChunkReceived("Hel"))
            .reduce(SharedChatEvent.AssistantChunkReceived("lo"))

        assertTrue(state.messages.last().isStreaming)
        assertEquals("Hello", state.messages.last().text)

        val finished = state.reduce(SharedChatEvent.AssistantResponseFinished)
        assertFalse(finished.messages.last().isStreaming)
    }

    @Test
    fun assistantFailureIsVisibleAndEndsStreaming() {
        val state = SharedChatState.initial()
            .reduce(SharedChatEvent.AssistantResponseStarted)
            .reduce(SharedChatEvent.AssistantResponseFailed("network error"))

        assertEquals("network error", state.messages.last().text)
        assertFalse(state.messages.last().isStreaming)
    }

    @Test
    fun rawStreamAdapterHandlesFinalMarker() {
        val state = SharedChatState.initial()
            .reduce(SharedChatEvent.AssistantResponseStarted)
            .reduceRawStreamChunk("Hel")
            .reduceRawStreamChunk(InferenceStreamProtocol.encodeFinal("lo"))

        assertEquals("Hello", state.messages.last().text)
        assertFalse(state.messages.last().isStreaming)
    }

    @Test
    fun rawStreamAdapterPreservesThinkingToolsAndTps() {
        val state = SharedChatState.initial()
            .reduce(SharedChatEvent.AssistantResponseStarted)
            .reduceRawStreamChunk(InferenceStreamProtocol.encodeThinkChunk("plan"))
            .reduceRawStreamChunk(InferenceStreamProtocol.encodeToolCallChunk(listOf("search")))
            .reduceRawStreamChunk(InferenceStreamProtocol.encodeTps(12.5f))

        assertEquals("plan", state.thinkingText)
        assertEquals(listOf("search"), state.activeToolNames)
        assertEquals(12.5f, state.generationTps)
    }
}
