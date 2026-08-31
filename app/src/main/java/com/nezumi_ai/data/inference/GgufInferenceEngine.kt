package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.inference.rnllama.RnLlamaContext
import com.nezumi_ai.data.inference.rnllama.RnLlamaNative
import com.nezumi_ai.utils.GgufMetadataReader
import com.nezumi_ai.utils.ImportedModelCapabilityStore
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
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
class GgufInferenceEngine(
    private val appContext: Context
) : AIInferenceEngine {

    companion object {
        private const val TAG = "GgufInferenceEngine"

        internal data class NativeGenerationSettings(
            val batchSize: Int,
            val ubatchSize: Int,
            val flashAttentionEnabled: Boolean,
            val contextShiftEnabled: Boolean,
            val maxTokensCap: Int
        )

        internal fun tryClearKvCacheWithInferenceLock(
            inferenceMutex: Mutex,
            action: () -> Unit
        ): Boolean {
            if (!inferenceMutex.tryLock()) {
                logDebug("Skipping KV cache clear because inference is active")
                return false
            }
            return try {
                action()
                true
            } finally {
                inferenceMutex.unlock()
            }
        }

        private fun logDebug(message: String) {
            runCatching { Log.d(TAG, message) }
        }

        // llama.cpp デフォルト値
        private const val DEFAULT_REPEAT_PENALTY = 1.1f
        private const val MAX_NEW_TOKENS = 4096
        private const val CHUNK_SIZE = 8  // トークンをチャンク単位で送信

        // ロールプレイループ・自己対話を防ぐ停止シーケンス
        internal val DEFAULT_STOP_SEQUENCES = listOf(
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
         * Gemma4 GGUF 対策の追加 stop。
         *
         * llama.cpp の Gemma4 テンプレ / パーサーには F16 GGUF で
         * Thinking ON 時に `<unused49>` トークンをコンテキスト上限まで連打してしまうバグがある
         * (ggml-org/llama.cpp#21338・#24170)。このケースには client 側 stop でブレークする。
         * 入れすぎると本文を誤って打ち切ってしまうより、実際にバグレポートされた `<unused49>` と、
         * Gemma トークナイザ仕様上隣接している ±2 本だけに限定してその他トークンストリームへの影響を最小化する。
         */
        internal val GEMMA4_UNUSED_STOP_SEQUENCES: List<String> = listOf(
            "<unused47>",
            "<unused48>",
            "<unused49>",
            "<unused50>",
            "<unused51>"
        )

        private fun effectiveStopSequences(config: InferenceConfig): List<String> {
            val custom = config.customStopTokens.map { it.trim() }.filter { it.isNotEmpty() }
            return DEFAULT_STOP_SEQUENCES + GEMMA4_UNUSED_STOP_SEQUENCES + custom
        }

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

        internal fun resolveNativeGenerationSettings(
            modelPath: String,
            config: InferenceConfig,
            appContext: android.content.Context? = null
        ): NativeGenerationSettings {
            // Bug fix(#42): ユーザーがテンプレートを明示選択している場合は GPT-2 専用の
            // 保守的ネイティブ設定 (batch=32 / flashAttn=off) を抑制する。
            val isGpt2Model = PromptBuilder.detectGgufFormat(modelPath, appContext) == PromptBuilder.GgufPromptFormat.PLAIN_COMPLETION
            return if (isGpt2Model) {
                NativeGenerationSettings(
                    batchSize = 32,
                    ubatchSize = 32,
                    flashAttentionEnabled = false,
                    contextShiftEnabled = false,
                    maxTokensCap = 512
                )
            } else {
                NativeGenerationSettings(
                    // Bug fix: ユーザーが InferenceConfig で指定した値を尊重する。
                    // 以前は .coerceAtMost(512) で上限を強制していたため、
                    // ユーザーが 1024/2048 などを設定してもネイティブに 512 として渡り
                    // 「設定が反映されない」バグの原因になっていた。
                    // 上下限は InferenceConfig.normalized() で MIN/MAX_BATCH_SIZE により丸め済み。
                    batchSize = config.llamaCppBatchSize,
                    ubatchSize = config.llamaCppUBatchSize,
                    flashAttentionEnabled = config.flashAttentionEnabled,
                    contextShiftEnabled = config.contextShiftEnabled,
                    maxTokensCap = MAX_NEW_TOKENS
                )
            }
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

    /** llama.cpp コンテキスト（rnllama経由） */
    @Volatile private var rnllamaCtx: RnLlamaContext? = null

    @Volatile private var loadedModelPath: String? = null
    @Volatile private var loadedConfig: InferenceConfig? = null

    /** セッション変更検出用 */
    @Volatile private var lastSessionId: Long? = null

    /** 推論ループへのキャンセルシグナル */
    private val cancelFlag = AtomicBoolean(false)

    /**
 * 「次回推論開始前に KV を強制クリアする」フラグ。
     *
     * GGUF では、ユーザーが生成途中で停止ボタンを押すと、その時点で
     * `nativeInterrupt()` が出ても、ネイティブの KV キャッシュには
     * 途中までの assistant トークンが残っている。チャットテンプレート上の
     * `<end_of_turn>` / `<|im_end|>` などの終端トークンが記録されず「漏れたターン」
     * として残るため、次回推論でそのごみが prompt prefix として使われて
     * 「2.0.0 …」 のような壊れた出力を引き起こすケースがある。
     *
     * そのため、停止時 / 取り消し時にこのフラグを立てておき、
     * 次回の [inferenceWithMedia] 開始直後に一度だけ KV をクリアしてから
     * 生成を始める。
     */
    private val forceClearBeforeNextInference = AtomicBoolean(false)

    private val alarmDao by lazy { NezumiAiDatabase.getInstance(appContext).alarmDao() }
    private val memoryRepository by lazy {
        MemoryRepository(NezumiAiDatabase.getInstance(appContext).memoryDao())
    }
    private val toolExecutor by lazy {
        NezumiLiteRtToolExecutor(appContext, alarmDao, memoryRepository, MemoryTextEmbedder)
    }

    // ─── ロード / アンロード ──────────────────────────────────────

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return modelMutex.withLock {
            var inferenceLockAcquired = false
            try {
                // 推論中にモデル切り替え / release が走ると native context が壊れうるため、
                // ロード前に推論 mutex を確保しておく。これにより unload / clearKvCache との競合を抑える。
                inferenceLockAcquired = withContext(Dispatchers.IO) {
                    runCatching { inferenceMutex.tryLock() }.getOrDefault(false)
                }
                if (!inferenceLockAcquired) {
                    Log.w(TAG, "loadModel deferred because inference is active")
                    return@withLock Result.failure(IllegalStateException("Inference is active; try again later"))
                }

                val normalized = config.normalized()
                val modelFile = resolveModelFile(modelName)
                    ?: return@withLock Result.failure(
                        IllegalStateException("GGUF model file not found: $modelName")
                    )

                val modelPath = modelFile.absolutePath
                if (rnllamaCtx != null &&
                    rnllamaCtx!!.isValid &&
                    loadedModelPath == modelPath &&
                    loadedConfig == normalized
                ) {
                    Log.d(TAG, "Model already loaded: $modelPath")
                    return@withLock Result.success(Unit)
                }

                // 既存コンテキストを解放
                freeNativeCtx()

                // Bug fix: ユーザーが InferenceConfig.llamaCppThreads / llamaCppGpuLayers で
                // 明示指定した値をエンジンに反映する。以前は毎回 getOptimalThreadCount() /
                // getAdaptiveGpuLayers(backendType) で上書きしていたため、ユーザーが設定画面から
                // スレッド数や GPU レイヤ数を変えても llama.cpp に届かないバグがあった。
                //
                // - スレッド数: 0 以下（未設定扱い）の場合のみ自動検出値にフォールバック
                // - GPU レイヤ数: backendType が "CPU" のときは 0 で強制、
                //   "GPU" のときは config.llamaCppGpuLayers を尊重し、0 の場合のみ従来の
                //   getAdaptiveGpuLayers() で自動判定する。
                val optimalThreads = if (normalized.llamaCppThreads > 0) {
                    normalized.llamaCppThreads.coerceIn(
                        InferenceConfig.MIN_THREADS,
                        InferenceConfig.MAX_THREADS
                    )
                } else {
                    getOptimalThreadCount()
                }
                val gpuLayers = if (!OpenClAvailability.isAvailable()) {
                    // OpenCL 非対応端末では GPU オフロード不可。レイヤー数は常に 0。
                    0
                } else when (normalized.backendType.uppercase()) {
                    "GPU" -> if (normalized.llamaCppGpuLayers > 0) {
                        normalized.llamaCppGpuLayers
                    } else {
                        getAdaptiveGpuLayers(normalized.backendType)
                    }
                    // NPU/その他は llama.cpp では GPU オフロード不可 → 0
                    else -> 0
                }
                val nativeSettings = resolveNativeGenerationSettings(modelPath, normalized, appContext)
                if (nativeSettings.batchSize <= 0 || nativeSettings.ubatchSize <= 0) {
                    return@withLock Result.failure(IllegalStateException("Invalid GGUF batch size configuration"))
                }

                if (!RnLlamaNative.loadLibraryIfNeeded()) {
                    return@withLock Result.failure(
                        IllegalStateException("RnLlamaNative library not loaded: libnezumi_rnllama_jni.so unavailable")
                    )
                }

                if (PromptBuilder.detectGgufFormat(modelPath, appContext) == PromptBuilder.GgufPromptFormat.PLAIN_COMPLETION) {
                    Log.w(TAG, "Using conservative native settings for GPT-2 model: batch=${nativeSettings.batchSize}, ubatch=${nativeSettings.ubatchSize}, flashAttention=${nativeSettings.flashAttentionEnabled}, ctxShift=${nativeSettings.contextShiftEnabled}")
                }
                Log.i(
                    TAG,
                    "Loading GGUF model: $modelPath backend=${normalized.backendType} " +
                        "threads=$optimalThreads gpuLayers=$gpuLayers " +
                        "nBatch=${nativeSettings.batchSize} nUbatch=${nativeSettings.ubatchSize} " +
                        "ropeFreqBase=${normalized.llamaCppRopeFreqBase} " +
                        "ropeFreqScale=${normalized.llamaCppRopeFreqScale} " +
                        "flashAttention=${nativeSettings.flashAttentionEnabled} " +
                        "ctxShift=${nativeSettings.contextShiftEnabled} " +
                        "kvUnified=${normalized.llamaCppKvUnified} " +
                        "mtpEnabled=${normalized.mtpEnabled} mtpDraft=${normalized.mtpDraftTokens}"
                )

                if (modelFile.extension.equals("gguf", ignoreCase = true) && !hasGgufMagicHeader(modelFile)) {
                    return@withLock Result.failure(
                        IllegalStateException("Invalid GGUF model file: magic header not found")
                    )
                }

                // mmprojパスを取得
                val mmprojPath = ImportedModelCapabilityStore.get(appContext, modelPath).mmprojPath
                if (mmprojPath != null) {
                    Log.i(TAG, "Using mmproj: $mmprojPath")
                }

                val ctx = withContext(Dispatchers.IO) {
                    RnLlamaContext(
                        modelPath = modelPath,
                        nCtx = normalized.contextWindow,
                        nBatch = nativeSettings.batchSize,
                        nUbatch = nativeSettings.ubatchSize,
                        nThreads = optimalThreads,
                        nGpuLayers = gpuLayers,
                        mmprojPath = mmprojPath,
                        flashAttentionEnabled = nativeSettings.flashAttentionEnabled,
                        contextShiftEnabled = nativeSettings.contextShiftEnabled,
                        // Bug fix: RoPE 周波数と MTP / KV 最適化のユーザー設定を
                        // ネイティブ側にきちんと渡す。従来はコンストラクタに渡していなかったため
                        // 常にデフォルト値 (base=0f / scale=1f, mtp=off) で動いていた。
                        ropeFreqBase = normalized.llamaCppRopeFreqBase,
                        ropeFreqScale = normalized.llamaCppRopeFreqScale,
                        mtpEnabled = normalized.mtpEnabled,
                        mtpDraftTokens = normalized.mtpDraftTokens,
                        kvCacheOptimizationEnabled = normalized.kvCacheOptimizationEnabled
                    )
                }

                if (!ctx.isValid) {
                    return@withLock Result.failure(
                        IllegalStateException("RnLlamaContext failed to initialize — invalid model file or insufficient memory")
                    )
                }

                rnllamaCtx = ctx
                loadedModelPath = modelPath
                loadedConfig = normalized
                lastSessionId = null  // セッションリセット
                Log.i(TAG, "GGUF model loaded: $modelPath using rnllama backend")
                maybeAutoEnableThinkingFromChatTemplate(modelPath)
                Result.success(Unit)
            } catch (t: Throwable) {
                Log.e(TAG, "loadModel failed", t)
                Result.failure(if (t is Exception) t else RuntimeException(t))
            } finally {
                if (inferenceLockAcquired) {
                    inferenceMutex.unlock()
                }
            }
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        cancelFlag.set(true)
        // モデル/エンジン切り替え時の強制停止:
        //   nativeComplete / nativeCompleteWithMedia は JNI レベルで blocking なため、
        //   Kotlin 側の cancelFlag だけだとネイティブループは止まらず、
        //   inferenceMutex を取れずに unloadModel が永遠にブロックしてしまう。
        //   ctx.interrupt() (= nativeInterrupt) を先に呼んでネイティブの
        //   is_interrupted フラグを立てることで、生成ループを即座に脱出させる。
        //   その後 freeNativeCtx() で LLM 本体とマルチモーダルプロジェクター (mmproj)
        //   を含む RnLlamaContext を完全に解放する。
        runCatching { rnllamaCtx?.interrupt() }
            .onFailure { Log.w(TAG, "interrupt() before unload failed", it) }
        return try {
            inferenceMutex.withLock {
                modelMutex.withLock {
                    try {
                        freeNativeCtx()
                        lastSessionId = null
                        Log.i(TAG, "GGUF model unloaded (LLM + multimodal projector forced stop)")
                        Result.success(Unit)
                    } catch (t: Throwable) {
                        Result.failure(if (t is Exception) t else RuntimeException(t))
                    } finally {
                        cancelFlag.set(false)
                    }
                }
            }
        } catch (t: Throwable) {
            cancelFlag.set(false)
            Result.failure(if (t is Exception) t else RuntimeException(t))
        }
    }

    /**
     * LLM コンテキストとマルチモーダルプロジェクターを強制解放する。
     * nativeReleaseContext 経由で mmproj を含む全リソースを破棄する。
     */
    private fun freeNativeCtx() {
        rnllamaCtx?.release()
        rnllamaCtx = null
        loadedModelPath = null
        loadedConfig = null
    }

    /**
 * 外部から KV キャッシュを強制クリアするための公開 API。
     * Thinking トグルやセッション切り替え時に前コンテキストが残って交互動作が崩れるのを防ぐ。
     */
    fun clearKvCacheIfLoaded() {
        val ctx = rnllamaCtx ?: return
        if (!ctx.isValid) return
        val didClear = tryClearKvCacheWithInferenceLock(inferenceMutex) {
            runCatching { ctx.clearKvCache() }
                .onFailure { Log.w(TAG, "clearKvCacheIfLoaded failed", it) }
        }
        if (didClear) {
            lastSessionId = null
        }
    }

    /**
 * 「次回推論開始前に KV を強制クリア」フラグを立てる。
     *
     * ユーザー停止 / revoke 直後の「壊れた状態」に備えてコンテキストを一旦リセットさせる。
     * 次回 [inferenceWithMedia] の入り口でこのフラグを見て、立っていたら
     * `lastSessionId` を含めて KV を全クリアしてから生成を始める。
     */
    fun requestForceClearBeforeNextInference() {
        forceClearBeforeNextInference.set(true)
    }

    // ─── キャンセル ───────────────────────────────────────────────

    override suspend fun cancelInference() {
        Log.d(TAG, "cancelInference: setting cancelFlag and interrupting native completion")
        cancelFlag.set(true)
        // 「止めるボタン」 / モデル切り替えが効かなくなっていたバグ修正:
        //   nativeComplete / nativeCompleteWithMedia は JNI レベルで blocking なため、
        //   Kotlin 側の cancelFlag をいくら立てても、ネイティブの生成ループ
        //   (NezumiRnLlamaJni.cpp) はそれを見ていない。ネイティブは
        //   completion->is_interrupted だけをチェックしているため、
        //   ctx.interrupt() (= nativeInterrupt) を呼ばないと生成が自然
        //   終了するまで止まらず、ストップボタンもモデル切り替えも
        //   体感上効かなく見える。
        runCatching { rnllamaCtx?.interrupt() }
            .onFailure { Log.w(TAG, "interrupt() failed", it) }
 // v5.1 fix: ここで forceClearBeforeNextInference を自動セットしてしまうと、
        //   ユーザー停止だけでなく、モデル切替や unload、推論コードパス内部での
        //   cancelInference() も含めて「本来意図しないケース」で KV がクリアされ、
        //   Thinking やターン間のコンテキスト保持が壊れて
        //   「GGUF で Thinking が出ない」不具合につながる。
        //   force-clear は、ユーザー停止パス (ChatViewModel.stopGenerationInternal) から
        //   明示的に requestForceClearBeforeNextInference() を呼んでもらうパスに限定する。
    }

    /**
     * モデルロード時の thinking 自動有効化 (要望D 関連)。
     *
     * GGUF の `tokenizer.chat_template` を読み、テンプレート内で `enable_thinking`
     * (または `<|think|>` 制御トークン) を参照していれば thinking 対応モデルとみなし、
     * capability ストアの thinkingEnabled を自動で ON にする。
     * ユーザーが既に設定を保存済みのモデルは尊重して上書きしない。
     */
    private fun maybeAutoEnableThinkingFromChatTemplate(modelPath: String) {
        runCatching {
            val modelFile = File(modelPath)
            if (!modelFile.isFile) return@runCatching
            val template = GgufMetadataReader.readChatTemplate(modelFile) ?: return@runCatching
            val supportsThinking = template.contains("enable_thinking") ||
                template.contains("<|think|>")
            if (!supportsThinking) return@runCatching
            val current = ImportedModelCapabilityStore.get(appContext, modelPath)
            if (!current.thinkingEnabled) {
                ImportedModelCapabilityStore.set(
                    appContext,
                    modelPath,
                    current.copy(thinkingEnabled = true)
                )
                Log.i(TAG, "Auto-enabled thinking for GGUF model (chat_template references enable_thinking): $modelPath")
            }
        }.onFailure { t ->
            Log.w(TAG, "maybeAutoEnableThinkingFromChatTemplate failed for $modelPath", t)
        }
    }

    data class GgufChatParseResult(
        val content: String,
        val reasoningContent: String
    )

    /** Whether a GGUF chat template has been successfully applied to the current context. */
    fun hasGgufChatTemplate(): Boolean {
        val context = rnllamaCtx ?: return false
        return runCatching { context.hasGgufChatTemplate() }.getOrDefault(false)
    }

    /** Parse output using the parser selected by the loaded GGUF chat template. */
    fun parseWithGgufChatTemplate(output: String, isPartial: Boolean): GgufChatParseResult? {
        // テンプレート未適用 (native 側が "{}" しか返せない) 場合と空出力を区別するため、
        // テンプレートが無効なら null を返して Kotlin パーサーへフォールバックさせる。
        if (!hasGgufChatTemplate()) return null
        val context = rnllamaCtx ?: return null
        return runCatching {
            val json = org.json.JSONObject(context.parseGgufChatOutput(output, isPartial))
            GgufChatParseResult(
                content = json.optString("content", ""),
                reasoningContent = json.optString("reasoning_content", "")
            )
        }.getOrNull()
    }

    /** Render OpenAI-compatible messages with the loaded GGUF chat template. */
    suspend fun formatWithGgufChatTemplate(
        messagesJson: String,
        enableThinking: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val context = rnllamaCtx ?: return@withContext ""
        context.applyGgufChatTemplate(
            messagesJson = messagesJson,
            enableThinking = enableThinking,
            addGenerationPrompt = true
        )
    }

    /**
     * Render OpenAI-compatible messages with an explicit Jinja chat template
     * (Hugging Face chat_template compatible). ユーザー選択のカスタム/ビルトイン
     * テンプレート用。成功時はネイティブ側にパーサーも設定される。
     */
    suspend fun formatWithJinjaChatTemplate(
        messagesJson: String,
        chatTemplate: String,
        enableThinking: Boolean
    ): String = withContext(Dispatchers.IO) {
        val context = rnllamaCtx ?: return@withContext ""
        context.applyJinjaChatTemplate(
            messagesJson = messagesJson,
            chatTemplate = chatTemplate,
            enableThinking = enableThinking,
            addGenerationPrompt = true
        )
    }

    // ─── 推論 ─────────────────────────────────────────────────────

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    /**
     * GGUF推論。images / audioClips はネイティブ側 (libmtmd) がサポートする場合のみ利用。
     *
     * 音声は一時 WAV ファイル化して nativeCompleteWithMedia() の mediaPaths に
     * 画像パスと混ぜて渡す。JNI 経由で libmtmd (mtmd_helper_bitmap_init_from_file)
     * が magic bytes (RIFF/MP3/fLaC) を自動判定し、miniaudio でモデル要求
     * サンプルレート (mtmd_get_audio_sample_rate) へリサンプルするため、
     * Kotlin 側では MediaCodec デコードだけ行いリサンプルはネイティブに任せる。
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
        // Bug fix: 推論停止を短時間に繰り返すと、ネイティブ側の is_interrupted
        //   フラグがクリアされないまま蓄積し、「押していないのに次回推論が
        //   即座に停止される」状態になっていた。推論開始前に必ずクリアする。
        runCatching { rnllamaCtx?.clearInterrupt() }
            .onFailure { Log.w(TAG, "clearInterrupt before inference failed", it) }

        val ctx = rnllamaCtx
        if (ctx == null || !ctx.isValid) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val normalized = config.normalized()

        try {
 // 前回推論がユーザー停止 / revoke / 例外で途中で折れた場合に備えて、
            //   「次回の推論では必ずコンテキストを一旦リセット」してから始める。
            //   途中までの assistant 出力とその終端トークン欠落による「壊れた出力」を防ぐため、
            //   このフラグが立っていたら無条件で KV と lastSessionId をリセットする。
            if (forceClearBeforeNextInference.getAndSet(false)) {
                Log.d(TAG, "forceClearBeforeNextInference flag set, clearing KV cache before inference")
                withContext(Dispatchers.IO) {
                    runCatching { ctx.clearKvCache() }
                        .onFailure { Log.w(TAG, "force clearKvCache before inference failed", it) }
                }
                lastSessionId = null
            }

            // セッション変更時は KV キャッシュをクリア
            if (lastSessionId != sessionId) {
                Log.d(TAG, "Session changed: $lastSessionId → $sessionId, clearing KV cache")
                withContext(Dispatchers.IO) {
                    ctx.clearKvCache()
                }
                lastSessionId = sessionId
            }

            Log.d(TAG, "GGUF inference start: session=$sessionId promptLen=${prompt.length} images=${images.size}")

            val fullAnswer = StringBuilder()
            var currentPrompt = prompt
            val toolCallingEnabled = normalized.enableToolCalling
            val maxToolRounds = if (toolCallingEnabled) 5 else 1
            var toolRound = 0
            var isFirstGenerationRound = true
            // Gemma 4 判定: モデルパスから 1 回だけ決定してツールループ内で使い回す。
            // GgufToolCallParser.parse / formatToolResults を Gemma 4 形式
            // (<|tool_call>call:NAME{...}<tool_call|>) に切り替えるためのフラグ。
            val isGemma4 = PromptBuilder.isGemma4Model(ctx.modelPath)

            val toolResultCards = mutableListOf<ToolResultCard>()
            while (isActive && toolRound < maxToolRounds) {
                toolRound++
                val roundText = generateRound(
                    ctx = ctx,
                    sessionId = sessionId,
                    prompt = currentPrompt,
                    config = normalized,
                    isFirstRound = isFirstGenerationRound,
                    emitChunk = { chunk -> trySend(chunk) },
                    images = images,
                    audioClips = audioClips
                )
                isFirstGenerationRound = false

                if (!toolCallingEnabled) {
                    // インライン表示対応: <tool_call> タグは本文に保持したまま sanitize する。
                    // ツール無効時はパースを走らず、そのまま本文を積むだけ。
                    fullAnswer.append(
                        Gemma4ThinkingParser.sanitizeVisibleText(
                            roundText,
                            preserveToolCallTags = false
                        )
                    )
                    break
                }

                val parsed = GgufToolCallParser.parse(roundText, isGemma4 = isGemma4)

                // トークン切れ検知:
                //   モデルが <tool_call> を開いたのに JSON 引数の途中でトークン予算切れ/停止シーケンス
                //   にかかってしまったケース。以前はここで黙って break していたため、UI の
                //   インラインカードが Running のまま永久に残り、モデルも次ターンでも何が起きたのか
                //   分からない状態になっていた。強制的に </tool_call> (または Gemma 4 なら <tool_call|>) で
                //   タグを閉じ、失敗ステータスの ToolResultCard + <tool_response> を合成して
                //   モデルに戻すことで、モデルが「今の呼び出しは失敗した」と認識して自然に
                //   立て直せるようにする。
                val truncationDetected = parsed.hadTruncatedToolCall
                val closingTag = GgufToolCallParser.closingTagFor(parsed.truncatedTagIsGemma4)
                val normalizedRoundText = if (truncationDetected) {
                    // 本文末尾に閉じタグを補完して、DB / 履歴プロンプトのタグ整合を保つ。
                    // 行末の閉じタグの前後に改行を入れて、后続の <tool_response> と行境を分ける。
                    buildString {
                        append(roundText)
                        if (!roundText.endsWith("\n")) append("\n")
                        append(closingTag)
                        append("\n")
                    }
                } else {
                    roundText
                }

                // インライン表示対応: <tool_call> タグは本文に保持したまま
                // sanitize する。UI 側で GgufToolCallParser.parseSegments() を使って
                // セグメント化し、タグの位置でカードをインライン描画するため。
                fullAnswer.append(
                    Gemma4ThinkingParser.sanitizeVisibleText(
                        normalizedRoundText,
                        preserveToolCallTags = true
                    )
                )

                // 実行対象のツールもなく、トークン切れもなければ通常の回答としてループを抜ける。
                if (parsed.toolCalls.isEmpty() && !truncationDetected) {
                    break
                }
                if (cancelFlag.get()) {
                    break
                }
                if (toolRound >= maxToolRounds) {
                    Log.w(TAG, "Tool call loop exceeded max rounds, breaking session=$sessionId")
                    break
                }

                if (parsed.toolCalls.isNotEmpty()) {
                    trySend(
                        InferenceStreamProtocol.encodeToolCallChunk(parsed.toolCalls.map { it.name })
                    )
                }
                val toolResults = mutableListOf<Pair<ToolCall, ToolExecutionResult>>()
                for (toolCall in parsed.toolCalls) {
                    val result = toolExecutor.execute(toolCall)
                    toolResults.add(toolCall to result)
                    val status = if (result.success) "success" else "error"
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(toolCall.name, status))
                    synchronized(toolResultCards) {
                        toolResultCards.add(
                            ToolResultCard(
                                toolName = toolCall.name.lowercase(),
                                success = result.success,
                                payload = anyToJsonElementMap(result.payload)
                            )
                        )
                    }
                }

                // トークン切れの失敗カードを合成して UI / モデル双方に通知する。
                //   UI: toolResultCards に success=false カードを追加
                //   モデル: <tool_response> として currentPrompt / fullAnswer に埋め込み、
                //     「前回のツール呼び出しはトークン予算不足で中断した」ことを伝える。
                val truncatedResponseBlock = if (truncationDetected) {
                    val card = GgufToolCallParser.buildTruncatedFailureCard(parsed.truncatedToolName)
                    synchronized(toolResultCards) { toolResultCards.add(card) }
                    trySend(InferenceStreamProtocol.encodeToolResultChunk(card.toolName, "error"))
                    Log.w(
                        TAG,
                        "Tool call truncated (token budget exhausted): name=${parsed.truncatedToolName} " +
                            "gemma4=${parsed.truncatedTagIsGemma4} session=$sessionId round=$toolRound"
                    )
                    GgufToolCallParser.formatTruncatedFailureResponse(parsed.truncatedToolName)
                } else {
                    ""
                }

                // バグ修正 (tool_response が履歴コンテキストに入らない):
                //   本文には `<tool_call>...</tool_call>` だけが残っており、対応する `<tool_response>`
                //   タグはモデルへの次ラウンド入力 (currentPrompt) にしか入らないため、DB に保存される
                //   assistant.content を次ターンのプロンプトへ再構築した際に「モデルがどのツールを呼んで
                //   何が返ったか」の対応関係が完全に失われる。同じ formatToolResults を fullAnswer にも埋め込むことで、
                //   タグごと保存され・UI の InlineToolCallCard は依然として展開時に `card.payload` を見て
                //   result を描画できるので見た目は変わらない。
                val toolResponseBlock = buildString {
                    append(GgufToolCallParser.formatToolResults(toolResults, isGemma4 = isGemma4))
                    append(truncatedResponseBlock)
                }
                if (toolResponseBlock.isNotEmpty()) {
                    fullAnswer.append(toolResponseBlock)
                }

                if (toolResultCards.isNotEmpty()) {
                    val toolResultsJson = ToolResultCard.listToJsonArray(toolResultCards)
                    trySend(InferenceStreamProtocol.encodeToolResults(toolResultsJson))
                    trySend(
                        InferenceStreamProtocol.encodeExecutedToolsList(
                            toolResultCards.map { it.toolName }.distinct()
                        )
                    )
                }

                currentPrompt = buildString {
                    append(prompt)
                    append(Gemma4ThinkingParser.stripThinkingForModelPrompt(normalizedRoundText))
                    append(toolResponseBlock)
                }
                withContext(Dispatchers.IO) {
                    ctx.clearKvCache()
                }
                lastSessionId = null
            }

            if (toolResultCards.isNotEmpty()) {
                val toolResultsJson = ToolResultCard.listToJsonArray(toolResultCards)
                trySend(InferenceStreamProtocol.encodeToolResults(toolResultsJson))
                trySend(
                    InferenceStreamProtocol.encodeExecutedToolsList(
                        toolResultCards.map { it.toolName }.distinct()
                    )
                )
            }

            // ネイティブ timings から t/s を送出 (UI 表示用)。
            // トークン数・時間ともに llama.cpp が測っているため、
            // Kotlin 側の概算よりも正確。
            runCatching {
                ctx.getLastTimings()?.decodeTokensPerSecond?.let { tps ->
                    if (tps > 0f) trySend(InferenceStreamProtocol.encodeTps(tps))
                }
            }.onFailure { Log.w(TAG, "emit TPS failed", it) }

            trySend(
                InferenceStreamProtocol.encodeFinal(
                    Gemma4ThinkingParser.sanitizeVisibleText(
                        fullAnswer.toString(),
                        preserveToolCallTags = toolCallingEnabled
                    )
                )
            )
            close()
        } catch (t: Throwable) {
            if (t is CancellationException) {
                // キャンセル時も可能なら timings を送出しておく
                runCatching {
                    rnllamaCtx?.getLastTimings()?.decodeTokensPerSecond?.let { tps ->
                        if (tps > 0f) trySend(InferenceStreamProtocol.encodeTps(tps))
                    }
                }
                // キャンセル時も final を送出（LiteRT 側と同じ挙動）
                trySend(InferenceStreamProtocol.encodeFinal(""))
                close()
            } else {
                Log.e(TAG, "GGUF inference error: session=$sessionId", t)
                close(if (t is Exception) t else RuntimeException(t))
            }
        } finally {
            // 本来の終了処理が起きないバグ修正:
            //   以前は endInference() が try ブロックの正常パスにしか置かれておらず、
            //   キャンセル例外や他の例外で抹けると PerformanceMonitor の
            //   activeMetrics にセッションが残留し、以降の推論で
            //   getLastCompletedTokenCount() が間違ったセッションの値を
            //   返して、ChatViewModel 側の t/s 計算がずれる。
            //   finally に移して必ず 1 回呼ぶ。
            runCatching {
                val metrics = PerformanceMonitor.endInference(sessionId)
                if (metrics != null) {
                    Log.i(TAG, "Performance: ${metrics.toLogString()}")
                }
            }.onFailure { Log.w(TAG, "endInference failed", it) }
            releaseInferenceMutex()
        }

        awaitClose {
            Log.d(TAG, "awaitClose: session=$sessionId")
            cancelFlag.set(true)
            // Flow が消費側からキャンセルされたとき（ストップボタン等）も
            // ネイティブの blocking JNI 呼び出しを即座に脱出させる。
            runCatching { rnllamaCtx?.interrupt() }
                .onFailure { Log.w(TAG, "awaitClose interrupt() failed", it) }
        }
    }.flowOn(Dispatchers.IO)

    // ─── ユーティリティ ──────────────────────────────────────────

    override suspend fun isAvailable(): Boolean = rnllamaCtx?.isValid == true

    private suspend fun generateRound(
        ctx: RnLlamaContext,
        sessionId: Long,
        prompt: String,
        config: InferenceConfig,
        isFirstRound: Boolean,
        emitChunk: (String) -> Unit,
        images: List<Bitmap>,
        audioClips: List<ByteArray>
    ): String = withContext(Dispatchers.IO) {
        if (isFirstRound) {
            PerformanceMonitor.startInference(sessionId, config.backendType, 0)
        }

        val stopSequences = effectiveStopSequences(config)
        val nativeSettings = resolveNativeGenerationSettings(ctx.modelPath, config, appContext)
        val maxTokens = config.maxTokens.coerceAtMost(nativeSettings.maxTokensCap)

        Log.d(TAG, "generateRound: maxTokens=$maxTokens, temperature=${config.temperature}, topP=${config.topP}, topK=${config.maxTopK}")

        // 画像を一時ファイルに保存
        val imagePaths = if (images.isNotEmpty()) {
            images.mapIndexed { index, bitmap ->
                val tempFile = File(appContext.cacheDir, "temp_img_${System.currentTimeMillis()}_$index.jpg")
                tempFile.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                tempFile.absolutePath
            }
        } else {
            emptyList()
        }

        // 音声を一時 WAV ファイルに保存。
        //
        // 非対応モデルでは明示的に拒否する。mtmd 内部でも audio 非対応時は
        // bitmap 生成に失敗するが、ここで事前に弾くことで「音声を捨てて
        // テキストだけ回答する」誤動作を防ぎ、ユーザーへ原因を伝えられる。
        val audioPaths = if (audioClips.isNotEmpty()) {
            if (!ctx.isAudioSupported) {
                imagePaths.forEach { File(it).delete() }
                throw IllegalStateException(
                    "Audio input is not supported by this model (GGUF backend). " +
                        "Load a model with an audio-capable mmproj."
                )
            }
            audioClips.mapIndexedNotNull { index, clip ->
                // 任意 codec (m4a/aac/mp3/ogg 等) を 16-bit PCM WAV へデコード。
                // リサンプルはネイティブ (miniaudio) が行うため、ここでは
                // decode + WAV 化のみを行う軽量ヘルパーを使う。
                val wavBytes = LlmMultimodalAudioHelper.decodeToPcmWav(appContext, clip)
                if (wavBytes == null || wavBytes.isEmpty()) {
                    Log.w(TAG, "generateRound: audio decode failed at index=$index; skipping")
                    null
                } else {
                    val tempFile = File(appContext.cacheDir, "temp_audio_${System.currentTimeMillis()}_$index.wav")
                    tempFile.outputStream().use { it.write(wavBytes) }
                    tempFile.absolutePath
                }
            }
        } else {
            emptyList()
        }

        // 画像 + 音声を 1 本の mediaPaths にまとめる。
        // libmtmd 側でファイル内容から画像/音声が自動ルーティングされる。
        val mediaPaths = (imagePaths + audioPaths).toTypedArray()

        // 推論実行（ブロッキング呼び出し）
        //
        // ストリーミング対応:
        //   nativeComplete() / nativeCompleteWithMedia() は JNI レベルでは
        //   blocking だが、内部で sendToken() を介して 1 トークンずつ
        //   token_callback を呼んでくれる。ここで onToken ラムダを渡すと
        //   各トークンがそのまま emitChunk() に流れるため、UI 側は
        //   推論完了を待たずにリアルタイムで応答を表示できる。
        //
        //   以前はコールバックを設定せず、推論完了後に result 文字列を
        //   CHUNK_SIZE 単位で疑似ストリーム化していたため、特に画像入力時は
        //   何分もの間 UI がフリーズして見えていた (= リアルタイム応答が
        //   出力されないバグ)。コールバック経由に切り替えたので、戻り値の
        //   result はもう UI には流さない。
        Log.d(TAG, "generateRound: Starting inference, imagePaths.size=${imagePaths.size}, audioPaths.size=${audioPaths.size}, prompt.length=${prompt.length}")
        // t/s (トークン/秒) が表示されないバグ修正:
        //   以前は streamCallback から PerformanceMonitor.recordToken() を
        //   一切呼んでいなかったため、totalTokens がずっと 0 のままだった。
        //   ChatViewModel 側は manager.getLastGenerationTokenCount() の値で
        //   t/s を計算しているため、トークン数が 0 だと計算結果が
        //   null になり、MessageAdapter で "t/s" ラベルが表示されない。
        //   ストリーミングコールバックでトークンをカウントするようにした。
        val streamCallback: (String) -> Unit = { token ->
            if (isActive && token.isNotEmpty()) {
                PerformanceMonitor.recordToken(sessionId)
                emitChunk(token)
            }
        }
        val result = try {
            if (mediaPaths.isNotEmpty()) {
                ctx.completeWithMedia(
                    prompt = prompt,
                    nPredict = maxTokens,
                    temperature = config.temperature,
                    topP = config.topP,
                    topK = config.maxTopK,
                    stopWords = stopSequences.toTypedArray(),
                    mediaPaths = mediaPaths,
                    onToken = streamCallback
                )
            } else {
                ctx.setTokenCallback(streamCallback)
                try {
                    ctx.complete(
                        prompt = prompt,
                        nPredict = maxTokens,
                        temperature = config.temperature,
                        topP = config.topP,
                        topK = config.maxTopK,
                        stopWords = stopSequences.toTypedArray()
                    )
                } finally {
                    // 古いラムダが次ラウンドに残らないよう確実にクリア。
                    ctx.setTokenCallback(null)
                }
            }
        } finally {
            // 一時ファイルを削除（例外/キャンセル時にも必ず実行）
            imagePaths.forEach { File(it).delete() }
            audioPaths.forEach { File(it).delete() }
        }
        Log.d(TAG, "generateRound: Inference completed, result.length=${result.length}")

        result
    }

    private fun anyToJsonElementMap(values: Map<String, Any?>): Map<String, JsonElement> {
        return values.entries.associate { (key, value) ->
            key to anyToJsonElement(value)
        }
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> {
                val obj = value.entries.mapNotNull { (k, v) ->
                    val key = k?.toString() ?: return@mapNotNull null
                    key to anyToJsonElement(v)
                }.toMap()
                JsonObject(obj)
            }
            is List<*> -> {
                val elements = value.map { anyToJsonElement(it) }
                kotlinx.serialization.json.JsonArray(elements)
            }
            else -> JsonPrimitive(value.toString())
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

    private fun hasGgufMagicHeader(file: File): Boolean {
        return try {
            val header = ByteArray(4)
            file.inputStream().use { it.read(header) }
            header[0] == 'G'.code.toByte() &&
                header[1] == 'G'.code.toByte() &&
                header[2] == 'U'.code.toByte() &&
                header[3] == 'F'.code.toByte()
        } catch (e: Exception) {
            Log.w(TAG, "hasGgufMagicHeader: failed to read header for ${file.absolutePath}", e)
            false
        }
    }
}