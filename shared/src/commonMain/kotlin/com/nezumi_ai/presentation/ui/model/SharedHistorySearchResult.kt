package com.nezumi_ai.presentation.ui.model

data class SharedHistorySearchResult(
    val sessionId: Long,
    val sessionName: String,
    val messageId: Long,
    val chunkId: Long,
    val chunkText: String,
    val score: Float
)
