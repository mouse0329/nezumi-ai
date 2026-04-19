package com.nezumi_ai.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "message",
    indices = [
        Index(value = ["sessionId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" or "assistant"
    val content: String,
    /** Gemma 4 シンキング（内部用のみ。プロンプト生成時には絶対に含めない） */
    val thinkingContent: String? = null,
    val imageUri: String? = null,
    val imageDescription: String? = null,  // Phase 12: 画像の説明（初回処理時に生成・保存。imageUri が空になった際は自動削除）
    val audioUri: String? = null,
    val timestamp: Long,
    val isStreaming: Boolean = false,  // Phase 13: 生成中フラグ（アプリ起動時に自動クリーニング）
    /** ツール実行結果のJSON配列 e.g. [{"toolName":"setalarm","success":true,"payload":{...}}] */
    val toolResultsJson: String? = null
)
