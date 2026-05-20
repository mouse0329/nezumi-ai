package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.MemorySessionDao
import com.nezumi_ai.data.database.entity.MemorySessionEntity

class MemorySessionRepository(
    private val dao: MemorySessionDao
) {
    suspend fun getOrCreate(sessionId: String): MemorySessionEntity {
        return dao.getById(sessionId) ?: MemorySessionEntity(id = sessionId).also {
            dao.insert(it)
        }
    }

    suspend fun markPending(sessionId: String, pending: Boolean = true) {
        getOrCreate(sessionId)
        dao.updatePending(sessionId, pending)
    }

    suspend fun markExtracted(sessionId: String, extractedTurn: Int) {
        val current = getOrCreate(sessionId)
        dao.update(
            current.copy(
                lastExtractedTurn = extractedTurn.coerceAtLeast(0),
                pendingExtraction = false
            )
        )
    }

    suspend fun getPendingSessions(): List<MemorySessionEntity> = dao.getPendingSessions()
}
