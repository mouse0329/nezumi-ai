package com.nezumi_ai.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "memory_session")
data class MemorySessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "last_extracted_turn")
    val lastExtractedTurn: Int = 0,
    @ColumnInfo(name = "pending_extraction")
    val pendingExtraction: Boolean = false
)
