package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nezumi_ai.data.database.entity.ToolCallHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolCallHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ToolCallHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<ToolCallHistoryEntity>)

    @Query("SELECT * FROM tool_call_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<ToolCallHistoryEntity>>

    @Query("SELECT * FROM tool_call_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 500): List<ToolCallHistoryEntity>

    @Query("SELECT * FROM tool_call_history WHERE sessionId = :sessionId ORDER BY timestamp DESC")
    suspend fun getBySession(sessionId: Long): List<ToolCallHistoryEntity>

    @Query("SELECT * FROM tool_call_history WHERE toolName = :toolName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByTool(toolName: String, limit: Int = 200): List<ToolCallHistoryEntity>

    @Query("""
        SELECT * FROM tool_call_history
        WHERE toolName LIKE '%' || :q || '%'
           OR IFNULL(query, '') LIKE '%' || :q || '%'
           OR IFNULL(sessionName, '') LIKE '%' || :q || '%'
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun search(q: String, limit: Int = 200): List<ToolCallHistoryEntity>

    @Query("DELETE FROM tool_call_history")
    suspend fun clearAll()

    @Query("DELETE FROM tool_call_history WHERE timestamp < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM tool_call_history")
    suspend fun count(): Long
}
