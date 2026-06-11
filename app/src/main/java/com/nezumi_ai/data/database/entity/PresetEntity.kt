package com.nezumi_ai.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "preset")
data class PresetEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val icon: String,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String = "",
    @ColumnInfo(name = "model_id")
    val modelId: String,
    @ColumnInfo(name = "enabled_tools")
    val enabledTools: String = "[]",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,
    @ColumnInfo(name = "memory_enabled")
    val memoryEnabled: Boolean = true,
    val description: String = "",
    @ColumnInfo(name = "is_locked")
    val isLocked: Boolean = false,
    @ColumnInfo(name = "tool_calling_enabled")
    val toolCallingEnabled: Boolean = false
)
