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
        thinkingContent: String? = null,
        imageUri: String? = null,
        imageDescription: String? = null,  // Phase 12: 画像説明
        audioUri: String? = null,
        isStreaming: Boolean = false
    ): Long {
        val message = MessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            thinkingContent = thinkingContent,
            imageUri = imageUri,
            imageDescription = imageDescription,  // Phase 12: 画像説明を保存
            audioUri = audioUri,
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
        isStreaming: Boolean,
        thinkingContent: String? = null,
        toolResultsJson: String? = null
    ) {
        val current = dao.getMessageById(messageId) ?: return
        dao.update(
            current.copy(
                content = content,
                thinkingContent = thinkingContent,
                isStreaming = isStreaming,
                toolResultsJson = toolResultsJson ?: current.toolResultsJson
            )
        )
    }

    suspend fun updateMessageMedia(
        messageId: Long,
        imageUri: String? = null,
        audioUri: String? = null
    ) {
        val current = dao.getMessageById(messageId) ?: return
        dao.update(current.copy(
            imageUri = imageUri ?: current.imageUri,
            audioUri = audioUri ?: current.audioUri
        ))
    }
    
    /**
     * Phase 13: 画像と説明文の整合性を保つ更新
     * imageUri が null/空 になった場合、imageDescription も自動的に削除
     */
    suspend fun updateMessageImageWithDescription(
        messageId: Long,
        imageUri: String?,
        imageDescription: String? = null
    ) {
        val current = dao.getMessageById(messageId) ?: return
        val finalDescription = if (imageUri.isNullOrEmpty()) {
            null  // 画像が削除されたら説明文も削除
        } else {
            imageDescription ?: current.imageDescription  // 新しい説明文があれば更新、なければ既存を保持
        }
        dao.update(current.copy(
            imageUri = imageUri,
            imageDescription = finalDescription
        ))
    }

    suspend fun hasMediaContent(messageId: Long): Boolean {
        val message = dao.getMessageById(messageId) ?: return false
        return message.imageUri != null || message.audioUri != null
    }

    suspend fun getMessageById(messageId: Long): MessageEntity? =
        dao.getMessageById(messageId)
    
    /**
     * Phase 13: アプリ起動時に isStreaming フラグをクリーニング
     * 前回のアプリ実行時に isStreaming = true のまま終了した場合、
     * それらを false に修正する（ゾンビストリーミング状態を防止）
     */
    suspend fun cleanupStreamingFlags(): Int {
        val allMessages = dao.getAllMessages()
        var fixedCount = 0
        
        for (msg in allMessages) {
            if (msg.isStreaming) {
                dao.update(msg.copy(isStreaming = false))
                fixedCount++
            }
        }
        
        if (fixedCount > 0) {
            android.util.Log.w("MessageRepository", "STARTUP_CLEANUP: Fixed $fixedCount messages with isStreaming=true -> false")
        }
        
        return fixedCount
    }
}
