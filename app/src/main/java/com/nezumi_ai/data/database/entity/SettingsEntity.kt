package com.nezumi_ai.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1, // Single row table
    val selectedModel: String = "E2B", // E2B or E4B
    val backendType: String = "CPU", // GPU or CPU
    val autoFallback: Boolean = true,
    val contextWindow: Int = 4096, // Legacy: Use contextWindowMap instead
    val contextWindowMap: String = "", // E2B=4096;E4B=4096;IMPORTED=4096
    val contextCompressionEnabled: Boolean = false,
    val contextCompressionThresholdPercent: Int = 70,
    val temperature: Float = 0.7f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 2048,
    val resourceMonitorEnabled: Boolean = false,
    /** Gemma 4 向け: プロンプト先頭に `<|think|>` を付与してシンキング生成を有効化。デフォルトはオフ */
    val gemmaThinkingEnabled: Boolean = false,
    /** LiteRT-LM 投機的デコーディング有効化（推論高速化。デフォルトはオフ） */
    val speculativeDecodingEnabled: Boolean = false,
    val systemPrompt: String = "",
    val userName: String = "",
    val lastModified: Long = System.currentTimeMillis()
)
