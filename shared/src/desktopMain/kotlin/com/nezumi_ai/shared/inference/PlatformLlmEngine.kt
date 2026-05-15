package com.nezumi_ai.shared.inference

import com.nezumi_ai.shared.model.InferenceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class PlatformLlmEngine : LlmEngine {
    private var initialized = false
    
    override suspend fun initialize(modelPath: String, config: InferenceConfig): Boolean {
        // Desktop版の実装（llama.cpp JNA）
        // 既存のDesktop実装を呼び出す
        initialized = true
        return true
    }
    
    override fun generate(prompt: String, config: InferenceConfig): Flow<String> = flow {
        // Desktop版の推論実装
        emit("Desktop implementation")
    }
    
    override fun interrupt() {
        // 中断処理
    }
    
    override fun release() {
        initialized = false
    }
    
    override fun isInitialized(): Boolean = initialized
}
