package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    
    @Insert
    suspend fun insert(session: ChatSessionEntity): Long
    
    @Update
    suspend fun update(session: ChatSessionEntity)
    
    @Delete
    suspend fun delete(session: ChatSessionEntity)
    
    @Query("SELECT * FROM chat_session ORDER BY lastUpdated DESC")
    fun getAllSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_session ORDER BY lastUpdated DESC")
    suspend fun getAllSessions(): List<ChatSessionEntity>
    
    @Query("SELECT * FROM chat_session WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_session ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getLatestSession(): ChatSessionEntity?
    
    @Query("DELETE FROM chat_session WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("UPDATE chat_session SET selectedModel = :newPath WHERE selectedModel = :oldPath")
    suspend fun updateSelectedModelPath(oldPath: String, newPath: String)
    
    @Query("SELECT COUNT(*) FROM chat_session")
    suspend fun getSessionCount(): Int
    
    @Query("DELETE FROM chat_session WHERE id IN (SELECT id FROM chat_session ORDER BY lastUpdated ASC LIMIT :limit)")
    suspend fun deleteOldestSessions(limit: Int)
}
