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
    val contextWindow: Int = 4096,
    val contextCompressionEnabled: Boolean = false,
    val contextCompressionThresholdPercent: Int = 70,
    val temperature: Float = 0.7f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 512,
    val resourceMonitorEnabled: Boolean = false,
    /** Gemma 4 向け: プロンプト先頭に `<|think|>` を付与してシンキング生成を有効化 */
    val gemmaThinkingEnabled: Boolean = true,
    val systemPrompt: String = "",
    val lastModified: Long = System.currentTimeMillis()
)
