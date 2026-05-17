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
        private const val DEFAULT_N_THREADS = 4
        private const val DEFAULT_N_GPU_LAYERS = 0
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val MAX_NEW_TOKENS = 4096

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
            else -> 0
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

                Log.i(TAG, "Loading GGUF model: $modelPath backend=${normalized.backendType}")
                val ctx = withContext(Dispatchers.IO) {
                    LlamaBridge.llamaInit(
                        modelPath = modelPath,
                        nCtx = normalized.contextWindow,
                        nThreads = DEFAULT_N_THREADS,
                        nGpuLayers = nGpuLayersForBackend(normalized.backendType),
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

            val answerAccum = StringBuilder()

            withContext(Dispatchers.IO) {
                // トークナイズ
                val tokens = LlamaBridge.llamaTokenize(ctx, prompt, addBos = true)
                    ?: throw IllegalStateException("Tokenization failed")

                // プロンプトを KV キャッシュに投入
                val decodeResult = LlamaBridge.llamaDecode(ctx, tokens)
                if (decodeResult != 0) {
                    throw IllegalStateException("llamaDecode failed: $decodeResult")
                }

                val eosToken = LlamaBridge.llamaEosToken(ctx)
                var generatedCount = 0

                // 生成ループ
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
                        answerAccum.append(piece)
                        trySend(piece)
                    }

                    // 生成トークンを KV キャッシュに追加（次ターン継続用）
                    LlamaBridge.llamaDecode(ctx, intArrayOf(token))
                    generatedCount++
                }

                Log.d(TAG, "GGUF inference done: session=$sessionId generated=$generatedCount cancelled=${cancelFlag.get()}")
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
