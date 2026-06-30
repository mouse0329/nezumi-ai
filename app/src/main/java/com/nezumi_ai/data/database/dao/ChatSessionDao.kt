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
    
    @Query("SELECT * FROM chat_session WHERE isIncognito = 0 ORDER BY isPinned DESC, lastUpdated DESC")
    fun getAllSessionsFlow(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_session WHERE isIncognito = 0 ORDER BY isPinned DESC, lastUpdated DESC")
    suspend fun getAllSessions(): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_session ORDER BY lastUpdated DESC")
    suspend fun getAllSessionsIncludingIncognito(): List<ChatSessionEntity>
    
    @Query("SELECT * FROM chat_session WHERE id = :sessionId")
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity?

    @Query("SELECT * FROM chat_session WHERE isIncognito = 0 ORDER BY lastUpdated DESC LIMIT 1")
    suspend fun getLatestSession(): ChatSessionEntity?
    
    @Query("DELETE FROM chat_session WHERE id = :sessionId")
    suspend fun deleteById(sessionId: Long)

    @Query("UPDATE chat_session SET selectedModel = :newPath WHERE selectedModel = :oldPath")
    suspend fun updateSelectedModelPath(oldPath: String, newPath: String)
    
    @Query("SELECT COUNT(*) FROM chat_session")
    suspend fun getSessionCount(): Int

    /**
     * ピン留め・incognito を除いた「通常保存対象」セッション数を返す。
     * Bug fix: 最大保存数はピン留め・incognito を除いたセッションに適用されるべき。
     */
    @Query("SELECT COUNT(*) FROM chat_session WHERE isIncognito = 0 AND isPinned = 0")
    suspend fun getRegularSessionCount(): Int

    /**
     * 古い順のセッションを削除する。
     * Bug fix: ピン留め・incognito を対象外とし、 lastUpdated 昇順で古いものから削除する。
     */
    @Query("DELETE FROM chat_session WHERE id IN (SELECT id FROM chat_session WHERE isIncognito = 0 AND isPinned = 0 ORDER BY lastUpdated ASC LIMIT :limit)")
    suspend fun deleteOldestSessions(limit: Int)
}
