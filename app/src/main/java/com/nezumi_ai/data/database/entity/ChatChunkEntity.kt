package com.nezumi_ai.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_chunk",
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["session_id"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ChatChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "message_id")
    val messageId: Long,
    @ColumnInfo(name = "session_id")
    val sessionId: Long,
    @ColumnInfo(name = "chunk_text")
    val chunkText: String,
    val embedding: ByteArray,
    val norm: Float,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
