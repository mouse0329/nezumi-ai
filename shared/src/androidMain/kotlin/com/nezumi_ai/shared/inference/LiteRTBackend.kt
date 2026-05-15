package com.nezumi_ai.shared.inference

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

actual class LiteRTBackend actual constructor(private val config: EngineConfig) : InferenceBackend {

    // TODO: litertlm-android を使用した実装
    // 実際のライブラリが利用可能になったら実装する

    override suspend fun generate(prompt: String): Flow<String> = flow {
        // 仮実装
        emit("LiteRT response for: $prompt")
    }

    override suspend fun load(modelPath: String) {
        // TODO: モデルロード実装
    }

    override fun unload() {
        // TODO: モデルアンロード実装
    }
}