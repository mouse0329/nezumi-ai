package com.nezumi_ai.shared.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val content: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUris: List<String> = emptyList()
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class ModelConfig(
    val modelPath: String,
    val modelType: ModelType,
    val nCtx: Int = 2048,
    val nThreads: Int = 4,
    val nGpuLayers: Int = 0,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40
)

@Serializable
enum class ModelType {
    GGUF,
    LITERT,
    UNKNOWN
}

@Serializable
data class InferenceConfig(
    val maxTokens: Int = 512,
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val stopWords: List<String> = emptyList()
)
