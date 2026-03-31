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
    
    @Query("SELECT * FROM chat_session WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?
    
    @Query("DELETE FROM chat_session WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)
}
