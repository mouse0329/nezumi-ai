package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.data.database.entity.ChatSessionEntity

data class SharedSessionSummary(
    val id: Long,
    val name: String,
    val lastUpdated: Long,
    val isPinned: Boolean,
    val isIncognito: Boolean
)

fun ChatSessionEntity.toSharedSessionSummary(): SharedSessionSummary =
    SharedSessionSummary(
        id = id,
        name = name,
        lastUpdated = lastUpdated,
        isPinned = isPinned,
        isIncognito = isIncognito
    )

fun Iterable<ChatSessionEntity>.toSharedSessionSummaries(): List<SharedSessionSummary> =
    map(ChatSessionEntity::toSharedSessionSummary)
