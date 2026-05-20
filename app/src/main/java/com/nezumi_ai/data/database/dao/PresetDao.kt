package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.PresetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: PresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(presets: List<PresetEntity>)

    @Update
    suspend fun update(preset: PresetEntity)

    @Delete
    suspend fun delete(preset: PresetEntity)

    @Query("SELECT * FROM preset ORDER BY is_default DESC, is_locked ASC, created_at ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM preset ORDER BY is_default DESC, is_locked ASC, created_at ASC")
    suspend fun getAll(): List<PresetEntity>

    @Query("SELECT * FROM preset WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PresetEntity?

    @Query("SELECT * FROM preset WHERE is_default = 1 LIMIT 1")
    suspend fun getDefault(): PresetEntity?

    @Query("SELECT COUNT(*) FROM preset")
    suspend fun count(): Int

    @Query("DELETE FROM preset WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
