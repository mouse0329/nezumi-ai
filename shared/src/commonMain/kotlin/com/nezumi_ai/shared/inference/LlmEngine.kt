package com.nezumi_ai.shared.inference

import com.nezumi_ai.shared.model.InferenceConfig
import kotlinx.coroutines.flow.Flow

interface LlmEngine {
    suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean
    fun generate(prompt: String, config: InferenceConfig): Flow<String>
    fun interrupt()
    fun release()
    fun isInitialized(): Boolean
}

expect class PlatformLlmEngine() : LlmEngine
