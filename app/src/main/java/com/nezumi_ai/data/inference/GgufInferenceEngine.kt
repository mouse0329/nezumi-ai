package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.nehuatl.llamacpp.LlamaHelper
import org.nehuatl.llamacpp.LlamaContext
import org.nehuatl.llamacpp.LlamaAndroid
import java.io.File
import java.io.IOException
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.absoluteValue

/**
 * llama.cpp (GGUF) 推論エンジン。llamacpp-kotlin 0.2.0 の LlamaHelper を使用。
 *
 * [AIInferenceEngine] を実装し、LiteRtLmEngine と差し替え可能。
 * 出力は [InferenceStreamProtocol] 準拠なので ViewModel / Repository の変更不要。
 *
 * ## 制約
 * - GPU オフロードは nGpuLayers=0 固定（Tensor G3 は OpenCL 非対応）
 * - マルチモーダル（画像）は現状テキストのみ（LLaVA mmproj は後回し）
 * - KVキャッシュは LlamaHelper 内部の contextId で管理
 * - セッション変更時も同一コンテキストを使いまわす（完全リセットは unload→load）
 */
class GgufInferenceEngine(private val context: Context) : AIInferenceEngine {

    companion object {
        private const val TAG = "GgufInferenceEngine"
        // "User:" 以降に次ターンを自己生成してしまうのを防ぐ
        private val STOP_SEQUENCES = listOf(
            "User:",
            "User：",
            "\nUser:",
            "\r\nUser:",
            " User:",
            "Assistant:",
            "Assistant：",
            "\nAssistant:",
            "\r\nAssistant:",
            " Assistant:",
            "ユーザー:",
            "ユーザー：",
            "\nユーザー:",
            "\r\nユーザー:",
            " ユーザー:",
            "アシスタント:",
            "アシスタント：",
            "\nアシスタント:",
            "\r\nアシスタント:",
            " アシスタント:"
        )
    }

    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)

    // LlamaHelper と、そこに渡した sharedFlow の参照を両方保持する
    @Volatile private var llamaHelper: LlamaHelper? = null
    @Volatile private var llamaFlow: MutableSharedFlow<LlamaHelper.LLMEvent>? = null
    @Volatile private var llamaContext: LlamaContext? = null  // ★ LlamaContext 直接保持
    @Volatile private var contextId: Int? = null  // ★ context id
    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedConfig: InferenceConfig? = null
    @Volatile private var isModelLoaded = false
    @Volatile private var lastSessionId: Long? = null
    @Volatile private var llamaAndroid: LlamaAndroid? = null  // ★ LlamaAndroid インスタンス
    @Volatile private var currentInferenceJob: Job? = null  // 現在実行中の推論ジョブ

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return modelMutex.withLock {
            try {
                val normalized = config.normalized()
                val modelFile = resolveModelFile(modelName)
                    ?: return@withLock Result.failure(
                        IllegalStateException("GGUF model file not found: $modelName")
                    )
                val modelPath = modelFile.absolutePath

                if (isModelLoaded &&
                    loadedModelPath == modelPath &&
                    loadedConfig == normalized
                ) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return@withLock Result.success(Unit)
                }

                releaseHelper()

                Log.i(TAG, "Loading GGUF: $modelPath nCtx=${normalized.contextWindow}")

                // ★ LlamaContext を直接初期化
                // model パスと model_fd の両方を渡す
                try {
                    val cid = Random().nextInt().absoluteValue
                    
                    // ★ ParcelFileDescriptor.open() で fd を取得し、detach して raw fd を渡す
                    // （llamacpp-kotlin 側は detachFd 前提。owner 競合を避ける）
                    val pfd = ParcelFileDescriptor.open(
                        File(modelPath),
                        ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    val modelFd = pfd.detachFd()
                    Log.d(TAG, "Opened and detached file descriptor: fd=$modelFd for $modelPath")
                    
                    // ★ model と model_fd の両方を params に渡す
                    val params = mapOf<String, Any>(
                        "model" to modelPath,   // ★ 必須：ファイルパス
                        "model_fd" to modelFd,  // ★ 必須：ファイルディスクリプタ（Int）
                        "embedding" to false,
                        "n_ctx" to normalized.contextWindow,
                        "n_batch" to normalized.llamaCppBatchSize,
                        "n_threads" to normalized.llamaCppThreads,
                        "n_gpu_layers" to normalized.llamaCppGpuLayers,
                        "n_keep" to normalized.llamaCppNKeep,
                        "use_mlock" to false,   // メモリロック無効（安定性向上）
                        "use_mmap" to true,
                        "vocab_only" to false,
                        "lora" to "",
                        "lora_scaled" to 1.0,
                        "rope_freq_base" to normalized.llamaCppRopeFreqBase,
                        "rope_freq_scale" to normalized.llamaCppRopeFreqScale
                    )

                    Log.d(TAG, "Creating LlamaContext with model_path=$modelPath model_fd=$modelFd")
                    Log.d(TAG, "Params: n_ctx=${normalized.contextWindow}, n_threads=${normalized.llamaCppThreads}, n_batch=${normalized.llamaCppBatchSize}, n_gpu_layers=${normalized.llamaCppGpuLayers}, n_keep=${normalized.llamaCppNKeep}, rope_freq_base=${normalized.llamaCppRopeFreqBase}, rope_freq_scale=${normalized.llamaCppRopeFreqScale}")
                    
                    // ★ LlamaContext 初期化を try-catch でラップ
                    val lc = try {
                        LlamaContext(cid, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "LlamaContext constructor threw exception", e)
                        Log.e(TAG, "Full exception: ${e.stackTraceToString()}")
                        throw IllegalStateException("Failed to create LlamaContext: ${e.message}", e)
                    }
                    
                    // ★ context が有効に初期化されたか確認
                    if (lc.context == 0L) {
                        Log.e(TAG, "LlamaContext.context is 0, initialization failed")
                        Log.e(TAG, "Model file path: $modelPath")
                        Log.e(TAG, "File exists: ${File(modelPath).exists()}")
                        Log.e(TAG, "File size: ${File(modelPath).length()} bytes")
                        throw IllegalStateException("LlamaContext initialization returned invalid context (0L)")
                    }
                    
                    Log.d(TAG, "LlamaContext initialized successfully: context=${lc.context}")
                    
                    // token callback を設定（推論時に使用）
                    try {
                        lc.setTokenCallback { token ->
                            Log.d(TAG, "Token: $token")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to set token callback", e)
                    }
                    
                    llamaContext = lc
                    contextId = cid
                    loadedModelPath = modelPath
                    loadedConfig = normalized
                    isModelLoaded = true
                    lastSessionId = null
                    
                    Log.i(TAG, "GGUF model loaded OK: $modelPath (context_id=$cid fd=$modelFd)")
                    Result.success(Unit)
                } catch (t: Exception) {
                    Log.e(TAG, "LlamaContext initialization failed", t)
                    Result.failure(t)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    // ─── アンロード ──────────────────────────────────────────────

    override suspend fun unloadModel(): Result<Unit> {
        return modelMutex.withLock {
            try {
                releaseHelper()
                Log.i(TAG, "GGUF model unloaded")
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    private fun releaseHelper() {
        llamaHelper?.abort()
        llamaHelper?.release()
        llamaHelper = null
        llamaFlow = null
        
        // ★ LlamaContext も release
        llamaContext?.release()
        llamaContext = null
        contextId = null

        loadedModelPath = null
        loadedConfig = null
        isModelLoaded = false
        lastSessionId = null
        Log.d(TAG, "Model and LlamaContext released")
    }

    // ─── キャンセル ───────────────────────────────────────────────

    override suspend fun cancelInference() {
        Log.d(TAG, "cancelInference called")
        currentInferenceJob?.let { job ->
            Log.d(TAG, "Cancelling ongoing inference job")
            job.cancel(CancellationException("User cancelled inference"))
            try {
                withTimeoutOrNull(5000L) {  // 5秒でタイムアウト
                    job.join()  // キャンセルが完了するまで待機
                    Log.d(TAG, "Inference job cancelled and joined successfully")
                } ?: run {
                    Log.w(TAG, "Inference job cancellation timeout after 5s - forcing termination")
                    job.cancel()  // 強制キャンセル
                }
            } catch (e: Exception) {
                Log.d(TAG, "Inference job cancellation completed with exception: ${e.message}")
            }
        } ?: run {
            Log.d(TAG, "No ongoing inference to cancel")
        }
    }

    // ─── 推論 ─────────────────────────────────────────────────────

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    /**
     * GGUF 推論。
     *
     * LlamaContext.completion() を直接呼び出し、
     * token callback で trySend してフロー化する。
     */
    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)

        val ctx = modelMutex.withLock { llamaContext }
        if (ctx == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        if (lastSessionId != sessionId) {
            Log.d(TAG, "Session: $lastSessionId → $sessionId")
            lastSessionId = sessionId
        }

        val answerAccum = StringBuilder()

        // LlamaContext.completion() を呼び出し
        try {
            val completionJob = launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "GGUF started session=$sessionId")

                    // token callback を設定（completion 中にトークンが emit される）
                    ctx.setTokenCallback { token ->
                        // キャンセルチェック：定期的にキャンセル要求をチェック
                        if (!isActive) {
                            Log.d(TAG, "Token callback detected cancellation")
                            throw CancellationException("Inference cancelled during token generation")
                        }
                        answerAccum.append(token)
                        trySend(token)
                    }

                    // 推論実行
                    val normalized = modelMutex.withLock { loadedConfig?.normalized() } ?: config.normalized()
                    val result = ctx.completion(
                        mapOf(
                            "prompt" to prompt,
                            "n_predict" to normalized.maxTokens,
                            "temperature" to normalized.temperature.toDouble(),
                            "top_p" to normalized.topP.toDouble(),
                            "top_k" to normalized.maxTopK,
                            "stop" to STOP_SEQUENCES,
                            "emit_partial_completion" to true
                        )
                    )

                    Log.d(TAG, "GGUF done session=$sessionId answer_length=${answerAccum.length}")
                    trySend(InferenceStreamProtocol.encodeFinal(answerAccum.toString()))
                    close()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        Log.d(TAG, "GGUF cancelled session=$sessionId answer_length=${answerAccum.length}")
                        runCatching {
                            trySend(InferenceStreamProtocol.encodeFinal(answerAccum.toString()))
                        }
                        close()
                    } else {
                        Log.e(TAG, "completion error", t)
                        close(if (t is Exception) t else RuntimeException(t))
                    }
                } finally {
                    currentInferenceJob = null
                    releaseInferenceMutex()
                }
            }
            currentInferenceJob = completionJob
        } catch (t: Throwable) {
            releaseInferenceMutex()
            close(if (t is Exception) t else RuntimeException(t))
        }

        // キャンセル時のクリーンアップ
        awaitClose {
            Log.d(TAG, "Flow cancelled - awaiting job termination")
            // フローがキャンセルされたら、推論ジョブもキャンセル
            currentInferenceJob?.cancel()
        }
    }

    // ─── ユーティリティ ──────────────────────────────────────────

    override suspend fun isAvailable(): Boolean = isModelLoaded

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex: double-release guard")
        }
    }

    /**
     * modelName から File を解決する。
     * 絶対パス + .gguf 拡張子のみ対応。
     * 将来的には ModelFileManager に GGUF エントリを追加して統合する。
     */
    private fun resolveModelFile(modelName: String): File? {
        if (modelName.endsWith(".gguf", ignoreCase = true) && modelName.startsWith("/")) {
            val f = File(modelName)
            return if (f.exists() && f.canRead()) f else null
        }
        Log.w(TAG, "resolveModelFile: unsupported format: $modelName")
        return null
    }
}

