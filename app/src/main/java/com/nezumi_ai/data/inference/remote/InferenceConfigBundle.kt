package com.nezumi_ai.data.inference.remote

import android.os.Bundle
import com.nezumi_ai.data.inference.InferenceConfig

/**
 * [InferenceConfig] ↔ [Bundle] 変換。
 *
 * AIDL 越しに推論設定を渡すための直列化。data class を Parcelable 化するより
 * 依存が浅く (Parcelable 生成コードを :gguf / :litert 両プロセスで共有できる)、
 * フィールド追加時の後方互換も取りやすいため Bundle を採用している。
 *
 * 変換は対称 (toBundle / fromBundle) をこの1ファイルに閉じ込める。
 * フィールドを追加したら必ず両方を更新すること。
 */
object InferenceConfigBundle {

    private const val K_CONTEXT_WINDOW = "contextWindow"
    private const val K_TEMPERATURE = "temperature"
    private const val K_MAX_TOP_K = "maxTopK"
    private const val K_MAX_TOKENS = "maxTokens"
    private const val K_TOP_P = "topP"
    private const val K_ENABLE_THINKING = "enableThinking"
    private const val K_ENABLE_SPECULATIVE_DECODING = "enableSpeculativeDecoding"
    private const val K_BACKEND_TYPE = "backendType"
    private const val K_REQUIRE_MULTIMODAL = "requireMultimodal"
    private const val K_LLAMA_CPP_THREADS = "llamaCppThreads"
    private const val K_LLAMA_CPP_GPU_LAYERS = "llamaCppGpuLayers"
    private const val K_LLAMA_CPP_GPU_BACKEND = "llamaCppGpuBackend"
    private const val K_LLAMA_CPP_BATCH_SIZE = "llamaCppBatchSize"
    private const val K_LLAMA_CPP_UBATCH_SIZE = "llamaCppUBatchSize"
    private const val K_LLAMA_CPP_KV_UNIFIED = "llamaCppKvUnified"
    private const val K_LLAMA_CPP_N_KEEP = "llamaCppNKeep"
    private const val K_LLAMA_CPP_ROPE_FREQ_BASE = "llamaCppRopeFreqBase"
    private const val K_LLAMA_CPP_ROPE_FREQ_SCALE = "llamaCppRopeFreqScale"
    private const val K_CUSTOM_STOP_TOKENS = "customStopTokens"
    private const val K_ENABLE_TOOL_CALLING = "enableToolCalling"
    private const val K_MTP_ENABLED = "mtpEnabled"
    private const val K_MTP_DRAFT_TOKENS = "mtpDraftTokens"
    private const val K_FLASH_ATTENTION_ENABLED = "flashAttentionEnabled"
    private const val K_DYNAMIC_BATCH_SIZE_ENABLED = "dynamicBatchSizeEnabled"
    private const val K_PROMPT_BATCH_SIZE = "promptBatchSize"
    private const val K_GENERATION_BATCH_SIZE = "generationBatchSize"
    private const val K_KV_CACHE_OPTIMIZATION_ENABLED = "kvCacheOptimizationEnabled"
    private const val K_CONTEXT_SHIFT_ENABLED = "contextShiftEnabled"

    fun toBundle(config: InferenceConfig): Bundle = Bundle().apply {
        putInt(K_CONTEXT_WINDOW, config.contextWindow)
        putFloat(K_TEMPERATURE, config.temperature)
        putInt(K_MAX_TOP_K, config.maxTopK)
        putInt(K_MAX_TOKENS, config.maxTokens)
        putFloat(K_TOP_P, config.topP)
        putBoolean(K_ENABLE_THINKING, config.enableThinking)
        putBoolean(K_ENABLE_SPECULATIVE_DECODING, config.enableSpeculativeDecoding)
        putString(K_BACKEND_TYPE, config.backendType)
        putBoolean(K_REQUIRE_MULTIMODAL, config.requireMultimodal)
        putInt(K_LLAMA_CPP_THREADS, config.llamaCppThreads)
        putInt(K_LLAMA_CPP_GPU_LAYERS, config.llamaCppGpuLayers)
        putString(K_LLAMA_CPP_GPU_BACKEND, config.llamaCppGpuBackend)
        putInt(K_LLAMA_CPP_BATCH_SIZE, config.llamaCppBatchSize)
        putInt(K_LLAMA_CPP_UBATCH_SIZE, config.llamaCppUBatchSize)
        putBoolean(K_LLAMA_CPP_KV_UNIFIED, config.llamaCppKvUnified)
        putInt(K_LLAMA_CPP_N_KEEP, config.llamaCppNKeep)
        putFloat(K_LLAMA_CPP_ROPE_FREQ_BASE, config.llamaCppRopeFreqBase)
        putFloat(K_LLAMA_CPP_ROPE_FREQ_SCALE, config.llamaCppRopeFreqScale)
        putStringArrayList(K_CUSTOM_STOP_TOKENS, ArrayList(config.customStopTokens))
        putBoolean(K_ENABLE_TOOL_CALLING, config.enableToolCalling)
        putBoolean(K_MTP_ENABLED, config.mtpEnabled)
        putInt(K_MTP_DRAFT_TOKENS, config.mtpDraftTokens)
        putBoolean(K_FLASH_ATTENTION_ENABLED, config.flashAttentionEnabled)
        putBoolean(K_DYNAMIC_BATCH_SIZE_ENABLED, config.dynamicBatchSizeEnabled)
        putInt(K_PROMPT_BATCH_SIZE, config.promptBatchSize)
        putInt(K_GENERATION_BATCH_SIZE, config.generationBatchSize)
        putBoolean(K_KV_CACHE_OPTIMIZATION_ENABLED, config.kvCacheOptimizationEnabled)
        putBoolean(K_CONTEXT_SHIFT_ENABLED, config.contextShiftEnabled)
    }

