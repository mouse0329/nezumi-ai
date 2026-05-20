package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("SELECT * FROM memory WHERE is_deleted = 0 ORDER BY updated_at DESC")
    fun observeActive(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memory WHERE is_deleted = 0")
    suspend fun getActive(): List<MemoryEntity>

    @Query("SELECT * FROM memory WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MemoryEntity?

    @Query("UPDATE memory SET access_count = access_count + 1, last_accessed_at = :accessedAt WHERE id IN (:ids)")
    suspend fun markAccessed(ids: List<Long>, accessedAt: Long = System.currentTimeMillis())

    @Query("UPDATE memory SET is_deleted = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun softDelete(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE memory SET is_deleted = 1, updated_at = :updatedAt WHERE is_deleted = 0")
    suspend fun softDeleteAll(updatedAt: Long = System.currentTimeMillis())
}
