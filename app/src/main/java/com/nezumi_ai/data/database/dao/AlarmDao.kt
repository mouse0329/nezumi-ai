package com.nezumi_ai.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.nezumi_ai.data.database.entity.AlarmEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmDao {
    
    @Insert
    suspend fun insert(alarm: AlarmEntity): Long

    @Update
    suspend fun update(alarm: AlarmEntity)
    
    @Delete
    suspend fun delete(alarm: AlarmEntity)
    
    @Query("SELECT * FROM alarm WHERE isEnabled = 1 ORDER BY triggerTime ASC")
    fun getEnabledAlarmsFlow(): Flow<List<AlarmEntity>>

    @Query("SELECT * FROM alarm WHERE isEnabled = 1 ORDER BY triggerTime ASC")
    suspend fun getEnabledAlarms(): List<AlarmEntity>
    
    @Query("SELECT * FROM alarm ORDER BY triggerTime DESC")
    suspend fun getAllAlarms(): List<AlarmEntity>
    
    @Query("SELECT * FROM alarm ORDER BY triggerTime DESC")
    fun getAll(): Flow<List<AlarmEntity>>
    
    @Query("SELECT * FROM alarm ORDER BY triggerTime DESC")
    fun observeAll(): Flow<List<AlarmEntity>>
    
    @Query("SELECT * FROM alarm WHERE id = :alarmId LIMIT 1")
    suspend fun getAlarmById(alarmId: Long): AlarmEntity?

    @Query("DELETE FROM alarm WHERE id = :alarmId")
    suspend fun deleteById(alarmId: Long)
    
    @Query("UPDATE alarm SET isEnabled = :enabled WHERE id = :alarmId")
    suspend fun setAlarmEnabled(alarmId: Long, enabled: Boolean)
    
    @Query("DELETE FROM alarm")
    suspend fun deleteAll()
}
