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

    /**
     * LiteRT-LM: 現在の会話の KV キャッシュ内トークン数 (prefill + decode)。
     * 画像・音声を含む実測値。会話未生成・未取得時は -1。
     */
    const val KEY_CONVERSATION_TOKEN_COUNT = "conversationTokenCount"

    /** LiteRT-LM: 直近推論の実測ベンチマーク (未推論時はいずれも -1) */
    const val KEY_LAST_PREFILL_TOKENS = "lastPrefillTokens"
    const val KEY_LAST_DECODE_TOKENS = "lastDecodeTokens"
    const val KEY_LAST_DECODE_TPS = "lastDecodeTokensPerSecond"
    const val KEY_LAST_TTFT_MS = "lastTtftMs"
}
