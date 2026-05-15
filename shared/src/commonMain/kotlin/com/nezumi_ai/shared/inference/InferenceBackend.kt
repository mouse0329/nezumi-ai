package com.nezumi_ai.shared.inference

import kotlinx.coroutines.flow.Flow

interface InferenceBackend {
    suspend fun generate(prompt: String): Flow<String>
    suspend fun load(modelPath: String)
    fun unload()
}