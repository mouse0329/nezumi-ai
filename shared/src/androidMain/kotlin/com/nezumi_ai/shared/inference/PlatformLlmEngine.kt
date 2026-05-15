package com.nezumi_ai.shared.inference

import com.nezumi_ai.shared.model.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class PlatformLlmEngine : LlmEngine {
    private var initialized = false
    
    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        // Android版の実装（LiteRT-LM または llama.cpp）
        // 既存のAndroid実装を呼び出す
        initialized = true
        return true
    }
    
    override fun generate(prompt: String, config: InferenceConfig): Flow<String> = flow {
        // Android版の推論実装
        emit("Android implementation")
    }
    
    override fun interrupt() {
        // 中断処理
    }
    
    override fun release() {
        initialized = false
    }
    
    override fun isInitialized(): Boolean = initialized
}
