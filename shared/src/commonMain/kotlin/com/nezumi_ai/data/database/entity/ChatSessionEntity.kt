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
    val selectedModel: String = "E2B", // E2B or E4B
    val isIncognito: Boolean = false,
    val isPinned: Boolean = false,
    /**
     * コンテキストメーター正確化: 最後に保存したコンテキスト使用量 (トークン数)。
     * エンジンの実測値 (llama.cpp n_past / LiteRT getTokenCount) を推論完了ごとに保存し、
     * アプリ再起動後の初回メーター表示に復元する。0 = 未計測。
     */
    val lastKnownContextTokens: Int = 0,
    /** lastKnownContextTokens のうち画像・音声由来のトークン数 (詳細表示用)。 */
    val lastKnownMediaTokens: Int = 0
)
