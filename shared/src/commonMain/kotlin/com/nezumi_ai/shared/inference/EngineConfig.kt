package com.nezumi_ai.shared.inference

data class EngineConfig(
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.1f,
    val contextLength: Int = 4096
)