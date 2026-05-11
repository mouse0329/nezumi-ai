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
    val imageDescription: String? = null,
    val audioUri: String? = null,
    val timestamp: Long,
    val isStreaming: Boolean = false,
    /** ツール実行結果のJSON配列 e.g. [{"toolName":"setalarm","success":true,"payload":{...}}] */
    val toolResultsJson: String? = null,
    /** 生成速度 (tokens/sec)。生成完了後に保存。null = 未計測 */
    val generationTps: Float? = null,
    /** 最初のトークン生成後から完了までの生成時間(ms)。null = 未計測 */
    val generationTimeMs: Long? = null
)
