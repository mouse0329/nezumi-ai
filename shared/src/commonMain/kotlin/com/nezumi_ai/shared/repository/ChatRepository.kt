package com.nezumi_ai.shared.repository

import com.nezumi_ai.shared.model.ChatMessage
import com.nezumi_ai.shared.model.ChatSession
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    suspend fun getAllSessions(): List<ChatSession>
    suspend fun getSession(sessionId: String): ChatSession?
    suspend fun createSession(session: ChatSession)
    suspend fun updateSession(session: ChatSession)
    suspend fun deleteSession(sessionId: String)
    
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    suspend fun insertMessage(message: ChatMessage)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteAllMessages(sessionId: String)
    
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
}
