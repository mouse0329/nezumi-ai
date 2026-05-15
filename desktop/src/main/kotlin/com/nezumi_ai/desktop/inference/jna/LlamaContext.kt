package com.nezumi_ai.desktop.inference.jna

import com.sun.jna.Pointer
import com.sun.jna.Memory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * High-level wrapper for llama.cpp
 * Compatible with Android RnLlamaNative API
 */
class LlamaContext(
    private val modelPath: String,
    private val nCtx: Int = 2048,
    private val nThreads: Int = Runtime.getRuntime().availableProcessors(),
    private val nGpuLayers: Int = 0
) {
    private var modelPtr: Pointer? = null
    private var contextPtr: Pointer? = null
    private var samplerPtr: Pointer? = null
    
    private val lib = LlamaCppLibrary.INSTANCE
    
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var isInterrupted = false
    
    @Volatile
    private var backendInitialized = false
    
    fun initialize(): Boolean {
        if (isInitialized) return true
        
        if (lib == null) {
            println("✗ llama.cpp library not loaded")
            println("  To use real AI inference:")
            println("  1. Build llama.cpp: See desktop/LLAMA_SETUP.md")
            println("  2. Place llama.dll in desktop/libs/windows/")
            println("  3. Or run: desktop/setup-llama.bat")
            return false
        }
        
        try {
            // Initialize backend once
            if (!backendInitialized) {
                println("Attempting to initialize llama.cpp backend...")
                
                // Try multiple initialization methods
                var initSuccess = false
                
                // Method 1: ggml_backend_load_all (newest)
                try {
                    val loaded = lib.ggml_backend_load_all()
                    if (loaded) {
                        println("✓ GGML backends loaded via ggml_backend_load_all")
                        initSuccess = true
                    }
                } catch (e: Throwable) {
                    println("⚠ ggml_backend_load_all failed: ${e.message}")
                }
                
                // Method 2: ggml_backend_load (older llama.cpp)
                if (!initSuccess) {
                    try {
                        val loaded = lib.ggml_backend_load()
                        if (loaded) {
                            println("✓ GGML backends loaded via ggml_backend_load")
                            initSuccess = true
                        }
                    } catch (e: Throwable) {
                        println("⚠ ggml_backend_load failed: ${e.message}")
                    }
                }
                
                // Method 3: llama_backend_init (legacy llama.cpp)
                if (!initSuccess) {
                    try {
                        lib.llama_backend_init()
                        println("✓ llama backend initialized via llama_backend_init")
                        initSuccess = true
                    } catch (e: Throwable) {
                        println("⚠ llama_backend_init failed: ${e.message}")
                    }
                }
                
                // Method 4: llama_numa_init (NUMA init only)
                if (!initSuccess) {
                    try {
                        lib.llama_numa_init(0)  // 0 = GGML_NUMA_STRATEGY_DISABLED
                        println("✓ NUMA initialized")
                        initSuccess = true
                    } catch (e: Throwable) {
                        println("⚠ llama_numa_init failed: ${e.message}")
                    }
                }
                
                // Method 5: Skip backend init entirely (let model loading handle it)
                if (!initSuccess) {
                    println("⚠ Backend initialization skipped, will try loading model directly")
                    initSuccess = true  // Continue anyway
                }
                
                if (!initSuccess) {
                    println("✗ All backend initialization methods failed")
                    return false
                }
                
                backendInitialized = true
                
                try {
                    val sysInfo = lib.llama_print_system_info()
                    println("  System info: $sysInfo")
                } catch (e: Exception) {
                    println("  (system info not available)")
                }
            }
            
            println("Loading model from: $modelPath")
            
            // Create model params with proper initialization
            println("Creating model params...")
            val modelParams = lib.llama_model_default_params() ?: LlamaModelParams()
            try {
                modelParams.n_gpu_layers = nGpuLayers
                modelParams.use_mmap = true
                modelParams.use_mlock = false
                modelParams.vocab_only = false
                modelParams.check_tensors = false
                println("Model params configured: n_gpu_layers=$nGpuLayers")
            } catch (e: Exception) {
                println("Warning: Failed to set model params: ${e.message}")
            }
            
            // Try to load model with params
            println("Calling llama_load_model_from_file...")
            modelPtr = try {
                lib.llama_load_model_from_file(modelPath, modelParams)
            } catch (e: Error) {
                println("✗ Error calling llama_load_model_from_file: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            } catch (e: Exception) {
                println("✗ Exception calling llama_load_model_from_file: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            }
            
            if (modelPtr == null) {
                println("✗ Failed to load model: $modelPath")
                println("  Possible causes:")
                println("  - Model file is corrupted")
                println("  - Insufficient memory (need ~${File(modelPath).length() / 1024 / 1024}MB RAM)")
                println("  - Incompatible model format")
                return false
            }
            println("✓ Model loaded successfully")
            
            // Get default context params
            println("Creating context params...")
            val ctxParams = LlamaContextParams()
            try {
                ctxParams.n_ctx = nCtx
                ctxParams.n_threads = nThreads
                ctxParams.n_threads_batch = nThreads
                ctxParams.logits_all = false
                ctxParams.embeddings = false
                ctxParams.offload_kqv = true
                println("Context params configured: n_ctx=$nCtx, n_threads=$nThreads")
            } catch (e: Exception) {
                println("Warning: Failed to set context params: ${e.message}")
            }
            
            println("Creating context...")
            contextPtr = try {
                lib.llama_new_context_with_model(modelPtr!!, ctxParams)
            } catch (e: Error) {
                println("✗ Error creating context: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            }
            
            if (contextPtr == null) {
                println("✗ Failed to create context")
                println("  Try reducing context size (current: $nCtx)")
                return false
            }
            println("✓ Context created successfully")
            
            // Get default sampler params
            println("Getting default sampler params...")
            val samplerParams = try {
                lib.llama_sampler_chain_default_params()
            } catch (e: Error) {
                println("Warning: Failed to get sampler params, using null")
                null
            }
            
            // Create sampler chain
            println("Creating sampler chain...")
            samplerPtr = try {
                lib.llama_sampler_chain_init(samplerParams)
            } catch (e: Error) {
                println("✗ Error creating sampler: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
                null
            }
            
            if (samplerPtr == null) {
                println("✗ Failed to create sampler")
                return false
            }
            
            // Add default samplers
            try {
                lib.llama_sampler_init_top_k(40)?.let { lib.llama_sampler_chain_add(samplerPtr!!, it) }
                lib.llama_sampler_init_top_p(0.95f, 1)?.let { lib.llama_sampler_chain_add(samplerPtr!!, it) }
                lib.llama_sampler_init_temp(0.8f)?.let { lib.llama_sampler_chain_add(samplerPtr!!, it) }
                println("✓ Sampler chain initialized")
            } catch (e: Exception) {
                println("Warning: Failed to add samplers: ${e.message}")
            }
            
            isInitialized = true
            println("✓ llama.cpp initialized successfully")
            println("  Model: $modelPath")
            println("  Context size: $nCtx")
            println("  Threads: $nThreads")
            println("  GPU layers: $nGpuLayers")
            
            return true
        } catch (e: UnsatisfiedLinkError) {
            println("✗ Native library error: ${e.message}")
            println("  llama.dll is missing or has unresolved dependencies")
            println("  See desktop/LLAMA_SETUP.md for setup instructions")
            release()
            return false
        } catch (e: Error) {
            println("✗ Native error: ${e.javaClass.simpleName}: ${e.message}")
            println("  llama.dll may be incompatible")
            release()
            return false
        } catch (e: Exception) {
            println("✗ Failed to initialize llama.cpp: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            release()
            return false
        }
    }
    
    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        topP: Float = 0.95f,
        topK: Int = 40
    ): Flow<String> = flow {
        if (!isInitialized) {
            if (!initialize()) {
                emit("Error: Failed to initialize llama.cpp")
                return@flow
            }
        }
        
        isInterrupted = false
        
        try {
            // Tokenize prompt
            val tokens = IntArray(nCtx)
            val nTokens = lib?.llama_tokenize(
                modelPtr!!,
                prompt,
                prompt.length,
                tokens,
                nCtx,
                true,
                true
            ) ?: -1
            
            if (nTokens < 0) {
                emit("Error: Failed to tokenize prompt")
                return@flow
            }
            
            println("Prompt tokenized: $nTokens tokens")
            
            // Create batch
            val batch = lib?.llama_batch_init(nCtx, 0, 1)
            
            // Decode prompt tokens
            for (i in 0 until nTokens) {
                // TODO: Add tokens to batch
                // This requires proper batch structure handling
            }
            
            // Generate tokens
            var generatedCount = 0
            var currentToken = tokens[nTokens - 1]
            
            while (generatedCount < maxTokens && !isInterrupted) {
                // Decode
                val decodeResult = lib?.llama_decode(contextPtr!!, batch!!)
                if (decodeResult != 0) {
                    println("Decode failed: $decodeResult")
                    break
                }
                
                // Sample next token
                currentToken = lib?.llama_sampler_sample(samplerPtr!!, contextPtr!!, -1) ?: break
                
                // Check for EOS
                val eosToken = lib?.llama_token_eos(modelPtr!!)
                if (currentToken == eosToken) {
                    break
                }
                
                // Convert token to text
                val tokenText = tokenToString(currentToken)
                if (tokenText.isNotEmpty()) {
                    emit(tokenText)
                }
                
                generatedCount++
            }
            
            batch?.let { lib?.llama_batch_free(it) }
            
        } catch (e: Exception) {
            emit("Error: ${e.message}")
            e.printStackTrace()
        }
    }
    
    private fun tokenToString(token: Int): String {
        val buffer = ByteArray(256)
        val length = lib?.llama_token_to_piece(
            modelPtr!!,
            token,
            buffer,
            buffer.size,
            0,
            false
        ) ?: 0
        
        return if (length > 0) {
            String(buffer, 0, length, StandardCharsets.UTF_8)
        } else {
            ""
        }
    }
    
    fun interrupt() {
        isInterrupted = true
    }
    
    fun release() {
        samplerPtr?.let {
            lib?.llama_sampler_free(it)
            samplerPtr = null
        }
        
        contextPtr?.let {
            lib?.llama_free(it)
            contextPtr = null
        }
        
        modelPtr?.let {
            lib?.llama_free_model(it)
            modelPtr = null
        }
        
        isInitialized = false
        println("llama.cpp context released")
    }
}
