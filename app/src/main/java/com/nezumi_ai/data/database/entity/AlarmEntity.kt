package com.nezumi_ai.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alarm")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String? = null,
    val triggerTime: Long, // Unix timestamp in milliseconds
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val hour: Int = 0,
    val minute: Int = 0,
    val label: String = ""
)
