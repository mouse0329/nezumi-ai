package com.nezumi_ai.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory",
    indices = [
        Index(value = ["is_deleted"]),
        Index(value = ["session_id"])
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String,
    val embedding: ByteArray,
    val norm: Float,
    val importance: Float = 0.7f,
    @ColumnInfo(name = "access_count")
    val accessCount: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "last_accessed_at")
    val lastAccessedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    val source: String = SOURCE_EXTRACTED,
    @ColumnInfo(name = "session_id")
    val sessionId: String = "",
    @ColumnInfo(name = "rga_uid")
    val rgaUid: String = java.util.UUID.randomUUID().toString(),
    @ColumnInfo(name = "rga_prev_uid")
    val rgaPrevUid: String? = null
) {
    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_ASSISTANT = "assistant"
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_EXTRACTED = "extracted"
    }
}
