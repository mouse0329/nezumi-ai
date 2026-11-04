package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.MemorySessionEntity

@Dao
interface MemorySessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: MemorySessionEntity)

    @Update
    suspend fun update(session: MemorySessionEntity)

    @Query("SELECT * FROM memory_session WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemorySessionEntity?

    @Query("SELECT * FROM memory_session WHERE pending_extraction = 1")
    suspend fun getPendingSessions(): List<MemorySessionEntity>

    @Query("UPDATE memory_session SET pending_extraction = :pending WHERE id = :id")
    suspend fun updatePending(id: String, pending: Boolean)
}
