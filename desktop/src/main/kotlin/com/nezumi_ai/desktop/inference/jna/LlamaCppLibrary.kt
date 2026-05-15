package com.nezumi_ai.desktop.inference.jna

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Callback
import com.sun.jna.Structure

/**
 * llama_model_params structure
 */
@Structure.FieldOrder("n_gpu_layers", "split_mode", "main_gpu", "tensor_split", "rpc_servers", "progress_callback", "progress_callback_user_data", "kv_overrides", "vocab_only", "use_mmap", "use_mlock", "check_tensors")
class LlamaModelParams : Structure() {
    @JvmField var n_gpu_layers: Int = 0
    @JvmField var split_mode: Int = 0
    @JvmField var main_gpu: Int = 0
    @JvmField var tensor_split: Pointer? = null
    @JvmField var rpc_servers: Pointer? = null
    @JvmField var progress_callback: Pointer? = null
    @JvmField var progress_callback_user_data: Pointer? = null
    @JvmField var kv_overrides: Pointer? = null
    @JvmField var vocab_only: Boolean = false
    @JvmField var use_mmap: Boolean = true
    @JvmField var use_mlock: Boolean = false
    @JvmField var check_tensors: Boolean = false
}

/**
 * llama_context_params structure
 */
@Structure.FieldOrder("n_ctx", "n_batch", "n_ubatch", "n_seq_max", "n_threads", "n_threads_batch", "rope_scaling_type", "pooling_type", "attention_type", "rope_freq_base", "rope_freq_scale", "yarn_ext_factor", "yarn_attn_factor", "yarn_beta_fast", "yarn_beta_slow", "yarn_orig_ctx", "defrag_thold", "cb_eval", "cb_eval_user_data", "type_k", "type_v", "logits_all", "embeddings", "offload_kqv", "flash_attn", "no_perf")
class LlamaContextParams : Structure() {
    @JvmField var n_ctx: Int = 512
    @JvmField var n_batch: Int = 2048
    @JvmField var n_ubatch: Int = 512
    @JvmField var n_seq_max: Int = 1
    @JvmField var n_threads: Int = 4
    @JvmField var n_threads_batch: Int = 4
    @JvmField var rope_scaling_type: Int = -1
    @JvmField var pooling_type: Int = -1
    @JvmField var attention_type: Int = 0
    @JvmField var rope_freq_base: Float = 0f
    @JvmField var rope_freq_scale: Float = 0f
    @JvmField var yarn_ext_factor: Float = -1f
    @JvmField var yarn_attn_factor: Float = 1f
    @JvmField var yarn_beta_fast: Float = 32f
    @JvmField var yarn_beta_slow: Float = 1f
    @JvmField var yarn_orig_ctx: Int = 0
    @JvmField var defrag_thold: Float = -1f
    @JvmField var cb_eval: Pointer? = null
    @JvmField var cb_eval_user_data: Pointer? = null
    @JvmField var type_k: Int = 1
    @JvmField var type_v: Int = 1
    @JvmField var logits_all: Boolean = false
    @JvmField var embeddings: Boolean = false
    @JvmField var offload_kqv: Boolean = true
    @JvmField var flash_attn: Boolean = false
    @JvmField var no_perf: Boolean = false
}

/**
 * llama_sampler_chain_params structure
 */
@Structure.FieldOrder("no_perf")
class LlamaSamplerChainParams : Structure() {
    @JvmField var no_perf: Boolean = false
}

/**
 * JNA interface for llama.cpp
 * Maps to native llama.cpp C API
 */
interface LlamaCppLibrary : Library {
    
    companion object {
        private const val LIBRARY_NAME = "llama"

        @Volatile
        private var cached: LlamaCppLibrary? = null

        /** true のときは [invalidate] まで再試行しない（ログスパム防止） */
        @Volatile
        private var gaveUpUntilInvalidate: Boolean = false

        val INSTANCE: LlamaCppLibrary?
            get() = getOrLoad()

        /** llama.dll 等を後から配置したあと、再度 [Native.load] できるようにする */
        fun invalidate() {
            synchronized(LlamaCppLibrary::class.java) {
                cached = null
                gaveUpUntilInvalidate = false
            }
        }

        private fun getOrLoad(): LlamaCppLibrary? {
            cached?.let { return it }
            synchronized(LlamaCppLibrary::class.java) {
                cached?.let { return it }
                if (gaveUpUntilInvalidate) return null
                val loaded = try {
                    Native.load(LIBRARY_NAME, LlamaCppLibrary::class.java) as LlamaCppLibrary
                } catch (e: UnsatisfiedLinkError) {
                    println("Warning: llama.cpp library not found. Running in mock mode.")
                    println("To use real llama.cpp, see: desktop/LLAMA_SETUP.md")
                    null
                }
                cached = loaded
                gaveUpUntilInvalidate = loaded == null
                return loaded
            }
        }
    }
    
    // ========== Model Loading ==========
    
    fun llama_model_default_params(): LlamaModelParams?
    fun llama_load_model_from_file(path: String, params: LlamaModelParams?): Pointer?
    fun llama_free_model(model: Pointer)
    
    // ========== Context Management ==========
    
    fun llama_context_default_params(): LlamaContextParams?
    fun llama_new_context_with_model(model: Pointer, params: LlamaContextParams?): Pointer?
    fun llama_free(ctx: Pointer)
    
    // ========== Sampler ==========
    
    fun llama_sampler_chain_default_params(): LlamaSamplerChainParams?
    fun llama_sampler_chain_init(params: LlamaSamplerChainParams?): Pointer?
    fun llama_sampler_chain_add(chain: Pointer, sampler: Pointer)
    fun llama_sampler_free(sampler: Pointer)
    fun llama_sampler_init_temp(temp: Float): Pointer?
    fun llama_sampler_init_top_k(k: Int): Pointer?
    fun llama_sampler_init_top_p(p: Float, minKeep: Int): Pointer?
    
    // ========== Tokenization ==========
    
    fun llama_tokenize(
        model: Pointer,
        text: String,
        textLen: Int,
        tokens: IntArray,
        nTokensMax: Int,
        addSpecial: Boolean,
        parseSpecial: Boolean
    ): Int
    
    fun llama_token_to_piece(
        model: Pointer,
        token: Int,
        buf: ByteArray,
        length: Int,
        lstrip: Int,
        special: Boolean
    ): Int
    
    // ========== Inference ==========
    
    fun llama_decode(ctx: Pointer, batch: Pointer): Int
    fun llama_sampler_sample(sampler: Pointer, ctx: Pointer, idx: Int): Int
    fun llama_get_logits_ith(ctx: Pointer, i: Int): Pointer?
    
    // ========== Batch Management ==========
    
    fun llama_batch_init(nTokens: Int, embd: Int, nSeqMax: Int): Pointer
    fun llama_batch_free(batch: Pointer)
    
    // ========== Utility ==========
    
    fun llama_n_vocab(model: Pointer): Int
    fun llama_n_ctx(ctx: Pointer): Int
    fun llama_token_eos(model: Pointer): Int
    fun llama_token_bos(model: Pointer): Int
    fun llama_backend_init()  // Deprecated in older/newer llama.cpp versions
    fun llama_backend_free()
    fun llama_print_system_info(): String
    
    // ========== Backend Management (new in recent llama.cpp) ==========
    
    fun ggml_backend_load_all(): Boolean
    fun ggml_backend_load(): Boolean
    fun llama_numa_init(numa: Int)  // NUMA initialization
}

interface LlamaTokenCallback : Callback {
    fun invoke(token: String)
}
