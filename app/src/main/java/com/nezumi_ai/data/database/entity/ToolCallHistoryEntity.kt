package com.nezumi_ai.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_call_history",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["sessionId"]),
        Index(value = ["toolName"]),
        Index(value = ["query"])
    ]
)
data class ToolCallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val sessionId: Long,
    val sessionName: String? = null,
    val toolName: String,
    val query: String? = null,
    val success: Boolean = true,
    val resultSummary: String? = null,
    val messageId: Long? = null
)
