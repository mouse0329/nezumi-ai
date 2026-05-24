package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nezumi_ai.data.database.entity.ChatChunkEntity

@Dao
interface ChatChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChatChunkEntity>)

    @Query("SELECT * FROM chat_chunk WHERE session_id = :sessionId")
    suspend fun getBySession(sessionId: Long): List<ChatChunkEntity>

    @Query("SELECT * FROM chat_chunk")
    suspend fun getAll(): List<ChatChunkEntity>

    @Query("SELECT * FROM chat_chunk WHERE message_id = :messageId")
    suspend fun getByMessage(messageId: Long): List<ChatChunkEntity>

    @Query("DELETE FROM chat_chunk WHERE message_id = :messageId")
    suspend fun deleteByMessage(messageId: Long)

    @Query("DELETE FROM chat_chunk WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: Long)

    @Query("SELECT COUNT(*) FROM chat_chunk WHERE message_id = :messageId")
    suspend fun countByMessage(messageId: Long): Int
}
