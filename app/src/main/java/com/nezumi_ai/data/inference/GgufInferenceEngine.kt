package com.nezumi_ai.data.inference

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * llama.cpp (GGUF) 推論エンジン。
 *
 * [AIInferenceEngine] を実装し、LiteRtLmEngine と差し替え可能。
 * 出力フォーマットは [InferenceStreamProtocol] に準拠するため、
 * 上位レイヤー（ViewModel / Repository）はエンジン種別を意識しない。
 *
 * ## セッション管理
 * - 同一 sessionId の間は KV キャッシュを保持し、ターン間の速度を向上させる。
 * - sessionId が変わった場合は llamaClearKvCache() を呼んでリセットする。
 *
 * ## スレッドモデル
 * - [loadModel] / [unloadModel] は [modelMutex] で直列化
 * - [inference] は [inferenceMutex] で直列化（同時推論1本のみ）
 * - JNI 呼び出しは Dispatchers.IO で実行
 *
 * ## キャンセル
 * - [cancelInference] で [cancelFlag] を立てるだけ
 * - 推論ループが毎トークン後にフラグを確認して脱出する
 * - LiteRT 側の cancelProcess() 相当
 */
class GgufInferenceEngine : AIInferenceEngine {

    companion object {
        private const val TAG = "GgufInferenceEngine"

        // llama.cpp デフォルト値
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val MAX_NEW_TOKENS = 4096
        private const val CHUNK_SIZE = 8  // トークンをチャンク単位で送信

        // ロールプレイループ・自己対話を防ぐ停止シーケンス
        private val STOP_SEQUENCES = listOf(
            "<|im_end|>",
            "<|im_start|>",
            "<end_of_turn>",
            "<start_of_turn>",
            "User:",
            "User：",
            "\nUser:",
            " User:",
            "Assistant:",
            "Assistant：",
            "\nAssistant:",
            " Assistant:",
            "ユーザー:",
            "ユーザー：",
            "\nユーザー:",
            " ユーザー:",
            "アシスタント:",
            "アシスタント：",
            "\nアシスタント:",
            " アシスタント:"
        )

        /**
         * デバイスのCPUコア数に基づいて最適なスレッド数を計算
         */
        private fun getOptimalThreadCount(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            // 物理コア数の推定（ハイパースレッディング考慮）
            val physicalCores = (cores / 2).coerceAtLeast(1)
            // 推論には物理コア数 - 1 を使用（UIスレッド用に1コア残す）
            return (physicalCores - 1).coerceAtLeast(2).coerceAtMost(8)
        }

        // チャットテンプレート（Gemma / ChatML 等に応じて変更）
        private const val ROLE_USER = "user"
        private const val ROLE_MODEL = "model"

        /**
         * InferenceConfig の backendType を GPU オフロード層数に変換する。
         * llama.cpp は層数ベースで GPU オフロードを制御する。
         * "GPU" → 全層オフロード (999)、それ以外 → 0 (CPU)
         */
        private fun nGpuLayersForBackend(backendType: String): Int = when (backendType.uppercase()) {
            "GPU" -> 999  // 全層 GPU オフロード
            "AUTO" -> -1  // 自動判定（llama.cppが最適値を選択）
            else -> 0
        }

        /**
         * メモリ使用量に基づいてGPU層数を動的に調整
         */
        private fun getAdaptiveGpuLayers(backendType: String): Int {
            if (backendType.uppercase() != "GPU") return 0
            
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val availableMemory = maxMemory - usedMemory
            
            // 利用可能メモリに応じてGPU層数を調整
            return when {
                availableMemory > 6L * 1024 * 1024 * 1024 -> 999  // 6GB以上: 全層
                availableMemory > 4L * 1024 * 1024 * 1024 -> 35   // 4-6GB: 大部分
                availableMemory > 2L * 1024 * 1024 * 1024 -> 20   // 2-4GB: 半分
                else -> 0  // 2GB未満: CPU
            }
        }
    }

