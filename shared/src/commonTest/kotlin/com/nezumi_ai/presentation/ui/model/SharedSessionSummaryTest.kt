package com.nezumi_ai.presentation.ui.model

import com.nezumi_ai.data.database.entity.ChatSessionEntity
import kotlin.test.Test
import kotlin.test.assertEquals

class SharedSessionSummaryTest {
    @Test
    fun entityMapsToUiSummaryWithoutDatabaseDetails() {
        val entity = ChatSessionEntity(
            id = 7,
            name = "Test",
            createDate = 1L,
            lastUpdated = 2L,
            selectedModel = "model",
            isIncognito = true,
            isPinned = true,
            lastKnownContextTokens = 100
        )

        assertEquals(
            SharedSessionSummary(
                id = 7,
                name = "Test",
                lastUpdated = 2L,
                isPinned = true,
                isIncognito = true
            ),
            entity.toSharedSessionSummary()
        )
    }
}
