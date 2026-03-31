package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

class ChatSessionRepository(private val dao: ChatSessionDao) {
    
    fun getAllSessions(): Flow<List<ChatSessionEntity>> = dao.getAllSessionsFlow()
    
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity? =
        dao.getSessionById(sessionId)
    
    suspend fun createSession(name: String): Long {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            name = name,
            createDate = now,
            lastUpdated = now,
            selectedModel = "E2B"
        )
        return dao.insert(session)
    }
    
    suspend fun updateSessionLastUpdated(sessionId: Long) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(lastUpdated = System.currentTimeMillis()))
    }
    
    suspend fun deleteSession(sessionId: Long) {
        dao.deleteById(sessionId)
    }
    
    suspend fun updateSessionModel(sessionId: Long, model: String) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(selectedModel = model))
    }

    suspend fun updateSessionName(sessionId: Long, name: String) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(name = name, lastUpdated = System.currentTimeMillis()))
    }
}