    // ─── 状態 ────────────────────────────────────────────────────

    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)

    /** llama_context* をラップした Long ポインタ。0 = 未ロード */
    @Volatile private var nativeCtx: Long = 0L

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedConfig: InferenceConfig? = null

    /** セッション変更検出用 */
    @Volatile private var lastSessionId: Long? = null

    /** 推論ループへのキャンセルシグナル */
    private val cancelFlag = AtomicBoolean(false)

    // ─── ロード / アンロード ──────────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return modelMutex.withLock {
            try {
                val normalized = config.normalized()
                val modelFile = resolveModelFile(modelName)
                    ?: return@withLock Result.failure(
                        IllegalStateException("GGUF model file not found: $modelName")
                    )

                val modelPath = modelFile.absolutePath
                if (nativeCtx != 0L &&
                    loadedModelPath == modelPath &&
                    loadedConfig == normalized
                ) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return@withLock Result.success(Unit)
                }

                // 既存コンテキストを解放
                freeNativeCtx()

                val optimalThreads = getOptimalThreadCount()
                val gpuLayers = getAdaptiveGpuLayers(normalized.backendType)
                
                if (!LlamaBridge.isLibraryLoaded()) {
                    return@withLock Result.failure(
                        IllegalStateException("GGUF native bridge not loaded: libllama_bridge.so unavailable")
                    )
                }

                Log.i(TAG, "Loading GGUF model: $modelPath backend=${normalized.backendType} threads=$optimalThreads gpuLayers=$gpuLayers")
                val ctx = withContext(Dispatchers.IO) {
                    LlamaBridge.llamaInit(
                        modelPath = modelPath,
                        nCtx = normalized.contextWindow,
                        nThreads = optimalThreads,
                        nGpuLayers = gpuLayers,
                        seed = -1  // ランダムシード
                    )
                }

                if (ctx == 0L) {
                    return@withLock Result.failure(
                        IllegalStateException("llamaInit failed — check model path and memory")
                    )
                }

                nativeCtx = ctx
                loadedModelPath = modelPath
                loadedConfig = normalized
                lastSessionId = null  // セッションリセット
                Log.i(TAG, "GGUF model loaded: $modelPath llama.cpp ${LlamaBridge.llamaVersion()}")
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return modelMutex.withLock {
            try {
                cancelFlag.set(true)
                freeNativeCtx()
                lastSessionId = null
                Log.i(TAG, "GGUF model unloaded")
                Result.success(Unit)
            } catch (t: Throwable) {
                Result.failure(if (t is Exception) t else RuntimeException(t))
            } finally {
                cancelFlag.set(false)
            }
        }
    }

    private fun freeNativeCtx() {
        val ctx = nativeCtx
        if (ctx != 0L) {
            LlamaBridge.llamaFree(ctx)
            nativeCtx = 0L
            loadedModelPath = null
            loadedConfig = null
        }
    }

    // ─── キャンセル ───────────────────────────────────────────────

    override suspend fun cancelInference() {
        Log.d(TAG, "cancelInference: setting cancelFlag")
        cancelFlag.set(true)
    }

    // ─── 推論 ─────────────────────────────────────────────────────

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    /**
     * GGUF推論。images / audioClips は現状無視（テキストのみ対応）。
     *
     * 出力フォーマット:
     *   - テキストデルタ: そのまま trySend
     *   - 完了: [InferenceStreamProtocol.encodeFinal]
     *
     * LiteRT 側と同じ protocol を使うため、ViewModel 側の変更不要。
     */
    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow<String> {
        // 推論 mutex を取得（同時推論1本のみ）
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)
        cancelFlag.set(false)

        val ctx = nativeCtx
        if (ctx == 0L) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val normalized = config.normalized()

        try {
            // セッション変更時は KV キャッシュをクリア
            if (lastSessionId != sessionId) {
                Log.d(TAG, "Session changed: $lastSessionId → $sessionId, clearing KV cache")
                withContext(Dispatchers.IO) {
                    LlamaBridge.llamaClearKvCache(ctx)
                }
                lastSessionId = sessionId
            }

            Log.d(TAG, "GGUF inference start: session=$sessionId promptLen=${prompt.length}")

            // パフォーマンスモニタリング開始
            val promptTokenCount = withContext(Dispatchers.IO) {
                LlamaBridge.llamaTokenize(ctx, prompt, addBos = true)?.size ?: 0
            }
            PerformanceMonitor.startInference(sessionId, normalized.backendType, promptTokenCount)

            val answerAccum = StringBuilder()
            val chunkBuffer = StringBuilder()
            var lastSendTime = System.currentTimeMillis()
            val inferenceStartTime = System.currentTimeMillis()
            var firstTokenTime: Long? = null

            withContext(Dispatchers.IO) {
                // トークナイズ
                val tokens = LlamaBridge.llamaTokenize(ctx, prompt, addBos = true)
                    ?: throw IllegalStateException("Tokenization failed")

                Log.d(TAG, "Tokenized: ${tokens.size} tokens")

                // プロンプトを KV キャッシュに投入
                decodePromptTokens(ctx, tokens)

                val eosToken = LlamaBridge.llamaEosToken(ctx)
                var generatedCount = 0
                var tokensSinceLastSend = 0

                // 生成ループ - チャンク単位で送信して効率化
                while (isActive && !cancelFlag.get() && generatedCount < MAX_NEW_TOKENS) {
                    val token = LlamaBridge.llamaSample(
                        ctx = ctx,
                        temperature = normalized.temperature,
                        topP = normalized.topP,
                        topK = normalized.maxTopK,
                        repeatPenalty = DEFAULT_REPEAT_PENALTY
                    )

                    if (token == eosToken) {
                        Log.d(TAG, "EOS reached at token $generatedCount")
                        break
                    }

                    // トークン → テキスト
                    val piece = LlamaBridge.llamaTokenToPiece(ctx, token)
                    if (piece.isNotEmpty()) {
                        if (firstTokenTime == null) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        answerAccum.append(piece)
                        chunkBuffer.append(piece)
                        tokensSinceLastSend++
                        PerformanceMonitor.recordToken(sessionId)  // トークン生成を記録
                    }

                    // ストップシーケンスチェック（自己対話ループを防ぐ）
                    val accumulated = answerAccum.toString()
                    val hitStop = STOP_SEQUENCES.any { stop -> accumulated.endsWith(stop) }
                    if (hitStop) {
                        // ストップシーケンス分をバッファから除去して送信
                        val matchedStop = STOP_SEQUENCES.first { stop -> accumulated.endsWith(stop) }
                        val trimmed = chunkBuffer.toString().removeSuffix(matchedStop)
                        if (trimmed.isNotEmpty()) trySend(trimmed)
                        chunkBuffer.clear()
                        tokensSinceLastSend = 0
                        Log.d(TAG, "Stop sequence hit at token $generatedCount: ${matchedStop.take(20)}")
                        break
                    }

                    // チャンク単位または100ms経過で送信（UIの応答性向上）
                    val now = System.currentTimeMillis()
                    if (tokensSinceLastSend >= CHUNK_SIZE || (now - lastSendTime) >= 100) {
                        if (chunkBuffer.isNotEmpty()) {
                            trySend(chunkBuffer.toString())
                            chunkBuffer.clear()
                            tokensSinceLastSend = 0
                            lastSendTime = now
                        }
                        
                        // TPS計算（10トークンごと）
                        if (generatedCount > 0 && generatedCount % 10 == 0 && firstTokenTime != null) {
                            val elapsed = now - firstTokenTime
                            if (elapsed > 0) {
                                val tps = (generatedCount * 1000f) / elapsed
                                trySend(InferenceStreamProtocol.encodeTps(tps))
                                Log.d(TAG, "TPS: %.1f tok/s (tokens=$generatedCount, elapsed=${elapsed}ms)".format(tps))
                            }
                        }
                    }

                    // 生成トークンを KV キャッシュに追加（次ターン継続用）
                    LlamaBridge.llamaDecode(ctx, intArrayOf(token))
                    generatedCount++
                }

                // 残りのバッファを送信
                if (chunkBuffer.isNotEmpty()) {
                    trySend(chunkBuffer.toString())
                }
                
                // 最終TPS計算と送信
                if (generatedCount > 0 && firstTokenTime != null) {
                    val finalElapsed = System.currentTimeMillis() - firstTokenTime
                    if (finalElapsed > 0) {
                        val finalTps = (generatedCount * 1000f) / finalElapsed
                        trySend(InferenceStreamProtocol.encodeTps(finalTps))
                        Log.d(TAG, "Final TPS: %.1f tok/s (tokens=$generatedCount, elapsed=${finalElapsed}ms)".format(finalTps))
                    }
                }

                Log.d(TAG, "GGUF inference done: session=$sessionId generated=$generatedCount cancelled=${cancelFlag.get()}")
            }

            // パフォーマンスメトリクスを記録
            val metrics = PerformanceMonitor.endInference(sessionId)
            if (metrics != null) {
                Log.i(TAG, "Performance: ${metrics.toLogString()}")
            }

            // LiteRT 側と同じ final チャンクを送出
            trySend(InferenceStreamProtocol.encodeFinal(answerAccum.toString()))
            close()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                // キャンセル時も final を送出（LiteRT 側と同じ挙動）
                trySend(InferenceStreamProtocol.encodeFinal(""))
                close()
            } else {
                Log.e(TAG, "GGUF inference error: session=$sessionId", t)
                close(if (t is Exception) t else RuntimeException(t))
            }
        } finally {
            releaseInferenceMutex()
        }

        awaitClose {
            Log.d(TAG, "awaitClose: session=$sessionId")
            cancelFlag.set(true)
        }
    }.flowOn(Dispatchers.IO)

    // ─── ユーティリティ ──────────────────────────────────────────

    override suspend fun isAvailable(): Boolean = nativeCtx != 0L

    private fun decodePromptTokens(ctx: Long, tokens: IntArray) {
        if (tokens.isEmpty()) return
        val batchCapacity = LlamaBridge.llamaGetBatchCapacity(ctx).coerceAtLeast(1)
        var offset = 0
        while (offset < tokens.size) {
            val end = minOf(offset + batchCapacity, tokens.size)
            val chunk = tokens.copyOfRange(offset, end)
            val result = LlamaBridge.llamaDecode(ctx, chunk)
            if (result != 0) {
                throw IllegalStateException(
                    "llamaDecode failed: $result (chunk ${offset + 1}..$end / capacity=$batchCapacity)"
                )
            }
            offset = end
        }
    }

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex: double-release guard triggered")
        }
    }

    /**
     * modelName から File を解決する。
     * "*.gguf" で終わる絶対パスはそのまま使用する。
     * それ以外は今後 ModelFileManager に統合する想定。
     */
    private fun resolveModelFile(modelName: String): File? {
        // 絶対パスで .gguf ファイルが指定された場合
        if (modelName.endsWith(".gguf", ignoreCase = true) && modelName.startsWith("/")) {
            val f = File(modelName)
            return if (f.exists() && f.canRead()) f else null
        }
        // TODO: ModelFileManager に GGUF エントリを追加後、ここで解決する
        Log.w(TAG, "resolveModelFile: unsupported modelName format: $modelName")
        return null
    }
}
