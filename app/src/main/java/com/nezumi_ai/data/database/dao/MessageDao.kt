package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Update
    suspend fun update(message: MessageEntity)
    
    @Delete
    suspend fun delete(message: MessageEntity)
    
    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSessionFlow(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<MessageEntity>
    
    @Query("SELECT * FROM message WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMessageInSession(sessionId: Long): MessageEntity?
    
    @Query("DELETE FROM message WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: Long)

    @Query("SELECT * FROM message WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("DELETE FROM message WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)
    
    @Query("SELECT * FROM message ORDER BY timestamp DESC")
    suspend fun getAllMessages(): List<MessageEntity>
}
