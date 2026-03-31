package com.nezumi_ai.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_session")
data class ChatSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createDate: Long,
    val lastUpdated: Long,
    val selectedModel: String = "E2B" // E2B or E4B
)