    fun fromBundle(bundle: Bundle?): InferenceConfig {
        if (bundle == null) return InferenceConfig()
        val default = InferenceConfig()
        return InferenceConfig(
            contextWindow = bundle.getInt(K_CONTEXT_WINDOW, default.contextWindow),
            temperature = bundle.getFloat(K_TEMPERATURE, default.temperature),
            maxTopK = bundle.getInt(K_MAX_TOP_K, default.maxTopK),
            maxTokens = bundle.getInt(K_MAX_TOKENS, default.maxTokens),
            topP = bundle.getFloat(K_TOP_P, default.topP),
            enableThinking = bundle.getBoolean(K_ENABLE_THINKING, default.enableThinking),
            enableSpeculativeDecoding = bundle.getBoolean(
                K_ENABLE_SPECULATIVE_DECODING, default.enableSpeculativeDecoding
            ),
            backendType = bundle.getString(K_BACKEND_TYPE, default.backendType),
            requireMultimodal = bundle.getBoolean(K_REQUIRE_MULTIMODAL, default.requireMultimodal),
            llamaCppThreads = bundle.getInt(K_LLAMA_CPP_THREADS, default.llamaCppThreads),
            llamaCppGpuLayers = bundle.getInt(K_LLAMA_CPP_GPU_LAYERS, default.llamaCppGpuLayers),
            llamaCppGpuBackend = bundle.getString(
                K_LLAMA_CPP_GPU_BACKEND, default.llamaCppGpuBackend
            ),
            llamaCppBatchSize = bundle.getInt(K_LLAMA_CPP_BATCH_SIZE, default.llamaCppBatchSize),
            llamaCppUBatchSize = bundle.getInt(K_LLAMA_CPP_UBATCH_SIZE, default.llamaCppUBatchSize),
            llamaCppKvUnified = bundle.getBoolean(K_LLAMA_CPP_KV_UNIFIED, default.llamaCppKvUnified),
            llamaCppNKeep = bundle.getInt(K_LLAMA_CPP_N_KEEP, default.llamaCppNKeep),
            llamaCppRopeFreqBase = bundle.getFloat(
                K_LLAMA_CPP_ROPE_FREQ_BASE, default.llamaCppRopeFreqBase
            ),
            llamaCppRopeFreqScale = bundle.getFloat(
                K_LLAMA_CPP_ROPE_FREQ_SCALE, default.llamaCppRopeFreqScale
            ),
            customStopTokens = bundle.getStringArrayList(K_CUSTOM_STOP_TOKENS)?.toList()
                ?: default.customStopTokens,
            enableToolCalling = bundle.getBoolean(K_ENABLE_TOOL_CALLING, default.enableToolCalling),
            mtpEnabled = bundle.getBoolean(K_MTP_ENABLED, default.mtpEnabled),
            mtpDraftTokens = bundle.getInt(K_MTP_DRAFT_TOKENS, default.mtpDraftTokens),
            flashAttentionEnabled = bundle.getBoolean(
                K_FLASH_ATTENTION_ENABLED, default.flashAttentionEnabled
            ),
            dynamicBatchSizeEnabled = bundle.getBoolean(
                K_DYNAMIC_BATCH_SIZE_ENABLED, default.dynamicBatchSizeEnabled
            ),
            promptBatchSize = bundle.getInt(K_PROMPT_BATCH_SIZE, default.promptBatchSize),
            generationBatchSize = bundle.getInt(K_GENERATION_BATCH_SIZE, default.generationBatchSize),
            kvCacheOptimizationEnabled = bundle.getBoolean(
                K_KV_CACHE_OPTIMIZATION_ENABLED, default.kvCacheOptimizationEnabled
            ),
            contextShiftEnabled = bundle.getBoolean(
                K_CONTEXT_SHIFT_ENABLED, default.contextShiftEnabled
            )
        )
    }
}
