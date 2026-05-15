package com.nezumi_ai.shared.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class GGUFBackend actual constructor() : InferenceBackend {

    // TODO: sd.cpp JNI (Android ABI) を使用した実装

    override suspend fun generate(prompt: String): Flow<String> = flow {
        // 仮実装
        emit("GGUF Android response for: $prompt")
    }

    override suspend fun load(modelPath: String) {
        // TODO: モデルロード実装
    }

    override fun unload() {
        // TODO: モデルアンロード実装
    }
}