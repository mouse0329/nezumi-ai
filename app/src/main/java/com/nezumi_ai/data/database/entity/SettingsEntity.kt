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
    val preloadMemoryWarningThresholdPercent: Int = 60,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val maxTopK: Int = 40,
    val maxTokens: Int = 2048,
    val resourceMonitorEnabled: Boolean = false,
    /** Gemma 4 向け: プロンプト先頭に `<|think|>` を付与してシンキング生成を有効化。デフォルトはオフ */
    val gemmaThinkingEnabled: Boolean = false,
    /** LiteRT-LM 投機的デコーディング有効化（推論高速化。デフォルトはオフ） */
    val speculativeDecodingEnabled: Boolean = false,
    val systemPrompt: String = "",
    val userName: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    // llama.cpp settings
    val llamaCppThreads: Int = 4,
    val llamaCppGpuLayers: Int = 0,
    val llamaCppBatchSize: Int = 512,
    val llamaCppUBatchSize: Int = 512,
    val llamaCppKvUnified: Boolean = true,
    val llamaCppNKeep: Int = 0,
    val llamaCppRopeFreqBase: Float = 0.0f,
    val llamaCppRopeFreqScale: Float = 1.0f,
    // Memory save mode: LLM extraction or rule-based extraction
    val memorySaveMode: String = "LLM",
    // Per-model custom stop tokens (path=token1,token2;path2=token3)
    val stopTokensMap: String = "",
    // Session persistence
    val currentSessionId: Long = -1,
    // Chat history settings
    val chatHistoryLimit: Int = 30, // 10, 30, 50, or -1 for unlimited
    // Performance optimization settings
    val mtpEnabled: Boolean = false, // Multi-Token Prediction (投機的デコーディング)
    val mtpDraftTokens: Int = 5, // MTP draft token count (1-16)
    val flashAttentionEnabled: Boolean = true, // Flash Attention (自動検出)
    val dynamicBatchSizeEnabled: Boolean = true, // 動的バッチサイズ調整
    val promptBatchSize: Int = 512, // プロンプト処理用バッチサイズ
    val generationBatchSize: Int = 128, // トークン生成用バッチサイズ
    val kvCacheOptimizationEnabled: Boolean = true, // KVキャッシュ最適化
    val contextShiftEnabled: Boolean = true // コンテキストシフト有効化
)
