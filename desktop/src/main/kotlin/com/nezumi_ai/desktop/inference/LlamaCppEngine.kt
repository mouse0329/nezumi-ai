package com.nezumi_ai.desktop.inference

import com.nezumi_ai.desktop.inference.jna.LlamaContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay

/**
 * llama.cpp統合エンジン
 * JNA経由でネイティブライブラリをロード
 */
class LlamaCppEngine {
    private var llamaContext: LlamaContext? = null
    private var isInitialized = false
    
    fun initialize(modelPath: String, nCtx: Int = 2048, nGpuLayers: Int = 0): Boolean {
        if (isInitialized) return true
        
        return try {
            println("Initializing llama.cpp with model: $modelPath")
            println("  nCtx: $nCtx, nGpuLayers: $nGpuLayers")
            
            // モデルファイルの存在確認
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                println("✗ Model file not found: $modelPath")
                return false
            }
            println("✓ Model file exists: ${modelFile.absolutePath}")
            println("  File size: ${modelFile.length() / 1024 / 1024} MB")
            
            llamaContext = LlamaContext(
                modelPath = modelPath,
                nCtx = nCtx,
                nGpuLayers = nGpuLayers
            )
            val success = llamaContext?.initialize() ?: false
            if (success) {
                isInitialized = true
                println("✓ llama.cpp initialized successfully")
            } else {
                println("✗ llama.cpp initialization failed")
            }
            success
        } catch (e: Exception) {
            println("Failed to initialize llama.cpp: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    fun generate(prompt: String): Flow<String> = flow {
        if (!isInitialized) {
            throw IllegalStateException("LlamaCppEngine is not initialized. Please load a model first.")
        }
        val ctxFlow = llamaContext?.generate(prompt)
            ?: throw IllegalStateException("LlamaContext is not available")
        
        ctxFlow.collect { chunk ->
            emit(chunk)
        }
    }
    
    fun interrupt() {
        llamaContext?.interrupt()
    }
    
    fun release() {
        llamaContext?.release()
        llamaContext = null
        isInitialized = false
    }
}
