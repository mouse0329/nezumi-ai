package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.MessageDao
import com.nezumi_ai.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val dao: MessageDao) {
    
    fun getMessagesForSession(sessionId: Long): Flow<List<MessageEntity>> =
        dao.getMessagesForSessionFlow(sessionId)

    suspend fun getMessagesForSessionOnce(sessionId: Long): List<MessageEntity> =
        dao.getMessagesForSession(sessionId)
    
    suspend fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        imageUri: String? = null,
        isStreaming: Boolean = false
    ): Long {
        val message = MessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            imageUri = imageUri,
            timestamp = System.currentTimeMillis(),
            isStreaming = isStreaming
        )
        return dao.insert(message)
    }
    
    suspend fun getLastMessage(sessionId: Long): MessageEntity? =
        dao.getLastMessageInSession(sessionId)
    
    suspend fun deleteAllMessagesInSession(sessionId: Long) {
        dao.deleteBySessionId(sessionId)
    }

    suspend fun deleteMessageById(messageId: Long) {
        dao.deleteById(messageId)
    }

    suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        isStreaming: Boolean
    ) {
        val current = dao.getMessageById(messageId) ?: return
        dao.update(current.copy(content = content, isStreaming = isStreaming))
    }
}
