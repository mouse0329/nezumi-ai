package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    
    @Insert
    suspend fun insert(settings: SettingsEntity)
    
    @Update
    suspend fun update(settings: SettingsEntity)
    
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    fun getSettingsFlow(): Flow<SettingsEntity?>
    
    @Query("SELECT * FROM settings WHERE id = 1 LIMIT 1")
    suspend fun getSettings(): SettingsEntity?
}
