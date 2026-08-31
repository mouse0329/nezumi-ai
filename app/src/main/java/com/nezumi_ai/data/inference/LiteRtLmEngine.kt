package com.nezumi_ai.data.inference

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.SamplerConfig
import com.nezumi_ai.data.database.NezumiAiDatabase
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

/**
 * Gemma / LiteRT-LM 推論（[com.google.ai.edge.litertlm]）。
 * AI Edge Gallery の [LlmChatModelHelper] と同様に Engine + Conversation で推論し、
 * thought チャンネルを [InferenceStreamProtocol.encodeThinkChunk] で送出する。
 */
@OptIn(ExperimentalApi::class)
class LiteRtLmEngine(
    private val appContext: Context
) : AIInferenceEngine {

    companion object {
        private const val TAG = "LiteRtLmEngine"
        /** 手動で選ばれたスチル画像の上限 (UI と揃える) */
        private const val MAX_VISION_IMAGES = 5
        /**
         * 動画に由来するフレーム列を受け入れる上限。 30 秒 × 1fps = 30。
         * コール経路 (フレームメント側) で展開されたフレームはこの上限まで通す。
         */
        private const val MAX_VIDEO_FRAMES = 30
        private const val MAX_BITMAP_EDGE = 1024
        private const val THOUGHT_CHANNEL = "thought"

        /**
         * モデル初期化はネイティブ側が重い。メイン・Default 共有プールを避け、1 本の IO ワーカーに直列化する。
         */
        private val modelLoadDispatcher = Dispatchers.IO.limitedParallelism(1)

        private fun shouldEmitPartialText(partial: String): Boolean {
            if (partial.isEmpty()) return false
            val t = partial.trimStart()
            return !t.startsWith("<ctrl", ignoreCase = true)
        }
    }

    private fun backendTypeLabel(backend: Backend): String {
        return when (backend) {
            is Backend.GPU -> "GPU"
            is Backend.NPU -> "NPU"
            else -> "CPU"
        }
    }

    private fun multimodalUnavailableException(detail: String): IllegalStateException {
        return IllegalStateException(
            "LiteRT-LM multimodal is unavailable: $detail. " +
                "Android では visionBackend=GPU を優先し、AndroidManifest.xml の <uses-native-library> に " +
                "libvndksupport.so / libOpenCL.so（NPU 利用時は libcdsprpc.so も）を宣言してください。"
        )
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var loadedConfig: InferenceConfig? = null
    private var loadedBackend: String? = null  // Phase 11: GPU/CPU/NPU バックエンド追跡（キャッシュ無効化用）
    @Volatile private var loadedWithVisionAudio: Boolean = false
    private var disableXnnpackCacheForProcess: Boolean = false
    private val modelMutex = Mutex()
    private val inferenceMutex = Mutex()
    private val inferenceMutexHeld = AtomicBoolean(false)
    @Volatile private var npuNativeLibraryDirChecked = false
    @Volatile private var cachedNpuNativeLibraryDir: String? = null
    // Timestamp (ms) of last critical engine init failure. Used to apply short backoff
    private val lastCriticalInitFailureMs = AtomicLong(0)
    private val ENGINE_INIT_BACKOFF_MS = 5_000L
    
    // ─────────────────────────────────────────────────────────────────────────
    // Phase 14: 抽出推論の割り込み検出メカニズム
    // ─────────────────────────────────────────────────────────────────────────
    
    /** 通常推論がリクエストされていることを示すフラグ。抽出推論がこれを見て自分をキャンセル */
    @Volatile private var normalInferenceRequested = false
    
    private val alarmDao by lazy { NezumiAiDatabase.getInstance(appContext).alarmDao() }
    private val memoryRepository: com.nezumi_ai.data.repository.MemoryRepository by lazy { 
        val db = NezumiAiDatabase.getInstance(appContext)
        com.nezumi_ai.data.repository.MemoryRepository(db.memoryDao())
    }
    private val toolExecutor by lazy { 
        NezumiLiteRtToolExecutor(appContext, alarmDao, memoryRepository, MemoryTextEmbedder)
    }

    /** Engine は同時に 1 セッションのみ。コールバックスレッドと awaitClose の競合もここで直列化する */
    private val activeConversationLock = Mutex()
    /** activeConversationLock で保護。@Volatile 不要（全アクセスが synchronized 内） */
    private var activeLiteRtConversation: Conversation? = null
    
    private data class ConversationKey(
        val sessionId: Long,
        val enableThinking: Boolean,
        val enableToolCalling: Boolean,
        /** ビルトインツールの有効 ID 集合の指紋。ツールの ON/OFF を切り替えたら Conversation を作り直す。 */
        val builtinToolsFingerprint: String,
        /** MCP ツール集合の指紋。サーバー追加/削除や tools/list_changed で変わる。 */
        val mcpToolsFingerprint: String,
        /**
         * ToolPreferences の revision カウンタ。
         *
         * これを ConversationKey に混ぜることで、モデル再ロード不要で
         * 「ツール構成が変わった → Conversation を作り直す → buildEnabledToolProviders() が再収集される」
         * という動的登録の効果が得られる。ツールの見た目・名前・スキーマは今まで通り維持したまま、
         * 反映のタイミングだけを動的化する。
         */
        val toolPrefsRevision: Int,
        /**
         * Bug fix (LiteRT 二重テンプレート対策): 構造化ペイロード経路では
         * system instruction の内容が変わったら会話を作り直す必要がある
         * (KV キャッシュの先頭が変わるため)。
         */
        val systemInstruction: String = ""
    )

    /**
     * 構造化ペイロード経路の「会話作り直し → 送信」間の競合を防ぐロック。
     * (同一セッションでも内部推論と本流が並走し得るため直列化する)
     */
    private val structuredConversationLock = Mutex()

    /** セッション遷移検出用 */
    @Volatile
    private var activeLiteRtConversationKey: ConversationKey? = null
    
    // ─────────────────────────────────────────────────────────
    // Phase 11: リソース管理の統合
    // ─────────────────────────────────────────────────────────
    
    /** メモリ監視 */
    private val memoryObserver = MemoryObserver
    
    /** Bitmap メモリプール */
    private val bitmapPool = BitmapMemoryPool()
    
    /** セッションリソース管理 */
    private val sessionManager = SessionResourceManager()
    
    /** Coroutine/Job 管理 */
    private val jobController = InferenceJobController()
    
    /** バックエンドリソース管理 */
    private val backendResourceManager = BackendResourceManager()

    // ─────────────────────────────────────────────────────────
    // Phase 13: メモリ抽出専用セッション（再ロードなし）
    // ─────────────────────────────────────────────────────────
    
    /** メモリ抽出用専用セッション（Thinking=OFF, JSON確定用） */
    @Volatile
    private var memoryExtractionConversation: Conversation? = null
    @Volatile
    private var memoryExtractionConversationConfig: InferenceConfig? = null

    /**
     * AI Edge Gallery 方式：推論キャンセル時は cancelProcess() だけ。
     * close() はセッション遷移時のみ。
     */
    private fun cancelConversation(conversation: Conversation?) {
        if (conversation == null) return
        runCatching {
            Log.d(TAG, "Cancelling conversation process")
            conversation.cancelProcess()
        }.onFailure { t ->
            Log.w(TAG, "Failed to cancel conversation process", t)
        }
    }

    private suspend fun cancelActiveConversation() {
        activeConversationLock.withLock {
            cancelConversation(activeLiteRtConversation)
        }
    }

    private suspend fun cancelMemoryExtractionConversation() {
        activeConversationLock.withLock {
            cancelConversation(memoryExtractionConversation)
        }
    }

    /**
     * awaitClose など suspend 不可のコンテキスト向け。
     * ロックなしで cancelProcess() のみ直接呼ぶ（runBlocking 禁止）。
     */
    private fun cancelActiveConversationNonSuspend() {
        runCatching { activeLiteRtConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelActiveConversationNonSuspend failed", it) }
    }

    private fun cancelMemoryExtractionConversationNonSuspend() {
        runCatching { memoryExtractionConversation?.cancelProcess() }
            .onFailure { Log.w(TAG, "cancelMemoryExtractionConversationNonSuspend failed", it) }
    }

    /**
     * セッション遷移時のみ使用。古い Conversation を close() して新たに作成する。
     */
    private suspend fun closeAndResetActiveConversation(sessionId: Long? = null) {
        activeConversationLock.withLock {
            val c = activeLiteRtConversation ?: return
            activeLiteRtConversation = null
            activeLiteRtConversationKey = null
            
            runCatching {
                Log.d(TAG, "Closing active conversation")
                // 1. まずネイティブプロセスをキャンセル
                c.cancelProcess()
                
                // 2. セッションIDが指定されている場合、関連タスクのキャンセルを待機
                if (sessionId != null) {
                    jobController.cancelSessionTasks(sessionId)
                    // ネイティブ側がキャンセルを処理し、スレッドが安全に停止するまでわずかに待機
                    // (SIGSEGV 回避のための安全策)
                    Thread.sleep(50) 
                }
                
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close conversation", t)
            }
            Log.i(TAG, "Active conversation closed and reset")
        }
    }

    private suspend fun closeAndResetMemoryExtractionConversation() {
        activeConversationLock.withLock {
            val c = memoryExtractionConversation ?: return
            memoryExtractionConversation = null
            memoryExtractionConversationConfig = null
            runCatching {
                Log.d(TAG, "Closing memory extraction conversation")
                c.close()
            }.onFailure { t ->
                Log.w(TAG, "Failed to close memory extraction conversation", t)
            }
            Log.i(TAG, "Memory extraction conversation closed and reset")
        }
    }

    /**
     * セッションIDに基づいて Conversation を取得または新規作成する。
     * 同一セッション内では Conversation と KVキャッシュを再利用し、レスポンス速度を向上させる。
     *
     * @param sessionId 現在のセッションID
     * @param eng 初期化済みの Engine インスタンス
     * @param config 推論設定（サンプラーパラメータなど）
     * @return Conversation インスタンス
     */
    /**
     * Bug fix(#5): マルチモーダル入力が含まれたセッションでは KV キャッシュを使い回すと
     * 過去ターンの画像/音声が参照できなくなる。「一度でも media を受け取ったセッション」では
     * conversation を毎ターン作り直す。 ChatViewModel 側から過去の media を再添付して
     * 多ターン参照できるようにする。
     */
    private val sessionsWithMediaHistory = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun markSessionHasMedia(sessionId: Long) {
        sessionsWithMediaHistory.add(sessionId)
    }

    fun clearSessionMediaHistory(sessionId: Long) {
        sessionsWithMediaHistory.remove(sessionId)
    }

    private suspend fun getOrCreateConversation(
        sessionId: Long,
        eng: Engine,
        config: InferenceConfig,
        structuredPayload: LiteRtStructuredPrompt.Payload? = null
    ): Conversation {
        val normalized = config.normalized()
        val builtinFp = if (normalized.enableToolCalling) {
            ToolPreferences(appContext).getEnabledTools()
                .map { it.name }.toSortedSet().joinToString(",").ifBlank { "none" }
        } else {
            "disabled"
        }
        val mcpFp = if (normalized.enableToolCalling) {
            val registry = com.nezumi_ai.data.mcp.McpToolRegistry.get(appContext)
            val activeMcpIds = ToolPreferences(appContext).getActiveMcpServerIds()
            // レジストリ未初期化 (アプリ再起動直後など) でアクティブサーバーがある場合、
            // Conversation のツール定義付与前にここで同期リフレッシュさせることで、
            // 初回ターンから MCP ツールが使えるようにする。
            if (registry.currentTools().isEmpty() && activeMcpIds.isNotEmpty()) {
                Log.d(TAG, "MCP registry empty but ${activeMcpIds.size} server(s) active - syncing")
                runCatching {
                    kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                        registry.refresh(activeMcpIds, force = true)
                    }
                }
            }
            registry.fingerprint()
        } else {
            "mcp:disabled"
        }
        val requestKey = ConversationKey(
            sessionId,
            normalized.enableThinking,
            normalized.enableToolCalling,
            builtinFp,
            mcpFp,
            // v2.1+: ツール ON/OFF / プリセット切替 / MCP サーバー集合変更を revision で拾い、
            //        モデル再ロード無しに Conversation だけを作り直せるようにする。
            ToolPreferences.currentRevision(),
            // 構造化経路: system instruction の変更も Conversation 再作成トリガに含める。
            systemInstruction = structuredPayload?.systemInstruction ?: ""
        )
        // Bug fix(#5): media を含むセッションでは KV キャッシュ再利用をやめる。
        val mustRecreateForMedia = sessionsWithMediaHistory.contains(sessionId)
        var convToAttach: Conversation? = null
        var created = false
        val maxAttempts = 6
        var attempt = 0
        while (!created && attempt < maxAttempts) {
            attempt++
            
            // ロックの外でリソースをクリーンアップ
            if (memoryExtractionConversation != null) {
                Log.i(TAG, "Closing existing memory extraction conversation before creating new one")
                closeAndResetMemoryExtractionConversation()
            }

            // 現在のセッション情報を取得してロック外でクローズを呼ぶ
            val currentSessionId = activeConversationLock.withLock { activeLiteRtConversationKey?.sessionId }
            if (currentSessionId != null) {
                closeAndResetActiveConversation(currentSessionId)
            }

            activeConversationLock.withLock {
                // セッションIDまたはThinking設定が変わった場合は新しいConversationを作成
                if (activeLiteRtConversation == null || activeLiteRtConversationKey != requestKey || mustRecreateForMedia) {
                    val samplerConfig = if (normalized.backendType.uppercase() == "NPU") {
                        null
                    } else {
                        SamplerConfig(
                            topK = normalized.maxTopK,
                            topP = normalized.topP.toDouble(),
                            temperature = normalized.temperature.toDouble()
                        )
                    }

                    // TQ導入: GPU利用時のみ投機的デコーディングを許可（NPUとの競合を防ぐ）
                    val canUseSpeculative = normalized.enableSpeculativeDecoding &&
                        normalized.backendType.uppercase() == "GPU"
                    ExperimentalFlags.enableSpeculativeDecoding = canUseSpeculative

                    try {
                        val tools = if (normalized.enableToolCalling) {
                            buildEnabledToolProviders(appContext, alarmDao)
                        } else {
                            emptyList()
                        }
                        // v2.1+: 実機で「ツールが LiteRT-LM に届いたか」を追えるように詳細ログを出す。
                        // tools が empty のまま LLM に渡ってしまう典型的な失敗 (
                        //   InferenceConfig.enableToolCalling=false / ToolPreferences 空 /
                        //   presetToolCallingOn=false / capability off など) を切り分けやすくする。
                        Log.i(
                            TAG,
                            "createConversation: enableToolCalling=${normalized.enableToolCalling} " +
                                "builtinToolProviders=${tools.size} " +
                                "enabledNezumiTools=${ToolPreferences(appContext).getEnabledTools().map { it.name }} " +
                                "toolPrefsRevision=${ToolPreferences.currentRevision()} " +
                                "sessionId=$sessionId"
                        )
                        // Bug fix (LiteRT 二重テンプレート対策):
                        //   構造化ペイロード経路では systemInstruction / initialMessages を
                        //   ConversationConfig へ渡し、テンプレート適用はエンジンに委ねる。
                        //   従来経路 (null) では渡さず、従来動作を維持する。
                        //
                        // Bug fix: Message.of(text) は @Deprecated であり、実装は常に
                        //   Message.user(text) のエイリアス (= Role.USER 固定) になっている。
                        //   これを assistant (model) ターンの履歴にも使っていたため、
                        //   Conversation API に渡る initialMessages が「全ターン USER ロール」に
                        //   なってしまい、エンジン側のチャットテンプレート適用時に
                        //   user/model のロール境界が失われ、ロールタグや発話が本文へ
                        //   混入する不具合 (見かけ上「テンプレートが二重にかかっている」ような
                        //   壊れた出力) を引き起こしていた。
                        //   Message.user() / Message.model() を明示的に使い分けて正しいロールで渡す。
                        val systemInstructionContents = structuredPayload?.let {
                            if (it.systemInstruction.isBlank()) null
                            else Contents.of(Content.Text(it.systemInstruction))
                        }
                        val initialMessages = structuredPayload?.let { payload ->
                            payload.history.map { turn ->
                                val content = Content.Text(turn.content)
                                if (turn.role == "model") {
                                    Message.model(Contents.of(content))
                                } else {
                                    Message.user(Contents.of(content))
                                }
                            }
                        }
                        val conv = try {
                            eng.createConversation(
                                ConversationConfig(
                                    systemInstruction = systemInstructionContents,
                                    initialMessages = initialMessages ?: emptyList(),
                                    tools = tools,
                                    samplerConfig = samplerConfig,
                                    automaticToolCalling = false
                                )
                            )
                        } catch (toolErr: Throwable) {
                            if (tools.isEmpty()) throw toolErr
                            Log.w(TAG, "createConversation with tools failed; retrying without tools", toolErr)
                            eng.createConversation(
                                ConversationConfig(
                                    systemInstruction = systemInstructionContents,
                                    initialMessages = initialMessages ?: emptyList(),
                                    tools = emptyList(),
                                    samplerConfig = samplerConfig,
                                    automaticToolCalling = false
                                )
                            )
                        }
                        activeLiteRtConversation = conv
                        activeLiteRtConversationKey = requestKey
                        convToAttach = conv
                        Log.d(TAG, "New conversation created for key=$requestKey, KVCache initialized")
                        created = true
                    } catch (t: Throwable) {
                        Log.w(TAG, "createConversation failed on attempt $attempt: ${t.message}")
                        // もしネイティブ側で既存セッションが残っている旨のエラーなら、短い遅延ののち再試行
                        if (attempt < maxAttempts && t.message?.contains("session already exists", ignoreCase = true) == true) {
                            // leave synchronized block briefly to allow native cleanup
                        } else {
                            throw t
                        }
                    }
                } else {
                    Log.d(TAG, "Conversation reused for key=$requestKey, KVCache preserved")
                    created = true
                }
            }

            if (!created) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                }
            }
        }

        // synchronized 範囲外で SessionResourceManager に関連付けを行う
        convToAttach?.let { convCreated ->
            try {
                sessionManager.attachConversation(sessionId, convCreated)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to attach conversation to session manager: $sessionId", e)
            }
        }

        return activeLiteRtConversation!!
    }

    private suspend fun getOrCreateMemoryExtractionConversation(
        eng: Engine,
        config: InferenceConfig
    ): Conversation {
        // Note: At this point, inferenceMutex is already held by the calling inference() function.
        // No need to wait for it.
        
        var convToAttach: Conversation? = null
        var created = false
        val maxAttempts = 6
        var attempt = 0
        val normalized = config.normalized()

        while (!created && attempt < maxAttempts) {
            attempt++

            // まずロック内で作成が必要か確認し、フラグだけを立てる
            var needCreate = false
            activeConversationLock.withLock {
                if (memoryExtractionConversation == null || memoryExtractionConversationConfig != normalized) {
                    Log.i(TAG, "Creating new memory extraction conversation (attempt=$attempt)")
                    needCreate = true
                } else {
                    Log.d(TAG, "Memory extraction conversation reused")
                    created = true
                }
            }

            if (needCreate) {
                // 既存のメモリ抽出 Conversation を安全にクローズ（suspend 関数、ロック外で呼ぶ）
                if (memoryExtractionConversation != null) {
                    closeAndResetMemoryExtractionConversation()
                }

                ExperimentalFlags.enableConversationConstrainedDecoding = false
                val samplerConfig = if (normalized.backendType.uppercase() == "NPU") {
                    null
                } else {
                    SamplerConfig(
                        topK = normalized.maxTopK,
                        topP = normalized.topP.toDouble(),
                        temperature = normalized.temperature.toDouble()
                    )
                }

                val canUseSpeculative = normalized.enableSpeculativeDecoding &&
                    normalized.backendType.uppercase() == "GPU"
                ExperimentalFlags.enableSpeculativeDecoding = canUseSpeculative

                try {
                    val conv = eng.createConversation(
                        ConversationConfig(
                            tools = emptyList(),  // メモリ抽出はツール不要
                            samplerConfig = samplerConfig,
                            automaticToolCalling = false
                        )
                    )

                    // 作成した Conversation をロック内で設定
                    activeConversationLock.withLock {
                        memoryExtractionConversation = conv
                        memoryExtractionConversationConfig = normalized
                    }

                    convToAttach = conv
                    Log.d(TAG, "Memory extraction conversation created")
                    created = true
                } catch (t: Throwable) {
                    Log.w(TAG, "createConversation for memory extraction failed on attempt $attempt: ${t.message}")
                    if (attempt < maxAttempts && t.message?.contains("session already exists", ignoreCase = true) == true) {
                        // will retry after brief sleep
                    } else {
                        throw t
                    }
                }
            }

            if (!created) {
                try {
                    Thread.sleep(120)
                } catch (_: InterruptedException) {
                }
            }
        }

        // Dedicated memory extraction conversations are managed internally
        // and do not correspond to a regular session ID in SessionResourceManager.
        // No session attachment is required.
        return memoryExtractionConversation!!
    }

    private suspend fun acquireInferenceMutex() {
        inferenceMutex.lock()
        inferenceMutexHeld.set(true)
    }

    private fun releaseInferenceMutex() {
        if (inferenceMutexHeld.compareAndSet(true, false)) {
            inferenceMutex.unlock()
        } else {
            Log.w(TAG, "releaseInferenceMutex called but mutex was not held (double-release guard)")
        }
    }

    private fun resolveNativeLibraryDirForLitert(): String? {
        if (npuNativeLibraryDirChecked) {
            return cachedNpuNativeLibraryDir
        }

        val nativeLibDir = appContext.applicationInfo.nativeLibraryDir
        if (nativeLibDir.isNullOrBlank()) {
            Log.w(TAG, "NPU native library directory is not available")
            npuNativeLibraryDirChecked = true
            return null
        }

        val nativeDir = File(nativeLibDir)
        if (!nativeDir.isDirectory) {
            Log.w(TAG, "NPU native library directory does not exist: $nativeLibDir")
            npuNativeLibraryDirChecked = true
            return null
        }

        val hasLiteRtLib = nativeDir.listFiles { file ->
            file.isFile && (file.name.equals("libLiteRt.so", ignoreCase = true) ||
                file.name.equals("liblitertlm_jni.so", ignoreCase = true) ||
                file.name.equals("libLiteRtClGlAccelerator.so", ignoreCase = true))
        }?.isNotEmpty() == true

        if (!hasLiteRtLib) {
            Log.w(TAG, "NPU native library directory does not contain expected LiteRT libs: $nativeLibDir")
            npuNativeLibraryDirChecked = true
            return null
        }

        val dispatchLibraryDir = listOf(
            nativeDir,
            File(appContext.filesDir, "models")
        ).firstOrNull { dir ->
            dir.isDirectory && dir.listFiles { file ->
                file.isFile &&
                    file.name.endsWith(".so", ignoreCase = true) &&
                    file.name.contains("dispatch", ignoreCase = true)
            }?.isNotEmpty() == true
        }

        if (dispatchLibraryDir == null) {
            Log.w(
                TAG,
                "NPU backend requested but LiteRT dispatch library is unavailable. Falling back before Engine init to avoid repeated Dispatch API failures."
            )
            npuNativeLibraryDirChecked = true
            cachedNpuNativeLibraryDir = null
            return null
        }

        val result = dispatchLibraryDir.absolutePath
        Log.d(TAG, "NPU native/dispatch library dir: $result")
        cachedNpuNativeLibraryDir = result
        npuNativeLibraryDirChecked = true
        return result
    }

    private fun getOptimalBackendType(requestedBackendType: String): String {
        val normalizedRequested = requestedBackendType.uppercase()
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.lowercase().ifBlank { Build.HARDWARE.lowercase() }
        } else {
            Build.HARDWARE.lowercase()
        }
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        Log.d(TAG, "Hardware check: socModel=$socModel model=$model device=$device manufacturer=$manufacturer requestedBackend=$normalizedRequested")

        val isGoogleTensor = socModel.contains("tensor") || socModel.contains("gs") ||
            model.contains("pixel 8a") || model.contains("pixel 8") || device.contains("gs")
        val isSupportedQualcommNpu = listOf("sm8550", "sm8650", "sm8750").any { socModel.contains(it) }

        return when {
            normalizedRequested != "NPU" -> normalizedRequested
            isGoogleTensor -> {
                Log.i(TAG, "Google Tensor detected. TQ演算の相性によりGPUへフォールバック.")
                "GPU"
            }
            resolveNativeLibraryDirForLitert().isNullOrBlank() -> {
                Log.i(TAG, "NPU dispatch runtime is unavailable. Falling back to CPU/XNNPACK.")
                "CPU"
            }
            isSupportedQualcommNpu -> {
                Log.i(TAG, "Supported Qualcomm NPU SoC detected. Attempting NPU.")
                "NPU"
            }
            else -> {
                Log.i(TAG, "Unconfirmed NPU support for SoC. Falling back to CPU/XNNPACK.")
                "CPU"
            }
        }
    }

    /**
     * XNNPack キャッシュ向けに、mmap/remap の失敗を避けるため
     * 内部ストレージ（/data 配下）のみを候補にする。
     */
    private fun resolveWritableXnnpackCacheDir(): File? {
        if (disableXnnpackCacheForProcess) {
            Log.w(TAG, "XNNPack cache is disabled for this process due to previous mmap/remap failure.")
            return null
        }

        val candidates = listOfNotNull(
            appContext.codeCacheDir?.let { File(it, "litertlm_xnnpack") },
            File(appContext.cacheDir, "litertlm_xnnpack")
        )

        for (dir in candidates) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "Failed to create XNNPack cache dir: ${dir.absolutePath}")
                    continue
                }
                if (!dir.isDirectory) {
                    Log.w(TAG, "XNNPack cache candidate is not a directory: ${dir.absolutePath}")
                    continue
                }
                val probe = File(dir, ".rw_probe")
                probe.writeText("ok")
                if (!probe.delete()) {
                    probe.deleteOnExit()
                }
                Log.d(TAG, "Using XNNPack cache dir: ${dir.absolutePath}")
                return dir
            } catch (e: Exception) {
                Log.w(TAG, "XNNPack cache dir is not writable: ${dir.absolutePath}", e)
            }
        }

        Log.w(TAG, "No writable XNNPack cache directory found. Continuing without cacheDir.")
        return null
    }

    /**
     * XNNPack mmap/re-map 失敗の典型メッセージを判定。
     * 例: "mmap_handle.cc:173: remap failed: Bad address"
     */
    private fun isXnnpackMmapFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                message.contains("mmap_handle.cc", ignoreCase = true) ||
                message.contains("remap failed", ignoreCase = true) ||
                message.contains("bad address", ignoreCase = true) ||
                message.contains("xnnpack", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    override suspend fun loadModel(modelName: String, config: InferenceConfig): Result<Unit> {
        return try {
            withContext(modelLoadDispatcher) {
                modelMutex.withLock {
                    loadModelLocked(modelName, config)
                }
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to load model", e)
            Result.failure(e)
        }
    }

    /**
     * [loadModel] の本体。[withContext] / [Mutex.withLock] の crossinline 内では return が使えないため分離。
     */
    private suspend fun loadModelLocked(modelName: String, config: InferenceConfig): Result<Unit> {
        val normalizedConfig = config.normalized()
        Log.d(TAG, "loadModel START: modelName=$modelName backend=${normalizedConfig.backendType}")
        val modelStartTimeMs = System.currentTimeMillis()

        

        val modelFile = resolveLocalModelFile(modelName)
        val resolveTimeMs = System.currentTimeMillis()
        Log.d(TAG, "loadModel RESOLVE: file=$modelFile duration=${resolveTimeMs - modelStartTimeMs}ms")

        if (modelFile == null || !modelFile.exists()) {
            return Result.failure(IllegalStateException("Model file is not available"))
        }
        val modelPath = modelFile.absolutePath
        val needsReload = loadedModelPath != modelPath ||
            loadedConfig?.forModelLoad() != normalizedConfig.forModelLoad() ||
            loadedBackend != normalizedConfig.backendType ||
            (normalizedConfig.requireMultimodal && !loadedWithVisionAudio) ||
            engine == null

        if (!needsReload) {
            Log.d(TAG, "Model already loaded: $modelPath backend=${normalizedConfig.backendType}")
            return Result.success(Unit)
        }

        // If a recent critical init failure occurred, avoid tight retry loops by forcing CPU-only init
        val nowMs2 = System.currentTimeMillis()
        val lastFail2 = lastCriticalInitFailureMs.get()
        val forceCpuOnly = nowMs2 - lastFail2 < ENGINE_INIT_BACKOFF_MS
        if (forceCpuOnly) {
            Log.w(TAG, "Recent engine init failure detected (${nowMs2 - lastFail2}ms ago). Forcing CPU-only backend to avoid retry loop.")
        }

        val effectiveBackendType = if (forceCpuOnly) "CPU" else getOptimalBackendType(normalizedConfig.backendType)
        val preferredBackend = backendForConfig(effectiveBackendType)
        val cacheDir = resolveWritableXnnpackCacheDir()
        val cacheDirPath = cacheDir?.absolutePath
        val backendChanged = loadedBackend != null && loadedBackend != effectiveBackendType
        if (backendChanged) {
            Log.i(TAG, "Backend changed from $loadedBackend to $effectiveBackendType. Preparing safe cache swap...")
        }

        // Try to move existing cache to a backup location instead of immediate deletion.
        var cacheBackupDir: File? = null
        var initCacheDirForEngine: String? = cacheDirPath
        if (backendChanged && !cacheDirPath.isNullOrBlank()) {
            try {
                val cacheDirFile = File(cacheDirPath)
                if (cacheDirFile.exists() && cacheDirFile.isDirectory) {
                    val backup = File(cacheDirFile.parentFile, cacheDirFile.name + ".backup_${System.currentTimeMillis()}")
                    if (cacheDirFile.renameTo(backup)) {
                        cacheBackupDir = backup
                        initCacheDirForEngine = null
                        Log.i(TAG, "Renamed cache dir to backup: ${backup.absolutePath}")
                    } else {
                        Log.w(TAG, "Failed to rename cache dir for backup: ${cacheDirFile.absolutePath}. Clearing instead.")
                        clearBackendSpecificCache(cacheDirPath)
                        initCacheDirForEngine = null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error while backing up cache dir, will clear: $cacheDirPath", e)
                clearBackendSpecificCache(cacheDirPath)
                initCacheDirForEngine = null
            }
        }

        runCatching { engine?.close() }
        engine = null
        loadedModelPath = null
        loadedConfig = null
        loadedBackend = null

        // Cache was backed up above if possible; avoid double-clearing here.
        Log.d(TAG, "loadModel CACHE_VALIDATE: path=$cacheDirPath")
        // Ensure any bundled dispatch/native libs are extracted to files/models so LiteRT can find vendor dispatch .so files
        runCatching {
            ModelFileManager.ensureDispatchLibraries(appContext)
        }.onFailure { e ->
            Log.w(TAG, "Failed to ensure dispatch libraries in files/models", e)
        }
        CacheManager.validateAndRepairCacheIfNeeded(cacheDirPath)
        CacheManager.cleanupCacheIfNeeded(appContext, modelFile.name.lowercase(), cacheDir)
        val validateTimeMs = System.currentTimeMillis()
        Log.d(TAG, "loadModel CACHE_VALIDATE: duration=${validateTimeMs - resolveTimeMs}ms")

        fun newEngine(withVisionAudio: Boolean, backend: Backend, attemptCacheDir: String?): Engine {
            val ec = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                visionBackend = if (withVisionAudio) Backend.GPU() else null,
                audioBackend = if (withVisionAudio) Backend.CPU() else null,
                maxNumTokens = normalizedConfig.contextWindow.coerceAtLeast(2048),
                cacheDir = attemptCacheDir
            )
            return Engine(ec)
        }

        suspend fun tryCreate(withVisionAudio: Boolean, backend: Backend): Engine {
            Log.d(
                TAG,
                "loadModel ENGINE_INIT: START - backend=${backend.javaClass.simpleName} maxNumTokens=${normalizedConfig.contextWindow} cacheDir=$initCacheDirForEngine"
            )

            var eng = newEngine(withVisionAudio, backend, initCacheDirForEngine)
            val initStartMs = System.currentTimeMillis()
            try {
                eng.initialize()
                val initEndMs = System.currentTimeMillis()
                Log.d(
                    TAG,
                    "loadModel ENGINE_INIT: END - duration=${initEndMs - initStartMs}ms backend=${backend.javaClass.simpleName} cacheEnabled=${initCacheDirForEngine != null}"
                )
                return eng
            } catch (first: Throwable) {
                runCatching { eng.close() }

                // XNNPack cache mmap エラー時は cacheDir を無効化して再試行
                if (initCacheDirForEngine != null && isXnnpackMmapFailure(first)) {
                    Log.w(
                        TAG,
                        "Engine init failed with XNNPack cache. Retrying without cacheDir. cacheDir=$initCacheDirForEngine",
                        first
                    )
                    disableXnnpackCacheForProcess = true
                    clearBackendSpecificCache(initCacheDirForEngine)
                    eng = newEngine(withVisionAudio, backend, null)
                    val retryStartMs = System.currentTimeMillis()
                    eng.initialize()
                    val retryEndMs = System.currentTimeMillis()
                    Log.i(
                        TAG,
                        "Engine init recovered by disabling XNNPack cache in ${retryEndMs - retryStartMs}ms backend=${backend.javaClass.simpleName}"
                    )
                    return eng
                }

                throw first
            }
        }

        suspend fun loadWithBackend(backend: Backend): Pair<Engine, Boolean> {
            val isGpuBackend = backend is Backend.GPU
            val tryWithVisionAudio = normalizedConfig.requireMultimodal || !isGpuBackend

            if (isGpuBackend && normalizedConfig.requireMultimodal) {
                Log.i(TAG, "GPU backend detected with multimodal request: enabling vision/audio initialization")
            } else if (isGpuBackend) {
                Log.d(TAG, "GPU backend detected: skipping vision/audio initialization to save VRAM")
            }
            
            return runCatching { tryCreate(withVisionAudio = tryWithVisionAudio, backend) to tryWithVisionAudio }
                .getOrElse { first ->
                    if (normalizedConfig.requireMultimodal) {
                        throw multimodalUnavailableException(
                            "backend=${backendTypeLabel(backend)} init failed: ${first.message ?: first.javaClass.simpleName}"
                        )
                    }
                    Log.w(TAG, "Engine init with vision/audio=${tryWithVisionAudio} failed, retrying text-only", first)
                    tryCreate(withVisionAudio = false, backend) to false
                }
        }

        suspend fun getBackendFallbackChain(preferred: Backend): List<Backend> {
            return when (preferred) {
                is Backend.NPU -> {
                    val npuLibDir = resolveNativeLibraryDirForLitert()
                    if (npuLibDir.isNullOrBlank()) {
                        Log.w(TAG, "NPU backend requested but native library dir is unavailable. Falling back to GPU/CPU chain.")
                        listOf(Backend.GPU(), Backend.CPU())
                    } else {
                        listOf(
                            Backend.NPU(nativeLibraryDir = npuLibDir),
                            Backend.GPU(),
                            Backend.CPU()
                        )
                    }
                }
                is Backend.GPU -> listOf(Backend.GPU(), Backend.CPU())
                else -> listOf(Backend.CPU())
            }
        }

        suspend fun tryBackendChain(backends: List<Backend>): Triple<Engine, Boolean, Backend> {
            var lastError: Throwable? = null

            for ((index, backend) in backends.withIndex()) {
                try {
                    Log.i(TAG, "Attempting to load with ${backend.javaClass.simpleName} (${index + 1}/${backends.size})")
                    val start = System.currentTimeMillis()
                    val (eng, withVA) = loadWithBackend(backend)
                    val duration = System.currentTimeMillis() - start
                    Log.i(TAG, "Successfully loaded with ${backend.javaClass.simpleName} in ${duration}ms (visionAudio=$withVA)")
                    return Triple(eng, withVA, backend)
                } catch (e: Throwable) {
                    lastError = e
                    Log.w(
                        TAG,
                        "Backend ${backend.javaClass.simpleName} (${index + 1}/${backends.size}) initialization failed: ${e.message}",
                        e
                    )
                    if (index < backends.size - 1) {
                        Log.i(TAG, "Trying next backend in fallback chain...")
                    }
                }
            }

            throw lastError ?: RuntimeException("All backends failed to initialize")
        }

        val fallbackChain = getBackendFallbackChain(preferredBackend)
        try {
            backendResourceManager.prepareBackendSwitch(preferredBackend)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to prepare backend switch", e)
        }
        Log.d(TAG, "Backend fallback chain: ${fallbackChain.map { it.javaClass.simpleName }}")

        // Attempt backend initialization; on failure restore cache backup and rollback resources.
        val engTriple = try {
            tryBackendChain(fallbackChain)
        } catch (e: Throwable) {
            // record the failure timestamp to avoid aggressive reattempts
            lastCriticalInitFailureMs.set(System.currentTimeMillis())
            Log.w(TAG, "Backend initialization failed; attempting to restore cache and rollback", e)
            // Restore cache backup if it exists
            if (cacheBackupDir != null && !cacheDirPath.isNullOrBlank()) {
                try {
                    val original = File(cacheDirPath)
                    if (cacheBackupDir!!.renameTo(original)) {
                        Log.i(TAG, "Restored cache from backup: ${original.absolutePath}")
                    } else {
                        Log.w(TAG, "Failed to restore cache backup: ${cacheBackupDir!!.absolutePath} -> ${original.absolutePath}")
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "Error while restoring cache backup", ex)
                }
            }
            // Attempt to rollback any prepared backend state
            try {
                backendResourceManager.rollbackBackend(preferredBackend)
            } catch (ex: Exception) {
                Log.w(TAG, "Rollback of backend after failed init also failed", ex)
            }
            throw e
        }

        val (eng, withVA, usedBackend) = engTriple

        try {
            backendResourceManager.registerBackendEngine(eng, usedBackend)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register backend engine", e)
        }

        engine = eng
        loadedModelPath = modelPath
        loadedConfig = normalizedConfig
        loadedBackend = effectiveBackendType
        loadedWithVisionAudio = withVA

        // On success, remove the cache backup if present
        if (cacheBackupDir != null) {
            try {
                if (cacheBackupDir!!.deleteRecursively()) {
                    Log.i(TAG, "Deleted cache backup: ${cacheBackupDir!!.absolutePath}")
                } else {
                    Log.w(TAG, "Failed to delete cache backup: ${cacheBackupDir!!.absolutePath}")
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Error deleting cache backup", ex)
            }
        }

        val totalTimeMs = System.currentTimeMillis() - modelStartTimeMs
        // v2.1+: 要求バックエンドと実効バックエンドの一致状況を残し、
        //        「GPU を選んだのに実際は CPU で動いていた」等の齟齬を実機ログで検出できるようにする。
        val requestedBackendUpper = normalizedConfig.backendType.uppercase()
        val effectiveBackendUpper = effectiveBackendType.uppercase()
        val backendMatched = requestedBackendUpper == effectiveBackendUpper
        Log.i(
            TAG,
            "loadModel SUCCESS: model=$modelPath " +
                "requestedBackend=$requestedBackendUpper effectiveBackend=$effectiveBackendUpper " +
                "match=$backendMatched visionAudio=$withVA totalDuration=${totalTimeMs}ms"
        )
        if (!backendMatched) {
            Log.w(
                TAG,
                "Backend fallback occurred: requested=$requestedBackendUpper -> effective=$effectiveBackendUpper. " +
                    "Check device support (NPU dispatch lib / GPU OpenCL / TPU delegate) and native library manifest."
            )
        }
        return Result.success(Unit)
    }

    private fun backendForConfig(backendType: String): Backend {
        return when (backendType.uppercase()) {
            "GPU" -> Backend.GPU()
            "NPU" -> {
                val npuLibDir = resolveNativeLibraryDirForLitert()
                if (npuLibDir.isNullOrBlank()) {
                    Log.w(TAG, "NPU backend requested but native library directory is unavailable. Using GPU instead.")
                    Backend.GPU()
                } else {
                    Backend.NPU(nativeLibraryDir = npuLibDir)
                }
            }
            else -> Backend.CPU()
        }
    }

    /**
     * Phase 11: バックエンド変更時のキャッシュクリア
     * GPU → CPU または CPU → GPU など、バックエンド切り替え時にキャッシュを削除する。
     * 異なるバックエンド間ではキャッシュ形式が互換でない可能性があるため。
     */
    private fun clearBackendSpecificCache(cacheDirPath: String?) {
        if (cacheDirPath.isNullOrBlank()) return
        
        try {
            val cacheDir = File(cacheDirPath)
            if (!cacheDir.exists() || !cacheDir.isDirectory) return
            
            // XNNPack / GPU キャッシュファイルを削除
            val cacheFiles = cacheDir.listFiles { file ->
                file.isFile && (
                    file.name.endsWith(".bin") ||
                    file.name.endsWith(".ckpt") ||
                    file.name.contains("gpu", ignoreCase = true) ||
                    file.name.contains("xnnpack", ignoreCase = true)
                )
            } ?: emptyArray()
            
            cacheFiles.forEach { file ->
                val deleted = file.delete()
                Log.d(TAG, "Cleared backend-specific cache: ${file.name} (deleted=$deleted)")
            }
            
            if (cacheFiles.isNotEmpty()) {
                Log.i(TAG, "Cleared ${cacheFiles.size} backend-specific cache files due to backend change")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing backend-specific cache", e)
        }
    }

    override suspend fun inference(
        sessionId: Long,
        prompt: String,
        config: InferenceConfig
    ): Flow<String> = inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config)

    override suspend fun inferenceWithMedia(
        sessionId: Long,
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        config: InferenceConfig
    ): Flow<String> = callbackFlow {
        val normalized = config.normalized()
        val useExtractionConversation = !normalized.enableThinking && sessionId == 0L

        // 通常推論の場合、フラグをセット（抽出推論がこれを見てキャンセルする）
        if (!useExtractionConversation) {
            normalInferenceRequested = true
        }

        // 全ての推論（通常＆抽出）が同じmutexで直列化
        acquireInferenceMutex()

        // 抽出推論がmutexを取った直後に通常推論がリクエストされたら、抽出をキャンセルして即座に終了
        if (useExtractionConversation && normalInferenceRequested) {
            Log.d(TAG, "Memory extraction cancelled due to normal inference request")
            releaseInferenceMutex()
            close()
            return@callbackFlow
        }

        val eng = modelMutex.withLock { engine }
        if (eng == null) {
            releaseInferenceMutex()
            close(IllegalStateException("Model not loaded. Call loadModel() first."))
            return@callbackFlow
        }

        val visionEnabled = loadedWithVisionAudio
        val hasMultimodalInput = images.isNotEmpty() || audioClips.isNotEmpty()

        // Bug fix: 外部インポート LiteRT-LM (.task / .litertlm) では、モデル設定で
        // 画像/音声のスイッチが OFF のときに vision/audio executor をロードしようとして
        // エラーが出ていた。ここで capability を再確認し、OFF ならメディアを捨てて
        // テキスト専用推論にフォールバックする（勝手に requireMultimodal 再ロードしない）。
        val capabilityBlockedMultimodal: Boolean = run {
            if (!hasMultimodalInput) return@run false
            val path = loadedModelPath ?: return@run false
            val lower = path.lowercase()
            // インポート判定は「models/imported 配下か」で行う。
            //   旧実装は「拡張子が .task/.litertlm かつ絶対パス」だけで判定していたため、
            //   ビルトイン Gemma (filesDir/models/gemma-4-2b.litertlm 等) までインポート扱いになり、
            //   capability スイッチ (既定 OFF) で画像/音声が捨てられてしまっていた。
            val isImportedLiteRt = (lower.endsWith(".task") || lower.endsWith(".litertlm")) &&
                File(path).isAbsolute &&
                File(path).parentFile?.canonicalPath ==
                    File(appContext.filesDir, "models/imported").canonicalPath
            if (!isImportedLiteRt) return@run false
            val caps = com.nezumi_ai.utils.ImportedModelCapabilityStore.get(appContext, path)
            val imageBlocked = images.isNotEmpty() && !caps.imageEnabled
            val audioBlocked = audioClips.isNotEmpty() && !caps.audioEnabled
            imageBlocked || audioBlocked
        }
        if (capabilityBlockedMultimodal) {
            releaseInferenceMutex()
            Log.w(
                TAG,
                "Imported LiteRT-LM: media supplied but capability switches are OFF. " +
                    "Falling back to text-only inference to avoid unnecessary multimodal reload."
            )
            inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config).collect { chunk ->
                trySend(chunk).isSuccess
            }
            close()
            return@callbackFlow
        }

        // マルチモーダル入力があるのにvisionが無効な場合、requireMultimodal=trueで再ロードを1回だけ試みる
        if (hasMultimodalInput && !visionEnabled) {
            releaseInferenceMutex()
            Log.i(TAG, "Multimodal input detected but engine loaded without vision/audio. Reloading with requireMultimodal=true...")

            val reloadConfig = normalized.copy(requireMultimodal = true)
            val reloadResult = modelMutex.withLock {
                val currentPath = loadedModelPath
                if (currentPath != null) {
                    loadModelLocked(File(currentPath).nameWithoutExtension, reloadConfig)
                } else {
                    Result.failure(IllegalStateException("Cannot reload: model path unknown"))
                }
            }

            if (reloadResult.isFailure) {
                close(reloadResult.exceptionOrNull() ?: RuntimeException("Model reload failed"))
                return@callbackFlow
            }

            // リロード後もvisionが有効にならなかった場合（vision encoder の3 signatures非対応等）
            // 無限ループを防ぐため画像なしのテキスト推論にフォールバックする
            val reloadedWithVision = modelMutex.withLock { loadedWithVisionAudio }
            if (!reloadedWithVision) {
                Log.w(TAG, "Vision encoder unavailable after reload (3-signature encoder not supported by this LiteRT-LM version). Falling back to text-only inference.")
                inferenceWithMedia(sessionId, prompt, emptyList(), emptyList(), config).collect { chunk ->
                    trySend(chunk).isSuccess
                }
                close()
                return@callbackFlow
            }

            Log.i(TAG, "Model reloaded with vision/audio support. Retrying inference...")
            inferenceWithMedia(sessionId, prompt, images, audioClips, config).collect { chunk ->
                trySend(chunk).isSuccess
            }
            close()
            return@callbackFlow
        }
        // Bug fix (LiteRT 二重テンプレート対策):
        //   本流チャットでは ChatViewModel から構造化ペイロード (system / history / current) が
        //   マーカー付き JSON で届く。デコードできた場合は ConversationConfig
        //   (systemInstruction / initialMessages) でテンプレート適用をエンジンに委ね、
        //   現ターンのみを送信する。デコードできない場合は従来通りの単一テキスト扱い
        //   (クラウド / 圧縮サマリー / メモリ抽出などの内部推論経路)。
        val structuredPayload =
            if (useExtractionConversation) null else LiteRtStructuredPrompt.decode(prompt)

        try {
            Log.d(TAG, "LiteRT inference session=$sessionId images=${images.size} audio=${audioClips.size} enableThinking=${normalized.enableThinking} visionEnabled=$visionEnabled structured=${structuredPayload != null}")

            val conv = if (useExtractionConversation) {
                Log.d(TAG, "Using dedicated memory extraction conversation for session=$sessionId")
                getOrCreateMemoryExtractionConversation(eng, normalized)
            } else if (structuredPayload != null) {
                // 構造化経路: Conversation API がエンジン側で履歴を管理するため、
                // 正確性を優先して「毎ターン systemInstruction + initialMessages を載せた
                // 会話を作り直す」。これは GGUF 経路が毎ターン全文プロンプトを再送するのと
                // 同等のコストで、履歴編集 (削除 / バリアント切替 / 再生成) や
                // 内部推論 (圧縮・メモリ抽出) による会話汚染の余地を完全に排除できる。
                // (KV キャッシュ再利用は行わない。再利用を厳密にやるには assistant 応答を含む
                //  履歴同期が必要で、不一致時の文脈破壊リスクが大きいため見送る)
                structuredConversationLock.withLock {
                    closeAndResetActiveConversation(sessionId)
                    getOrCreateConversation(sessionId, eng, normalized, structuredPayload)
                }
            } else {
                Log.d(TAG, "Using regular chat conversation for session=$sessionId")
                getOrCreateConversation(sessionId, eng, normalized)
            }

            val contents = mutableListOf<Content>()
            // 5 枚を超える入力は「動画フレーム列」とみなして 30 枚まで引き上げる。
            // スチル画像 5 枚制限は UI 側で保証されるため、ここではリミッタとしてのみ機能していれば十分。
            val visionCap = if (images.size > MAX_VISION_IMAGES) MAX_VIDEO_FRAMES else MAX_VISION_IMAGES
            for (bitmap in images.take(visionCap)) {
                val scaled = scaleBitmapForVision(bitmap)
                try {
                    val imageBytes = scaled.toPngByteArray()
                    if (imageBytes.isNotEmpty()) {
                        contents.add(Content.ImageBytes(imageBytes))
                    } else {
                        Log.w(TAG, "Skipping empty image payload for session=$sessionId")
                    }
                } finally {
                    // 参照が異なる場合のみ recycle する
                    // (scaleBitmapForVision がサイズ内に収まる場合、元の bitmap を返す)
                    if (scaled !== bitmap) {
                        scaled.recycle()
                        Log.d(TAG, "Scaled bitmap recycled (original ${bitmap.width}x${bitmap.height} -> ${scaled.width}x${scaled.height})")
                    }
                }
            }
            for (clip in audioClips) {
                if (clip.isNotEmpty()) {
                    val normalizedAudio =
                        LlmMultimodalAudioHelper.toMono16Bit16kHzWav(appContext, clip)
                    if (normalizedAudio != null && normalizedAudio.isNotEmpty()) {
                        contents.add(Content.AudioBytes(normalizedAudio))
                    } else {
                        // 生バイトをそのまま送るとネイティブ側で落ちる可能性があるためスキップする。
                        Log.w(TAG, "Audio normalization failed; skipping invalid audio payload")
                    }
                }
            }
            // 構造化経路では prompt はマーカー付き JSON なのでそのまま送ると
            // JSON テキストがユーザーメッセージとしてモデルに渡ってしまう。
            // デコード済みの現ターン本文だけを送り、履歴は initialMessages / KV キャッシュに委ねる。
            val textToSend = structuredPayload?.currentText ?: prompt
            if (textToSend.trim().isNotEmpty()) {
                contents.add(Content.Text(textToSend))
            }

            val extraContext =
                if (normalized.enableThinking) mapOf("enable_thinking" to "true") else emptyMap()

            val answerAccum = StringBuilder()
            val toolResultCards = mutableListOf<ToolResultCard>()
            val generationJob = launch(Dispatchers.IO) {
                try {
                    var inferenceStartMs = System.currentTimeMillis()
                    var firstTokenMs = -1L
                    var tokenCount = 0f
                    var firstRequest = true
                    var pendingToolResponseMessage: Message? = null
                    val maxToolRounds = if (normalized.enableToolCalling) 5 else 1
                    var toolRound = 0
                    while (isActive && toolRound < maxToolRounds) {
                        toolRound++
                        var toolCallsInTurn: List<ToolCall> = emptyList()
                        // バグ修正 (インライン tool-call カード・ストリーミング対応):
                        //   LiteRt の messageFlow は各ラウンド内で「そのラウンドのメッセージ全文」を
                        //   逐次送信してくるが、ラウンドをまたいでは累積しない。旧実装は
                        //   answerAccum (全ラウンド累積) と text (今ラウンド内の全文) を直接比較していたため、
                        //   ラウンド2以降は startsWith が失敗して text 全体が answerAccum に二重追記され、
                        //   trySend も text 全体を送ってしまって UI 側の merge ロジックが
                        //   既追記済み <tool_call> タグを見失うケースがあった。
                        //   ラウンド単位の累積 roundAccum を使い、ラウンド内の差分だけを
                        //   answerAccum / trySend に流すことで修正する。
                        var roundAccum = ""
                        val messageFlow = if (firstRequest) {
                            firstRequest = false
                            conv.sendMessageAsync(Contents.of(contents), extraContext)
                        } else {
                            conv.sendMessageAsync(
                                pendingToolResponseMessage
                                    ?: throw IllegalStateException("Tool response message missing"),
                                extraContext
                            )
                        }
                        messageFlow.collect { message ->
                            val calls = message.toolCalls
                            if (calls.isNotEmpty()) {
                                toolCallsInTurn = calls
                                // インライン tool_call カード表示:
                                //   LiteRt は構造化 API 経由でツール呼び出しを返すため、モデルの生テキストには
                                //   <tool_call> タグが含まれない。UI 側 (GgufToolCallParser.parseSegments) が本文中の
                                //   出現位置でカードを差し込めるよう、タグを answerAccum に合成挿入して
                                //   同じ内容を trySend もする (ストリーミング UI でもタグを見えるように)。
                                val tagPayload = buildString {
                                    for (call in calls) {
                                        append("\n<tool_call>\n")
                                        append(buildToolCallJson(call))
                                        append("\n</tool_call>\n")
                                    }
                                }
                                answerAccum.append(tagPayload)
                                trySend(tagPayload).isSuccess
                                trySend(
                                    InferenceStreamProtocol.encodeToolCallChunk(
                                        calls.map { it.name }
                                    )
                                ).isSuccess
                            }
                            val thought = message.channels[THOUGHT_CHANNEL]
                            if (!thought.isNullOrEmpty()) {
                                trySend(InferenceStreamProtocol.encodeThinkChunk(thought)).isSuccess
                            }
                            if (calls.isNotEmpty()) return@collect
                            val text = message.toString()
                            if (shouldEmitPartialText(text)) {
                                // TTFT計測
                                if (firstTokenMs < 0) {
                                    firstTokenMs = System.currentTimeMillis()
                                    val ttft = firstTokenMs - inferenceStartMs
                                    Log.d(TAG, "TTFT: ${ttft}ms session=$sessionId")
                                }

                                // ラウンド内の累積 (roundAccum) との差分を取る。LiteRt の text は
                                // ラウンド内で逐次伸びる全文なので、text.startsWith(roundAccum) は常に true
                                // (モデルが既存トークンを上書きしない場合) 。万一 startsWith が false なら
                                // ささい上書き修正と見なして text 全体をデルタとする (安全側に倒す)。
                                val deltaText = if (text.startsWith(roundAccum)) {
                                    text.substring(roundAccum.length)
                                } else {
                                    text
                                }
                                if (deltaText.isNotEmpty()) {
                                    tokenCount += TextTokenEstimator.estimateOutputTokens(deltaText)
                                }

                                // TPS ログ
                                if (tokenCount.toInt() % 10 == 0) {
                                    val elapsedMs = System.currentTimeMillis() - inferenceStartMs
                                    val tps = if (elapsedMs > 0) tokenCount * 1000.0 / elapsedMs else 0.0
                                    Log.d(TAG, "TPS: %.1f tok/s (tokens=%.1f, elapsed=${elapsedMs}ms) session=$sessionId".format(tps, tokenCount))
                                }

                                roundAccum = text
                                if (deltaText.isNotEmpty()) {
                                    answerAccum.append(deltaText)
                                    trySend(deltaText).isSuccess
                                }
                            }
                        }

                        if (toolCallsInTurn.isEmpty()) {
                            break
                        }
                        if (toolRound >= maxToolRounds) {
                            Log.w(TAG, "Tool call loop exceeded max rounds, breaking session=$sessionId")
                            break
                        }
                        // ツール呼び出し時は計測をリセット
                        inferenceStartMs = System.currentTimeMillis()
                        firstTokenMs = -1L
                        tokenCount = 0f
                        // 修正: 並列実行の速度は維持したまま、各ツールの結果を toolCallsInTurn の
                        // インデックス位置に対応する配列スロットへ書き込み、全ツール完了後に
                        // インデックス順で toolResultCards / toolResponses へ追記する。
                        // toolResponses (モデルへの再入力用) も同じ理由で、実行完了順ではなく
                        // 呼び出し順にしないと、モデルが「どの呼び出しに対する応答か」を
                        // 取り違える可能性がある。
                        val roundResultSlots = arrayOfNulls<ToolResultCard>(toolCallsInTurn.size)
                        val roundResponseSlots = arrayOfNulls<Content.ToolResponse>(toolCallsInTurn.size)
                        val toolJobs = toolCallsInTurn.mapIndexed { callIndex, toolCall ->
                            launch(Dispatchers.IO) {
                                try {
                                    val result = toolExecutor.execute(toolCall)
                                    val status = if (result.success) "success" else "error"
                                    trySend(
                                        InferenceStreamProtocol.encodeToolResultChunk(
                                            toolCall.name,
                                            status
                                        )
                                    ).isSuccess
                                    // モデルへ送り返すのは payload (フル本文) ではなく payloadForModel。
                                    //   convert_md_to_document のように UI カードには Markdown 全文が必要でも、
                                    //   モデルにはその全文を <tool_response> 経由で再送する必要がない
                                    //   (ChatViewModel.invokeConvertMdToDocumentFromTool の modelPayload 参照)。
                                    //   ここで payload を渡すと、次ラウンド以降のプロンプトに Markdown 本文が
                                    //   毎回乗り続けてコンテキストを浪費するため、GGUF 側 (GgufToolCallParser
                                    //   .resultPayloadJson) と同じく要約ペイロードを使う。
                                    roundResponseSlots[callIndex] =
                                        Content.ToolResponse(toolCall.name, result.payloadForModel)
                                    // ToolResultCard を蓄積（UI表示用）。
                                    // toolCallsInTurn 内での元の呼び出し順 (callIndex) のスロットに
                                    // 書き込むことで、実行完了順ではなく呼び出し順を保つ。
                                    val jsonPayload = result.payload.mapValues { (_, v) ->
                                        anyToJsonElement(v)
                                    }
                                    roundResultSlots[callIndex] = ToolResultCard(
                                        toolName = toolCall.name.lowercase(),
                                        success = result.success,
                                        payload = jsonPayload
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Tool execution error: ${toolCall.name}", e)
                                    trySend(
                                        InferenceStreamProtocol.encodeToolResultChunk(
                                            toolCall.name,
                                            "error"
                                        )
                                    ).isSuccess
                                }
                            }
                        }
                        // すべてのツール実行が完了するまで待機
                        toolJobs.forEach { it.join() }
                        // 呼び出し順 (callIndex) のまま toolResultCards / モデル応答へ追記する。
                        // 例外で書き込まれなかったスロット (null) はスキップする。
                        synchronized(toolResultCards) {
                            roundResultSlots.forEach { card ->
                                if (card != null) toolResultCards.add(card)
                            }
                        }
                        val orderedToolResponses: List<Content> = roundResponseSlots.filterNotNull()
                        pendingToolResponseMessage = Message.Companion.tool(Contents.of(orderedToolResponses))

                        // バグ修正 (tool_response が履歴コンテキストに保存されない):
                        //   LiteRt は構造化 API (Message.Companion.tool(...)) でモデルにツール結果を戻すため、
                        //   answerAccum (= チャット履歴の assistant.content になる文字列) には `<tool_response>`
                        //   タグが残らない。次ターンのプロンプトを履歴から再構築する際に「モデルがどのツールを
                        //   呼んで何が返ったか」の対応関係が完全に失われるので、GGUF 側と揃えて同じ `<tool_response>` フォーマットで
                        //   answerAccum に合成挿入する。UI 側 (InlineToolCallCard) は card.payload を見て result を
                        //   描画するので見た目は変わらないが、履歴プロンプトにはここで初めて tool_response が乗る。
                        val toolResponseBlock = buildString {
                            appendLine()
                            // タグに乗せる name は呼び出し側の ToolCall.name を使う (litertlm の Content.ToolResponse の
                            // プロパティ名に依存しないようにする)。roundResponseSlots と toolCallsInTurn は
                            // 同じ callIndex の位置に対応している。
                            roundResponseSlots.forEachIndexed { i, resp ->
                                if (resp == null) return@forEachIndexed
                                val card = roundResultSlots.getOrNull(i) ?: return@forEachIndexed
                                val name = toolCallsInTurn.getOrNull(i)?.name ?: card.toolName
                                appendLine("<tool_response>")
                                appendLine("{\"name\":\"$name\",\"content\":${buildToolResponseContentJson(card)}}")
                                appendLine("</tool_response>")
                            }
                        }
                        if (toolResponseBlock.isNotBlank()) {
                            answerAccum.append(toolResponseBlock)
                            trySend(toolResponseBlock).isSuccess
                        }

                        // ライブ persist: ラウンド完了ごとに現時点の toolResultCards を JSON 化して送出し、
                        //   UI 側がツールカードの result を生成中に展開できるようにする。
                        //   以前は close() 直前の 1 回しか送らなかったため、モデルが最終回答を吐き終えるまで
                        //   カードの “result” 行は (モデルへ送信済み) のプレースホルダーのままだった。
                        synchronized(toolResultCards) {
                            if (toolResultCards.isNotEmpty()) {
                                trySend(
                                    InferenceStreamProtocol.encodeToolResults(
                                        ToolResultCard.listToJsonArray(toolResultCards)
                                    )
                                ).isSuccess
                            }
                        }
                    }

                    // 最終サマリーログ
                    val totalElapsed = System.currentTimeMillis() - inferenceStartMs
                    val finalTps = if (totalElapsed > 0) tokenCount * 1000.0 / totalElapsed else 0.0
                    Log.i(TAG, "Inference complete: %.1f tok/s total (tokens=%.1f, ${totalElapsed}ms) session=$sessionId".format(finalTps, tokenCount))

                    // toolResultCards をJSON化して toolResultsJson として送出
                    val toolResultsJson = if (toolResultCards.isNotEmpty()) {
                        ToolResultCard.listToJsonArray(toolResultCards)
                    } else {
                        null
                    }
                    trySend(
                        InferenceStreamProtocol.encodeToolResults(toolResultsJson)
                    ).isSuccess

                    // 実行されたツール一覧を送出（UI表示用）
                    val executedToolNames = toolResultCards.map { it.toolName }.distinct()
                    if (executedToolNames.isNotEmpty()) {
                        trySend(
                            InferenceStreamProtocol.encodeExecutedToolsList(executedToolNames)
                        ).isSuccess
                    }

                    val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                    trySend(finalResult).isSuccess
                    close()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        val finalResult = InferenceStreamProtocol.encodeFinal(answerAccum.toString())
                        trySend(finalResult).isSuccess
                        close()
                    } else {
                        Log.e(TAG, "Inference error session=$sessionId", t)
                        close(if (t is Exception) t else RuntimeException(t))
                    }
                } finally {
                    releaseInferenceMutex()
                    // 通常推論が終了したらフラグをリセット（抽出が再開できるように）
                    if (!useExtractionConversation) {
                        normalInferenceRequested = false
                    }
                }
            }

            awaitClose {
                Log.d(TAG, "awaitClose: cancelling session=$sessionId")
                generationJob.cancel()
                // awaitClose は suspend 不可。runBlocking 禁止のため NonSuspend 版を使用
                if (useExtractionConversation) {
                    cancelMemoryExtractionConversationNonSuspend()
                } else {
                    cancelActiveConversationNonSuspend()
                }
            }
        } catch (t: Throwable) {
            // 例外時は cancel のみ（close は呼ばず）
            if (useExtractionConversation) {
                cancelMemoryExtractionConversationNonSuspend()
            } else {
                cancelActiveConversationNonSuspend()
            }
 // 外側で例外が発生した場合も必ず release する（generationJob.finally が実行されない可能性あり）
            releaseInferenceMutex()
            if (!useExtractionConversation) {
                normalInferenceRequested = false
            }
            if (t is CancellationException) {
                close(t)
                return@callbackFlow
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Inference failed session=$sessionId", e)
            close(e)
        }
    }

    override suspend fun unloadModel(): Result<Unit> {
        return try {
            inferenceMutex.withLock {
            modelMutex.withLock {
                Log.d(TAG, "Unloading LiteRT-LM engine with resource cleanup")
                
                // 1. 推論をキャンセル
                cancelActiveConversation()
                cancelMemoryExtractionConversation()
                closeAndResetActiveConversation()
                closeAndResetMemoryExtractionConversation()
                
                // 2. 現在のモデル名を保持してから Engine をクローズ
                val unloadingModelName = loadedModelPath?.let { File(it).name.lowercase() } ?: ""
                runCatching { engine?.close() }
                engine = null
                loadedModelPath = null
                loadedConfig = null
                loadedBackend = null  // Phase 11: バックエンド状態をリセット
                loadedWithVisionAudio = false
                
                // 3. Bitmap メモリプールをクリア
                bitmapPool.clear()
                
                // 4. バックエンドリソースをクリーンアップ
                backendResourceManager.cleanupAll()
                
                // 5. キャッシュをクリーンアップ（XNNPack が使うディレクトリを優先）
                // アンロードするモデルのキャッシュは保護
                CacheManager.cleanupCacheIfNeeded(
                    context = appContext,
                    currentModelBaseName = unloadingModelName,
                    cacheDir = resolveWritableXnnpackCacheDir(),
                    forceScan = false
                )
                
                Log.d(TAG, "LiteRT-LM engine unloaded with full resource cleanup")
                Result.success(Unit)
            }
            }
        } catch (t: Throwable) {
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Failed to unload model", e)
            Result.failure(e)
        }
    }

    override suspend fun cancelInference() {
        Log.d(TAG, "Cancelling active inference (cancelProcess only, KV cache preserved)")
        
        // メモリ状態をチェック（キャンセル前に状態ログ出力）
        val memStatus = memoryObserver.getMemoryStatus(appContext)
        Log.d(TAG, "Memory status at cancel: ${memStatus.usedPercent}% (${memStatus.usedMB}MB/${memStatus.maxMB}MB)")
        
        cancelActiveConversation()
        cancelMemoryExtractionConversation()
    }

    suspend fun forceReset() {
        modelMutex.withLock {
            Log.w(TAG, "forceReset: Forcibly invalidating LiteRT-LM engine state without Engine.close()")
            cancelActiveConversation()
            cancelMemoryExtractionConversation()
            closeAndResetActiveConversation()
            closeAndResetMemoryExtractionConversation()

            engine = null
            loadedModelPath = null
            loadedConfig = null
            loadedBackend = null
            loadedWithVisionAudio = false
            bitmapPool.clear()
            // backendResourceManager.cleanupAll() は close 系呼び出しと重複する可能性があるため避ける
        }
    }

    override suspend fun isAvailable(): Boolean = true

    private fun Bitmap.toPngByteArray(): ByteArray {
        val stream = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * LiteRt の ToolCall (構造化) を、GGUF 系と同じ `{"name":..,"arguments":..}` 形の
     * JSON テキストに変換する。answerAccum に <tool_call>...</tool_call> として
     * 埋め込むために使う (UI 側のインラインカード表示で必要)。
     */
    private fun buildToolCallJson(call: ToolCall): String {
        return buildToolCallJsonInternal(call)
    }

    private fun buildToolCallJsonInternal(call: ToolCall): String {
        val argsJson = try {
            val entries = call.arguments.entries.joinToString(",") { (k, v) ->
                "\"${k.replace("\"", "\\\"")}\":" + anyToJsonElement(v).toString()
            }
            "{$entries}"
        } catch (_: Throwable) {
            "{}"
        }
        return "{\"name\":\"${call.name}\",\"arguments\":$argsJson}"
    }

    /**
     * ToolResultCard の payload を GGUF 側の formatToolResults() と同じ `{"key":value,...}` 形式の
     * JSON テキストにシリアライズする。<tool_response> タグの content フィールドに埋め込むために使う。
     */
    private fun buildToolResponseContentJson(card: ToolResultCard): String {
        return runCatching {
            val obj = JsonObject(card.payload.mapValues { sanitizeJsonElement(it.value) })
            obj.toString()
        }.getOrElse { "{\"success\":${card.success}}" }
    }

    /** JSON 値を再帰的に走査し、文字列リーフにだけタグ無害化を適用する。数値・真偽値・構造はそのまま。 */
    private fun sanitizeJsonElement(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.associate { (k, v) ->
                ToolPayloadSanitizer.sanitizeValue(k) to sanitizeJsonElement(v)
            }
        )
        is JsonArray -> JsonArray(element.map { sanitizeJsonElement(it) })
        is JsonPrimitive ->
            if (element.isString) JsonPrimitive(ToolPayloadSanitizer.sanitizeValue(element.content))
            else element
        else -> element
    }

    private fun anyToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is JsonElement -> value
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> {
                val obj = value.entries.associate { (k, v) ->
                    (k?.toString() ?: "null") to anyToJsonElement(v)
                }
                JsonObject(obj)
            }
            is List<*> -> JsonArray(value.map { anyToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    /**
     * ビジョン推論用に Bitmap をスケーリングする（メモリ管理強化版）。
     *
     * 実装上の特徴：
     * - MAX_BITMAP_EDGE (1024px) 以下の場合、元の bitmap をそのまま返す（recycle 不要）
     * - MAX_BITMAP_EDGE を超える場合、新しい Bitmap インスタンスを作成してスケーリング
     * - OutOfMemoryError 発生時は、段階的に品質を下げて再試行
     * - BitmapRecycleHelper による安全なスケーリング
     *
     * 呼び出し側で `if (scaled !== bitmap)` チェックを行い、新しいインスタンスの場合のみ recycle すること。
     *
     * @param bitmap スケーリング対象の Bitmap
     * @return スケーリング済みの Bitmap（元の bitmap または新規作成された Bitmap）
     */
    private suspend fun scaleBitmapForVision(bitmap: Bitmap): Bitmap {
        // メモリ監視：スケーリング前のメモリ状態をチェック
        val memoryOk = memoryObserver.requestMemoryCorrectionIfNeeded(appContext)
        if (!memoryOk) {
            Log.w(TAG, "Memory insufficient for bitmap scaling, returning original")
            return bitmap
        }
        
        // BitmapRecycleHelper を使用した安全なスケーリング
        return BitmapRecycleHelper.safeScaleBitmap(
            bitmap,
            maxWidth = MAX_BITMAP_EDGE,
            maxHeight = MAX_BITMAP_EDGE,
            initialQuality = 100
        )
    }

    private fun resolveModelPath(modelName: String): String {
        val lowered = modelName.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(modelName).isAbsolute) {
            return modelName
        }
        return when (ModelFileManager.resolveModelName(modelName)) {
            ModelFileManager.LocalModel.GEMMA3N_2B -> "gemma-3n-2b.task"
            ModelFileManager.LocalModel.GEMMA3N_4B -> "gemma-3n-4b.task"
            ModelFileManager.LocalModel.GEMMA4_2B -> "gemma-4-2b.litertlm"
            ModelFileManager.LocalModel.GEMMA4_4B -> "gemma-4-4b.litertlm"
        }
    }

    private fun resolveLocalModelFile(modelName: String): File? {
        val resolved = resolveModelPath(modelName)
        val lowered = resolved.lowercase()
        if ((lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(resolved).isAbsolute) {
            val file = File(resolved)
            val validated = ModelFileManager.validateImportedTaskFile(file)
            if (validated.isFailure) {
                val cause = validated.exceptionOrNull()
                Log.w(TAG, "Imported model validation failed: ${cause?.message}")
                if (cause?.message?.contains("Web用モデル") == true) {
                    throw cause
                }
                return null
            }
            return validated.getOrNull()
        }
        val localModel = ModelFileManager.resolveModelName(modelName)
        val verified = ModelFileManager.validatedModelFileForLoad(appContext, localModel)
        if (verified.isFailure) {
            Log.w(TAG, "Local model integrity check failed: ${verified.exceptionOrNull()?.message}")
            return null
        }
        return verified.getOrNull()
    }
}