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
    val toolCallingEnabled: Boolean = false,
    /**
     * プリセット一覧でのユーザー並び順・タグ付け用ソート順。
     * 小さいほど上に表示される。高値デフォルトで「未設定」を示し、
     * 未設定同士は従来と同じ created_at でソートされる。
     */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Long = Long.MAX_VALUE,
    /**
     * タグやグループ名を CSV で保持するシンプルな仕分け。
     * ひとまず「名前検索 + 並び替え + タグフィルタ」のためのストレージ。
     */
    @ColumnInfo(name = "tags_csv")
    val tagsCsv: String = "",
    /**
     * このプリセットに紐付く MCP サーバー ID の JSON 配列。
     * `[]` (デフォルト) は MCP を使わないことを意味する。
     */
    @ColumnInfo(name = "mcp_server_ids")
    val mcpServerIds: String = "[]",
    @ColumnInfo(name = "skills_enabled")
    val skillsEnabled: Boolean = false,
    @ColumnInfo(name = "hidden_skill_names")
    val hiddenSkillNames: String = "[]"
)
