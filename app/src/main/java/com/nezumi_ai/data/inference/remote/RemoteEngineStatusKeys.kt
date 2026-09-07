package com.nezumi_ai.data.inference.remote

/**
 * [IRemoteInferenceEngine.getEngineStatus] が返す Bundle のキー定義。
 * メインプロセス側アダプタとサービス側 Stub の両方で使うため1箇所に集約する。
 */
object RemoteEngineStatusKeys {
    /** GGUF: 要求された GPU バックエンドが使えず CPU へフォールバックしたか */
    const val KEY_GPU_FALLBACK_OCCURRED = "gpuBackendFallbackOccurred"

    /** GGUF: 実際に使用されたバックエンド ("CPU" / "OPENCL" / "VULKAN") */
    const val KEY_ACTUAL_GPU_BACKEND = "actualGpuBackend"

    /** GGUF: 現在のコンテキストに GGUF chat template が適用済みか */
    const val KEY_HAS_GGUF_CHAT_TEMPLATE = "hasGgufChatTemplate"

    /** LiteRT-LM: 現在ロード済みのバックエンド ("GPU" / "CPU" / "NPU"、未ロード時は null) */
    const val KEY_LOADED_BACKEND = "loadedBackend"
}
