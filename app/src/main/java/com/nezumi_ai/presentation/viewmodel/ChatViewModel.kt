package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import com.nezumi_ai.BuildConfig
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ToolCallState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    private val appContext: Context,
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    companion object {
        private const val TAG = "ChatViewModel"
        private const val RESPONSE_TIMEOUT_MS = 120_000L
        private const val COMPRESSION_TIMEOUT_MS = 25_000L
        /** ストリーム中の Room 更新間隔（Gallery レベル：高速更新） */
        private const val STREAM_PERSIST_INTERVAL_MS = 100L
        private const val STREAM_PERSIST_INTERVAL_TABLE_MS = 50L
        private const val DEFAULT_SESSION_TITLE = "新しいチャット"
        /** Phase 14: トークン数と文字数の変換比率（1トークン ≈ 3.5～4文字）*/
        private const val TOKEN_TO_CHAR_RATIO = 4
        private const val COMPRESSION_RECENT_MESSAGE_COUNT = 6
        private const val COMPRESSION_SUMMARY_MAX_CHARS = 700
        /** 1 回の生成の上限（ネイティブが onDone を返さない場合の保険） */
        private const val GENERATION_WALL_TIMEOUT_MS = 900_000L
        /** 最初のトークン以降、この時間チャンクが無ければ打ち切り */
        private const val GENERATION_STALL_TIMEOUT_MS = 180_000L
        private const val GENERATION_STALL_CHECK_MS = 5_000L

        /**
         * ローカル .litertlm を「破損・欠落」とみなして削除してよいときだけ true。
         * [TF_LITE_AUX not found] など TFLite/NPU ランタイムのエラーはファイル破損ではない。
         */
        private fun shouldDeleteLocalModelFileOnLoadError(errorMessage: String): Boolean {
            if (errorMessage.contains("TF_LITE", ignoreCase = true)) return false
            return errorMessage.contains("Cannot read", ignoreCase = true) ||
                errorMessage.contains("not found", ignoreCase = true) ||
                errorMessage.contains("corrupt", ignoreCase = true) ||
                errorMessage.contains("invalid", ignoreCase = true)
        }
    }

    private class FirstTokenTimeoutException : CancellationException("FIRST_TOKEN_TIMEOUT")

    private class GenerationStalledException : Exception("GENERATION_STALLED")

    private class GenerationWallTimeoutException : Exception("GENERATION_WALL_TIMEOUT")

    private data class CompressedContextCache(
        val signature: Int,
        val summary: String
    )
    
    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId
    
    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages
    
    private val _pendingMediaMessage = MutableStateFlow<MessageEntity?>(null)
    val pendingMediaMessage: StateFlow<MessageEntity?> = _pendingMediaMessage
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedModel = MutableStateFlow("Gemma4-2B")
    val selectedModel: StateFlow<String> = _selectedModel

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading

    private val _modelLoadingStatus = MutableStateFlow("")
    val modelLoadingStatus: StateFlow<String> = _modelLoadingStatus

    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing

    /** true のとき、このチャットでは設定のシンキングONでも LiteRT の enable_thinking を付けない */
    private val _chatSessionDisableThinking = MutableStateFlow(false)
    val chatSessionDisableThinking: StateFlow<Boolean> = _chatSessionDisableThinking.asStateFlow()

    private val _sessionTitle = MutableStateFlow(DEFAULT_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle

    private val _contextUsageChars = MutableStateFlow(0)
    val contextUsageChars: StateFlow<Int> = _contextUsageChars

    private val _contextWindowSize = MutableStateFlow(4096)
    val contextWindowSize: StateFlow<Int> = _contextWindowSize

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage
    
    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent
    
    enum class NavigationEvent {
        BACK_TO_HOME,  // ホームスクリーンに戻る
        CLEAR_CHAT     // チャット画面をクリア
    }
    
    private val _toolCallState = MutableStateFlow<ToolCallState?>(null)
    val toolCallState: StateFlow<ToolCallState?> = _toolCallState.asStateFlow()

    data class MemoryWarningInfo(
        val modelName: String,
        val predictedUsagePercent: Int,
        val currentUsagePercent: Int,
        val currentUsageMB: Long,
        val maxMB: Long
    )

    private val _memoryWarning = MutableStateFlow<MemoryWarningInfo?>(null)
    val memoryWarning: StateFlow<MemoryWarningInfo?> = _memoryWarning.asStateFlow()

    fun dismissMemoryWarning() {
        _memoryWarning.value = null
    }

    fun proceedWithModelLoad(model: String, config: InferenceConfig) {
        viewModelScope.launch {
            _memoryWarning.value = null
            // メモリ警告をスキップしてロードを続行
            loadModelWithOverlay(model, config, onlyIfAvailable = false, skipMemoryWarning = true)
        }
    }

    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null
    private var messagesCollectionJob: Job? = null
    private val compressedContextCache = mutableMapOf<Long, CompressedContextCache>()
    private var currentBackendType = "CPU"  // GPU時はキャッシュを無効化するためのフラグ
    private val userTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:User|ユーザー)\\s*[:：]")
    private val assistantTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:Assistant|アシスタント)\\s*[:：]")
    private val roleTurnMarkerRegex =
        Regex("(?i)(?:^|[\\s\\n\\r])(?:User|Assistant|ユーザー|アシスタント)\\s*[:：]")
    
    // WakeLock管理
    private var screenWakeLock: PowerManager.WakeLock? = null
    private val powerManager: PowerManager? by lazy {
        appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
    }
    
    init {
        // Phase 13: アプリ起動時に isStreaming フラグをクリーニング
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fixedCount = messageRepository.cleanupStreamingFlags()
                if (fixedCount > 0) {
                    Log.w(TAG, "STARTUP: Cleaned up $fixedCount streaming messages")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error cleaning streaming flags on startup", t)
            }
        }
        
        // Phase 14: アプリ起動時のメモリ確認ログ出力
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detailedMem = MemoryObserver.getDetailedMemoryInfo(appContext)
                Log.d(TAG, "STARTUP_MEMORY_INFO:\n$detailedMem")
                
                val memStatus = MemoryObserver.getMemoryStatus(appContext)
                Log.d(TAG, "STARTUP_MEMORY_STATUS: level=${memStatus.level} used=${memStatus.usedMB}MB max=${memStatus.maxMB}MB percent=${memStatus.usedPercent}% device_low=${memStatus.isLowMemory}")
            } catch (t: Throwable) {
                Log.e(TAG, "Error logging startup memory info", t)
            }
        }
        
        // ViewModel初期化時は設定のみ取得（モデルロードはしない）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _selectedModel.value = normalizeModel(settingsRepository.getSelectedModel())
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error initializing ModelManager", e)
            }
        }
        
        // バックエンド設定変更を監視
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settingsRepository.getSettings().collect { settings ->
                    if (settings != null) {
                        val currentBackend = settingsRepository.getBackendForModel(_selectedModel.value)
                        setBackendType(currentBackend)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error monitoring settings changes", e)
            }
        }
        
        // モデル変更を監視してコンテキストウィンドウを更新
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _selectedModel.collect { model ->
                    val contextWindow = settingsRepository.getContextWindowForModel(model)
                    _contextWindowSize.value = contextWindow
                    Log.d(TAG, "Context window updated for model=$model: $contextWindow")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error monitoring model changes", e)
            }
        }
    }
    
    fun setCurrentSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        // デフォルトは「設定に従う（= シンキングOFFを強制しない）」
        _chatSessionDisableThinking.value = false
        
        // セッション遷移時に前の推論をキャンセルし、KVキャッシュを確実にクリア
        stopGeneration()
        
        // キャンセル前のコレクションジョブ
        messagesCollectionJob?.cancel()
        
        messagesCollectionJob = viewModelScope.launch {
            Log.d(TAG, "START_MESSAGE_COLLECTION: sessionId=$sessionId")
            messageRepository.getMessagesForSession(sessionId)
                .collect { msgs ->
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            TAG,
                            "UPDATE_MESSAGES_FLOW: count=${msgs.size} messages=${msgs.map { "${it.role}:${it.content.take(30)}" }}"
                        )
                    }
                    // Room の Flow は参照を再利用することがあるため、toList() でコピーして新しいオブジェクト参照を作る
                    _messages.value = msgs.toList()
                    _contextUsageChars.value = estimateContextUsageChars(msgs)
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            _sessionTitle.value = session.name
        }
        viewModelScope.launch(Dispatchers.IO) {
            // セッション遷移時に圧縮コンテキストキャッシュをクリア
            clearCompressedContextCache(sessionId)
            // チャット画面表示時にはモデルをロードしない。
            // 実ロードは送信時(generateAIResponse)に遅延させる。
            _selectedModel.value = normalizeModel(settingsRepository.getSelectedModel())
        }
    }
    
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun setChatSessionDisableThinking(disabled: Boolean) {
        // 設定値のみ更新。モデルリロードは行わない。
        // 次のメッセージ送信時に新しい設定が自動的に適用される。
        Log.d(TAG, "setChatSessionDisableThinking: disabled=$disabled")
        _chatSessionDisableThinking.value = disabled
        viewModelScope.launch {
            _uiMessage.emit(if (disabled) "このチャットでシンキング: OFF" else "このチャットでシンキング: ON")
        }
    }

    fun switchModel(model: String) {
        if (_isLoading.value || _isModelLoading.value) {
            viewModelScope.launch {
                _uiMessage.emit("生成中またはモデル処理中はモデル切替できません")
            }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedModel = normalizeModel(model)
            settingsRepository.updateModel(normalizedModel)
            _selectedModel.value = normalizedModel
            val config = chatInferenceConfigForModel(normalizedModel)
            val result = loadModelWithOverlay(normalizedModel, config, onlyIfAvailable = true)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                Log.e(TAG, "Failed to switch model: $normalizedModel", error)
                
                // メモリ不足エラーを検出
                if (error?.message?.contains("memory") == true || 
                    error?.message?.contains("Memory") == true) {
                    _uiMessage.emit("メモリが不足しています。ホームスクリーンに戻ります...")
                    _navigationEvent.emit(NavigationEvent.BACK_TO_HOME)
                    return@launch
                }
                
                // ファイル読み込みエラーを検出（PATH NOT FOUND など）
                val errorMsg = error?.message ?: ""
                if (shouldDeleteLocalModelFileOnLoadError(errorMsg)) {
                    
                    Log.w(TAG, "モデルファイルの読み込みエラー: $normalizedModel")
                    _uiMessage.emit("❌ モデルファイルが読み込めません。設定画面で再ダウンロードしてください。")
                    
                    // ファイルを削除してリセット
                    try {
                        val modelEnum = when (normalizedModel.uppercase()) {
                            "GEMMA4-4B" -> ModelFileManager.LocalModel.GEMMA4_4B
                            "GEMMA4-2B" -> ModelFileManager.LocalModel.GEMMA4_2B
                            "E4B" -> ModelFileManager.LocalModel.GEMMA3N_4B
                            "E2B" -> ModelFileManager.LocalModel.GEMMA3N_2B
                            else -> ModelFileManager.LocalModel.GEMMA4_2B  // デフォルト
                        }
                        ModelFileManager.clearCorruptedModel(appContext, modelEnum)
                        Log.i(TAG, "モデルファイルをクリアしました")
                    } catch (e: Exception) {
                        Log.e(TAG, "モデルファイルのクリアに失敗", e)
                    }
                }
            }
        }
    }
    
    fun sendMessage(userMessage: String) {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value) return
        
        // 前の job をキャンセル
        generationJob?.cancel(CancellationException("Stopped by user"))
        generationJob = null
        
        generationJob = viewModelScope.launch {
            val thisJob = this // このJobインスタンスを保存
            try {
                // ユーザーメッセージを保存
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "user",
                    content = userMessage
                )
                
                // セッションの lastUpdated を更新
                sessionRepository.updateSessionLastUpdated(sessionId)
                
                // 入力フィールドをクリア
                _inputText.value = ""
                
                // AI応答を生成
                _isLoading.value = true
                generateAIResponse(sessionId, userMessage)
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error sending message", e)
            } finally {
                _isLoading.value = false
                // このJobがまだcurrentなら null にする（前のJobから overwrite されない）
                if (generationJob == thisJob) {
                    generationJob = null
                }
            }
        }
    }

    fun compressContextManually() {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value) {
            viewModelScope.launch {
                _uiMessage.emit("生成中は圧縮できません")
            }
            return
        }
        if (_isCompressing.value) {
            viewModelScope.launch {
                _uiMessage.emit("圧縮処理中です")
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = requireModelManager()
                val selectedModel = normalizeModel(settingsRepository.getSelectedModel())
                _selectedModel.value = selectedModel
                val engineModelName = toEngineModelName(selectedModel)
                if (!ModelFileManager.isModelAvailable(appContext, engineModelName)) {
                    _uiMessage.emit("モデル未ダウンロードのため圧縮できません")
                    return@launch
                }

                val config = settingsRepository.getInferenceConfigForModel(selectedModel)
                val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
                if (loadResult.isFailure) {
                    val error = loadResult.exceptionOrNull()
                    val errorMsg = error?.message ?: "Unknown error"
                    Log.e(TAG, "Compression model load failed: $errorMsg", error)
                    _uiMessage.emit("圧縮用モデルのロードに失敗しました：$errorMsg")
                    return@launch
                }

                val messages = messageRepository.getMessagesForSessionOnce(sessionId)
                    .filterNot { shouldExcludeFromModelContext(it) }
                if (messages.isEmpty()) {
                    _uiMessage.emit("圧縮対象のコンテキストがありません")
                    return@launch
                }

                val compressionTarget = if (messages.size > COMPRESSION_RECENT_MESSAGE_COUNT) {
                    messages.dropLast(COMPRESSION_RECENT_MESSAGE_COUNT)
                } else {
                    messages
                }
                val signature = compressionTarget.fold(17) { acc, msg ->
                    ((acc * 31) + msg.role.hashCode()) * 31 + msg.content.hashCode()
                }
                
                // GPU時はキャッシュを使用せず常に再計算（メモリ安定性優先）
                val useCache = currentBackendType != "GPU"
                val cached = if (useCache) compressedContextCache[sessionId] else null
                
                if (cached != null && cached.signature == signature) {
                    _uiMessage.emit("圧縮コンテキストは最新です")
                    return@launch
                }

                _isCompressing.value = true
                val summary = try {
                    requestCompressedContextSummary(
                        sessionId = sessionId,
                        manager = manager,
                        messages = compressionTarget,
                        config = config
                    )
                } finally {
                    _isCompressing.value = false
                }
                
                // GPU時はキャッシュに保存しない
                if (useCache) {
                    compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
                }
                
                // 圧縮完了後、コンテキスト使用量を再計算して UI に反映
                val updatedMessages = messageRepository.getMessagesForSessionOnce(sessionId)
                _contextUsageChars.value = estimateContextUsageChars(updatedMessages)
                
                Log.d(TAG, "Context compression completed successfully. Messages will use compressed context on next send.")
                _uiMessage.emit("✅ コンテキストを圧縮しました\n次のメッセージ送信から圧縮コンテキストが使用されます")
            } catch (t: Throwable) {
                _isCompressing.value = false
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Manual context compression failed", e)
                _uiMessage.emit("圧縮に失敗しました: ${e.message}")
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel(CancellationException("Stopped by user"))
        generationJob = null  // 新しいJobとの競合を即座に防ぐ
        _isLoading.value = false
        
        // Gallery方式：Conversation.cancelProcess() を呼ぶ（KV cache は保持）
        viewModelScope.launch {
            try {
                val manager = requireModelManager()
                manager.cancelInference()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cancel inference", e)
            }
        }
    }

    fun revokeLastPrompt() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val messages = messageRepository.getMessagesForSessionOnce(sessionId)
            val lastUserIndex = messages.indexOfLast { it.role == "user" }
            if (lastUserIndex < 0) {
                _uiMessage.emit("取り消せるプロンプトがありません")
                return@launch
            }
            revokePromptFromMessageInternal(sessionId, messages[lastUserIndex].id)
        }
    }

    fun revokePromptFromMessage(promptMessageId: Long) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            revokePromptFromMessageInternal(sessionId, promptMessageId)
        }
    }

    private suspend fun revokePromptFromMessageInternal(sessionId: Long, promptMessageId: Long) {
        stopGeneration()
        val messages = messageRepository.getMessagesForSessionOnce(sessionId)
        val targetIndex = messages.indexOfFirst { it.id == promptMessageId && it.role == "user" }
        if (targetIndex < 0) {
            _uiMessage.emit("取り消せるプロンプトがありません")
            return
        }

        val toDelete = messages.subList(targetIndex, messages.size)
        toDelete.forEach { msg ->
            MessageMediaStore.deleteStoredFileIfOwned(appContext, msg.imageUri)
            MessageMediaStore.deleteStoredFileIfOwned(appContext, msg.audioUri)
            messageRepository.deleteMessageById(msg.id)
        }
        compressedContextCache.remove(sessionId)
        sessionRepository.updateSessionLastUpdated(sessionId)
        _uiMessage.emit("プロンプトを取り消しました")
    }
    
    private suspend fun generateAIResponse(
        sessionId: Long,
        userMessage: String,
        images: List<Bitmap> = emptyList(),
        audioClips: List<ByteArray> = emptyList()
    ) {
        var streamingMessageId: Long? = null
        val aiStartMs = System.currentTimeMillis()  // Phase 11: 全体ロード時間を計測開始
        try {
            // Acquire WakeLock to prevent screen sleep during generation
            acquireScreenWakeLock()
            
            // Tool Call State マシンをリセット（非同期化して TTFT を短縮）
            viewModelScope.launch {
                _toolCallState.value = null
            }
            
            val manager = requireModelManager()
            val selectedModel = normalizeModel(settingsRepository.getSelectedModel())
            
            // メモリ不足チェック
            val memoryPercent = manager.getMemoryUsagePercent()
            if (memoryPercent >= 85) {
                Log.w(TAG, "generateAIResponse: Memory usage too high: $memoryPercent%")
                _uiMessage.emit("メモリが不足しています。ホームスクリーンに戻ります...")
                _navigationEvent.emit(NavigationEvent.BACK_TO_HOME)
                return
            }
            _selectedModel.value = selectedModel
            val engineModelName = toEngineModelName(selectedModel)
            if (!ModelFileManager.isModelAvailable(appContext, engineModelName)) {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    content = "選択モデル($selectedModel)が未ダウンロードです。設定画面でダウンロードしてください。"
                )
                return
            }
            val config = chatInferenceConfigForModel(selectedModel)
            val backend = settingsRepository.getBackendForModel(selectedModel)
            val aiStartMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse START: model=$selectedModel, enableThinking=${config.enableThinking}, backend=$backend, memoryUsage=$memoryPercent%")
            
            // Phase 11: ロード進捗ログの細分化
            val modelLoadStartMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse LOAD_START: model=$selectedModel")
            
            val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
            
            val modelLoadEndMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse LOAD_END: model=$selectedModel duration=${modelLoadEndMs - modelLoadStartMs}ms success=${loadResult.isSuccess}")
            
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown error"
                Log.e(TAG, "Model loading failed for $selectedModel: $errorMsg", error)
                
                // メモリ不足エラーを検出
                if (errorMsg.contains("memory", ignoreCase = true)) {
                    _uiMessage.emit("メモリが不足しています。ホームスクリーンに戻ります...")
                    _navigationEvent.emit(NavigationEvent.BACK_TO_HOME)
                    return
                }
                
                // ファイル読み込みエラーを検出
                if (shouldDeleteLocalModelFileOnLoadError(errorMsg)) {
                    
                    Log.w(TAG, "モデルファイルの読み込みエラー: $selectedModel")
                    _uiMessage.emit("❌ モデルファイルが読み込めません。設定画面で再ダウンロードしてください。")
                    
                    // ファイルを削除してリセット
                    try {
                        val modelEnum = when (selectedModel.uppercase()) {
                            "GEMMA4-2B" -> ModelFileManager.LocalModel.GEMMA4_2B
                            "GEMMA4-4B" -> ModelFileManager.LocalModel.GEMMA4_4B
                            else -> ModelFileManager.LocalModel.GEMMA4_2B
                        }
                        ModelFileManager.clearCorruptedModel(appContext, modelEnum)
                        Log.i(TAG, "モデルファイルをクリアしました")
                    } catch (e: Exception) {
                        Log.e(TAG, "モデルファイルのクリアに失敗", e)
                    }
                    return
                }
                
                throw (error ?: IllegalStateException("モデルのロード($selectedModel)に失敗しました: $errorMsg"))
            }
            
            Log.d(TAG, "Starting inference for session $sessionId")
            
            val promptForModel = buildPromptWithSessionContext(sessionId, config, manager)

            // ストリーミング推論を実行（マルチモーダル対応）
            val aiResponseFlow = withContext(Dispatchers.IO) {
                if (images.isNotEmpty() || audioClips.isNotEmpty()) {
                    // マルチモーダル推論
                    Log.d(TAG, "Using multimodal inference: ${images.size} images, ${audioClips.size} audio clips")
                    manager.runInferenceWithMedia(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        images = images,
                        audioClips = audioClips,
                        config = config
                    )
                } else {
                    // テキストのみ推論
                    Log.d(TAG, "Using text-only inference")
                    manager.runInference(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        config = config
                    )
                }
            }

            streamingMessageId = messageRepository.addMessage(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                isStreaming = true
            )
            val activeStreamingMessageId = streamingMessageId
                ?: throw IllegalStateException("Failed to create streaming message")

            val answerBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var nativeThinkingStream = false
            var lastPersistedContent = ""
            var lastPersistedThinking: String? = null
            var lastPersistAt = 0L
            var toolResultsJson: String? = null

            // ストリーム内容を収集
            // タイムアウトは「最初の出力が来るまで」のみ有効。
            val firstTokenSeen = AtomicBoolean(false)
            val lastChunkAt = AtomicLong(SystemClock.elapsedRealtime())
            val wallEndAt = SystemClock.elapsedRealtime() + GENERATION_WALL_TIMEOUT_MS
            var streamAbortNote: String? = null
            try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val firstTokenTimeoutJob = launch {
                            delay(RESPONSE_TIMEOUT_MS)
                            if (!firstTokenSeen.get()) {
                                cancel(FirstTokenTimeoutException())
                            }
                        }

                        val stallWatchJob = launch {
                            while (isActive) {
                                delay(GENERATION_STALL_CHECK_MS)
                                if (!firstTokenSeen.get()) continue
                                val idle = SystemClock.elapsedRealtime() - lastChunkAt.get()
                                if (idle >= GENERATION_STALL_TIMEOUT_MS) {
                                    throw GenerationStalledException()
                                }
                            }
                        }

                        try {
                            aiResponseFlow.collect { chunk ->
                                if (SystemClock.elapsedRealtime() > wallEndAt) {
                                    throw GenerationWallTimeoutException()
                                }
                                if (!firstTokenSeen.getAndSet(true)) {
                                    firstTokenTimeoutJob.cancel()
                                }
                                lastChunkAt.set(SystemClock.elapsedRealtime())
                                val finalFromModel = InferenceStreamProtocol.decodeFinal(chunk)
                                val thinkDelta = InferenceStreamProtocol.decodeThinkChunk(chunk)
                                val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(chunk)
                                val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(chunk)
                                val toolResults = InferenceStreamProtocol.decodeToolResults(chunk)
                                if (finalFromModel != null) {
                                    Log.d(TAG, "FINAL received: length=${finalFromModel.length}")
                                    answerBuilder.clear()
                                    answerBuilder.append(finalFromModel)
                                } else if (thinkDelta != null) {
                                    nativeThinkingStream = true
                                    if (thinkDelta.isNotEmpty()) {
                                        val curT = thinkingBuilder.toString()
                                        val mergedT = mergeStreamingChunk(curT, thinkDelta)
                                        if (mergedT != curT && mergedT.length >= curT.length) {
                                            thinkingBuilder.clear()
                                            thinkingBuilder.append(mergedT)
                                        } else if (mergedT.length < curT.length) {
                                            Log.w(
                                                TAG,
                                                "Thinking chunk merge would shrink: ${curT.length} -> ${mergedT.length}, skipping"
                                            )
                                        }
                                    }
                                } else if (toolCallChunk != null) {
                                    // Tool Call チャンク処理：実行中の詳細フィードバック
                                    Log.d(TAG, "Tool call detected: $toolCallChunk")
                                    val toolNames = toolCallChunk.split(",").map { it.trim() }
                                    for (toolName in toolNames) {
                                        // ToolCallState を Executing に更新（async で非ブロッキング）
                                        viewModelScope.launch {
                                            _toolCallState.value = ToolCallState.Executing(
                                                toolName = toolName,
                                                elapsedMs = System.currentTimeMillis() - lastChunkAt.get()
                                            )
                                        }
                                        
                                        val executingMsg = when (toolName) {
                                            "set_alarm" -> "⏰ アラームを設定中..."
                                            "send_message" -> "💬 メッセージを送信中..."
                                            "search" -> "🔍 検索中..."
                                            else -> "🔧 $toolName を実行中..."
                                        }
                                        _uiMessage.emit(executingMsg)
                                        Log.d(TAG, "Tool execution started: $toolName")
                                    }
                                } else if (toolResultChunk != null) {
                                    // Tool Result チャンク処理：実行結果のフィードバック
                                    Log.d(TAG, "Tool result received: $toolResultChunk")
                                    val parts = toolResultChunk.split(":", limit = 2)
                                    if (parts.size >= 2) {
                                        val toolName = parts[0].trim()
                                        val status = parts[1].trim()
                                        
                                        // ToolCallState を Result に更新（async で非ブロッキング）
                                        viewModelScope.launch {
                                            _toolCallState.value = ToolCallState.Result(
                                                toolName = toolName,
                                                status = if (status.contains("success", ignoreCase = true)) "success" else "error",
                                                resultMessage = status
                                            )
                                        }
                                        
                                        val resultMsg = when (status) {
                                            "success" -> "✅ $toolName: 成功"
                                            "error" -> "❌ $toolName: 実行失敗"
                                            else -> "⏳ $toolName: ${status}"
                                        }
                                        _uiMessage.emit(resultMsg)
                                        Log.d(TAG, "Tool execution completed: $toolName status=$status")
                                    }
                                } else if (toolResults != null) {
                                    // ツール実行結果JSON（テーブル保存用）
                                    toolResultsJson = toolResults
                                    Log.d(TAG, "Tool results JSON received: length=${toolResults.length}")
                                } else {
                                    val executedToolsList = InferenceStreamProtocol.decodeExecutedToolsList(chunk)
                                    if (executedToolsList != null) {
                                        // 実行されたツール一覧を UI に表示
                                        Log.d(TAG, "Executed tools list: $executedToolsList")
                                        if (executedToolsList.isNotEmpty()) {
                                            val toolsDisplay = executedToolsList.joinToString(", ")
                                            val toolListMsg = "🔧 実行ツール: $toolsDisplay"
                                            _uiMessage.emit(toolListMsg)
                                        }
                                    } else {
                                        if (chunk.isNotEmpty()) {
                                            val currentContent = answerBuilder.toString()
                                            if (BuildConfig.DEBUG) {
                                                Log.d(TAG, "RAW_CHUNK: length=${chunk.length} content='${chunk.take(100)}'")
                                            }
                                            val merged = mergeStreamingChunk(currentContent, chunk)
                                            if (merged != currentContent && merged.length >= currentContent.length) {
                                                answerBuilder.clear()
                                                answerBuilder.append(merged)
                                                if (BuildConfig.DEBUG) {
                                                    Log.d(
                                                        TAG,
                                                        "Chunk merged: ${currentContent.length} -> ${merged.length} chars (added ${merged.length - currentContent.length} chars)"
                                                )
                                                if (merged.length - currentContent.length != chunk.length) {
                                                    Log.w(TAG, "⚠ OVERLAP DETECTED: chunk=${chunk.length} chars, but added only ${merged.length - currentContent.length} chars")
                                                }
                                                            }
                                                    } else if (merged.length < currentContent.length) {
                                                        Log.w(TAG, "❌ Chunk merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping merge")
                                                        if (BuildConfig.DEBUG) {
                                                            Log.w(TAG, "  original chunk: '${chunk.take(80)}'")
                                                            Log.w(TAG, "  current: '${currentContent.take(80)}'")
                                                            Log.w(TAG, "  merged: '${merged.take(80)}'")
                                                        }
                                                    } else if (merged == currentContent) {
                                                        // chunk が既に反映済み
                                                        if (BuildConfig.DEBUG) {
                                                            Log.d(TAG, "DUPLICATE_CHUNK: skipped (already present)")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                val messageIdToUpdate = streamingMessageId ?: activeStreamingMessageId
                                messageIdToUpdate?.let { id ->
                                    val contentForUi: String
                                    val thinkingForUi: String?
                                    if (nativeThinkingStream) {
                                        contentForUi =
                                            sanitizeAssistantOutputForModel(
                                                engineModelName = engineModelName,
                                                text = Gemma4ThinkingParser.sanitizeVisibleText(answerBuilder.toString())
                                            )
                                        thinkingForUi =
                                            Gemma4ThinkingParser.sanitizeVisibleText(thinkingBuilder.toString())
                                                .ifBlank { null }
                                        if (BuildConfig.DEBUG && (contentForUi.isNotEmpty() || !thinkingForUi.isNullOrBlank())) {
                                            Log.d(TAG, "CONTENT_THINKING_STATE: content_len=${contentForUi.length} thinking_len=${thinkingForUi?.length ?: 0}")
                                        }
                                    } else {
                                        val parsedStream =
                                            Gemma4ThinkingParser.parseStreaming(answerBuilder.toString())
                                        contentForUi =
                                            sanitizeAssistantOutputForModel(
                                                engineModelName = engineModelName,
                                                text = parsedStream.answer
                                            )
                                        thinkingForUi = parsedStream.thinking
                                    }
                                    val now = SystemClock.elapsedRealtime()
                                    val persistInterval = if (isLikelyMarkdownTable(contentForUi)) {
                                        STREAM_PERSIST_INTERVAL_TABLE_MS
                                    } else {
                                        STREAM_PERSIST_INTERVAL_MS
                                    }
                                    val isFirstVisibleContent =
                                        contentForUi.isNotEmpty() && lastPersistedContent.isEmpty()
                                    val isFirstThinkingPersist =
                                        !thinkingForUi.isNullOrBlank() && lastPersistedThinking.isNullOrBlank()
                                    // Thinking フェーズのみで content が空の場合は persist を遅延させる
                                    // （content が来た時、または最終確定時のみ persist する）
                                    val isThinkingOnlyPhase = contentForUi.isEmpty() && !thinkingForUi.isNullOrBlank()
                                    val shouldPersistToDb =
                                        if (isThinkingOnlyPhase) {
                                            // Thinking のみ中は persist をスキップ
                                            false
                                        } else {
                                            // Content が存在すれば通常の persist ロジック
                                            (contentForUi != lastPersistedContent ||
                                                thinkingForUi != lastPersistedThinking) &&
                                                (finalFromModel != null ||
                                                    isFirstVisibleContent ||
                                                    isFirstThinkingPersist ||
                                                    now - lastPersistAt >= persistInterval)
                                        }
                                    if (shouldPersistToDb) {
                                        messageRepository.updateMessageContent(
                                            messageId = id,
                                            content = contentForUi,
                                            isStreaming = finalFromModel == null,
                                            thinkingContent = thinkingForUi,
                                            toolResultsJson = if (finalFromModel != null) toolResultsJson else null
                                        )
                                        lastPersistedContent = contentForUi
                                        lastPersistedThinking = thinkingForUi
                                        lastPersistAt = now
                                    } else if (isThinkingOnlyPhase) {
                                        // Thinking のみ中でも UI には即座に反映: in-memory で更新
                                        if (BuildConfig.DEBUG) {
                                            Log.d(TAG, "THINKING_ONLY_PHASE: updating in-memory id=$id thinkingLen=${thinkingForUi?.length ?: 0}")
                                        }
                                        val currentMsgs = _messages.value.toMutableList()
                                        val idx = currentMsgs.indexOfFirst { it.id == id }
                                        if (BuildConfig.DEBUG) {
                                            Log.d(TAG, "THINKING_ONLY_PHASE: found index=$idx current_messages=${currentMsgs.size}")
                                        }
                                        if (idx >= 0) {
                                            val updated = currentMsgs[idx].copy(thinkingContent = thinkingForUi)
                                            currentMsgs[idx] = updated
                                            _messages.value = currentMsgs.toList()
                                            if (BuildConfig.DEBUG) {
                                                Log.d(TAG, "THINKING_ONLY_PHASE: in-memory updated and emitted")
                                            }
                                        } else {
                                            if (BuildConfig.DEBUG) {
                                                Log.w(TAG, "THINKING_ONLY_PHASE: could not find message id=$id in ${currentMsgs.map { it.id }}")
                                            }
                                        }
                                    }
                                }
                                if (BuildConfig.DEBUG) Log.d(TAG, "Received chunk: $chunk")
                            }
                        } finally {
                            firstTokenTimeoutJob.cancel()
                            stallWatchJob.cancel()
                            Log.d(TAG, "Flow collection completed")
                        }
                    }
                }
            } catch (collectionError: Throwable) {
                when {
                    collectionError is FirstTokenTimeoutException -> {
                        Log.d(TAG, "First token timeout during flow collection")
                    }
                    collectionError is GenerationStalledException -> {
                        Log.w(TAG, "Generation stalled (no chunks); finalizing partial", collectionError)
                        streamAbortNote =
                            "\n\n（長時間出力が途切れたため、ここで打ち切りました）"
                        withContext(Dispatchers.Main) {
                            _uiMessage.emit("応答が長時間途切れました。表示された分まで保存しました。")
                        }
                    }
                    collectionError is GenerationWallTimeoutException -> {
                        Log.w(TAG, "Generation wall timeout; finalizing partial", collectionError)
                        streamAbortNote =
                            "\n\n（生成時間の上限に達したため、ここで打ち切りました）"
                        withContext(Dispatchers.Main) {
                            _uiMessage.emit("生成時間が上限に達しました。表示された分まで保存しました。")
                        }
                    }
                    collectionError is CancellationException -> {
                        Log.d(TAG, "Flow collection was cancelled: ${collectionError.message}")
                    }
                    else -> {
                        Log.e(TAG, "Error during flow collection", collectionError)
                        throw collectionError
                    }
                }
            }

            val completeResponse: String
            val finalThinking: String?
            if (nativeThinkingStream) {
                completeResponse =
                    sanitizeAssistantOutputForModel(
                        engineModelName = engineModelName,
                        text = Gemma4ThinkingParser.sanitizeVisibleText(answerBuilder.toString())
                    )
                finalThinking =
                    Gemma4ThinkingParser.sanitizeVisibleText(thinkingBuilder.toString()).ifBlank { null }
            } else {
                val finalParsed = Gemma4ThinkingParser.parse(answerBuilder.toString())
                completeResponse =
                    sanitizeAssistantOutputForModel(
                        engineModelName = engineModelName,
                        text = finalParsed.answer
                    )
                finalThinking = finalParsed.thinking
            }
            val note = streamAbortNote
            val contentToSave =
                when {
                    note == null -> completeResponse
                    completeResponse.isNotEmpty() -> completeResponse + note
                    else -> note.trim()
                }

            val hasPayload =
                contentToSave.isNotEmpty() || !finalThinking.isNullOrEmpty()

            Log.d(TAG, "Inference collection completed: hasPayload=$hasPayload, completeResponse.length=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}")

            if (hasPayload) {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Updating message content with final response")
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = contentToSave,
                        isStreaming = false,
                        thinkingContent = finalThinking,
                        toolResultsJson = toolResultsJson
                    )
                    if (contentToSave.isNotEmpty()) {
                        Log.d(TAG, "Generating session title")
                        maybeGenerateSessionTitle(sessionId, userMessage, contentToSave)
                    }
                    syncSessionTitleFromDb(sessionId)
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AI response saved to database: ${completeResponse.take(50)}...")
                }
            } else {
                Log.w(TAG, "No payload generated, saving default message")
                withContext(Dispatchers.IO) {
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = "申し訳ありません。応答を生成できませんでした。",
                        isStreaming = false,
                        thinkingContent = null
                    )
                    syncSessionTitleFromDb(sessionId)
                }
            }
        } catch (t: Throwable) {
            if (t is FirstTokenTimeoutException) {
                val id = streamingMessageId
                if (id != null) {
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = "応答開始がタイムアウトしました。もう一度お試しください。",
                        isStreaming = false,
                        thinkingContent = null
                    )
                }
                withContext(Dispatchers.Main) {
                    _uiMessage.emit("⏱️ 応答タイムアウト")
                }
                return
            }
            if (t is CancellationException) {
                val id = streamingMessageId
                if (id != null) {
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = "生成を停止しました。",
                        isStreaming = false,
                        thinkingContent = null
                    )
                }
                Log.d(TAG, "Generation cancelled: ${t.message}")
                return
            }
            val e = if (t is Exception) t else RuntimeException(t)
            Log.e(TAG, "Error generating AI response", e)

            // エラーメッセージを詳細化
            val errorMessage = when {
                e.message?.contains("Web用モデル") == true -> 
                    "このモデルはWeb用です。AndroidアプリではWeb用モデルは使用できません。本体デバイス用の.taskファイルをお使いください。"
                e.message?.contains("END header") == true || e.message?.contains("zip END header") == true -> 
                    "モデルファイル(.task)のダウンロードが不完全です。ダウンロード中に中断された可能性があります。設定画面でモデルを削除して再度ダウンロードしてください。"
                e.message?.contains("ZIPファイルが破損") == true -> 
                    "モデルファイル(.task)が破損しています。コピー中にエラーが発生した可能性があります。ファイルを削除して再度追加してください。"
                e.message?.contains("Unable to open zip archive") == true -> 
                    "モデルファイルが破損しているか不正な形式です。設定画面でモデルを再度ダウンロードしてください。"
                e.message?.contains("ZIP archive") == true ->
                    "モデルファイルの整合性チェックに失敗しました。ダウンロードが不完全な可能性があります。設定画面でモデルを削除して再度ダウンロードしてください。"
                e.message?.contains("Model not loaded") == true ->
                    "モデルがロードされていません。もう一度全てリセットしてから試してください。"
                else -> "エラー: ${e.message ?: "Unknown error"}"
            }
            val id = streamingMessageId
            if (id != null) {
                messageRepository.updateMessageContent(
                    messageId = id,
                    content = errorMessage,
                    isStreaming = false,
                    thinkingContent = null
                )
            } else {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    content = errorMessage
                )
            }
            // エラーを UI に通知
            withContext(Dispatchers.Main) {
                _uiMessage.emit("❌ " + (e.message?.take(30) ?: "エラーが発生しました"))
            }
        } finally {
            // Gallery パターン: 全パスで _isLoading を false にする
            Log.d(TAG, "Generation concluded, setting isLoading=false")
            _isLoading.value = false
            
            // Tool Call State マシンを Done に設定
            _toolCallState.value = ToolCallState.Done
            
            // Phase 11: 全体のロード時間をログ出力
            val aiTotalMs = System.currentTimeMillis() - aiStartMs
            Log.d(TAG, "generateAIResponse TOTAL_DURATION: ${aiTotalMs}ms (model load, inference, and all processing)")
            
            // Release WakeLock when generation completes
            releaseScreenWakeLock()
        }
    }

    private suspend fun chatInferenceConfigForModel(model: String): InferenceConfig {
        val base = settingsRepository.getInferenceConfigForModel(model)
        val disableThinking = _chatSessionDisableThinking.value
        val result = if (disableThinking) {
            base.copy(enableThinking = false)
        } else {
            base
        }
        Log.d(TAG, "chatInferenceConfigForModel: model=$model, disableThinking=$disableThinking, enableThinking=${result.enableThinking}")
        return result
    }

    private fun normalizeModel(model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        val isLocalTaskPath =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm") || lowered.endsWith(".gguf")) &&
                File(trimmed).isAbsolute
        
        return when {
            trimmed.equals("Gemma4-4B", ignoreCase = true) -> "Gemma4-4B"
            trimmed.equals("Gemma4-2B", ignoreCase = true) -> "Gemma4-2B"
            trimmed.equals("E4B", ignoreCase = true) -> "E4B"  // Gemma3n 4B (保持)
            trimmed.equals("E2B", ignoreCase = true) -> "E2B"  // Gemma3n 2B (保持)
            isLocalTaskPath -> trimmed
            else -> "Gemma4-2B"  // デフォルト
        }
    }

    private fun toEngineModelName(model: String): String {
        val normalized = normalizeModel(model)
        return when {
            normalized.equals("Gemma4-4B", ignoreCase = true) -> "gemma4-4b"
            normalized.equals("Gemma4-2B", ignoreCase = true) -> "gemma4-2b"
            normalized.equals("E4B", ignoreCase = true) -> "gemma-3n-4b"  // Gemma3n 4B
            normalized.equals("E2B", ignoreCase = true) -> "gemma-3n-2b"  // Gemma3n 2B
            (normalized.endsWith(".task") ||
                normalized.endsWith(".litertlm") ||
                normalized.endsWith(".gguf")) && normalized.startsWith("/") -> normalized
            else -> "gemma4-2b"  // デフォルト
        }
    }

    private suspend fun syncSessionTitleFromDb(sessionId: Long) {
        val session = sessionRepository.getSessionById(sessionId) ?: return
        _sessionTitle.value = session.name
    }

    private suspend fun maybeGenerateSessionTitle(
        sessionId: Long,
        userMessage: String,
        aiResponse: String
    ) {
        val session = sessionRepository.getSessionById(sessionId) ?: return
        if (session.name.trim() != DEFAULT_SESSION_TITLE) return
        val title = buildSessionTitle(userMessage, aiResponse)
        if (title.isBlank() || title == DEFAULT_SESSION_TITLE) return
        sessionRepository.updateSessionName(sessionId, title)
        _sessionTitle.value = title
    }

    private fun buildSessionTitle(userMessage: String, aiResponse: String): String {
        val source = sequenceOf(aiResponse, userMessage)
            .map { it.trim().replace("\n", " ") }
            .firstOrNull { it.isNotBlank() }
            ?: return DEFAULT_SESSION_TITLE
        val cleaned = source
            .replace(Regex("^[「『\"'\\s]+"), "")
            .replace(Regex("[」』\"'\\s]+$"), "")
            .replace(Regex("\\s+"), " ")
        val maxLen = 28
        return if (cleaned.length <= maxLen) cleaned else cleaned.take(maxLen).trimEnd() + "..."
    }

    private fun stripSyntheticRoleLoopTail(text: String): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""

        val markers = roleTurnMarkerRegex.findAll(normalized).take(16).toList()
        if (markers.size < 2) return normalized

        val first = markers.first()
        val cutIndex = if (first.range.first <= 2) {
            markers.getOrNull(1)?.range?.first ?: return normalized
        } else {
            first.range.first
        }
        if (cutIndex <= 0) return normalized

        val tail = normalized.substring(cutIndex)
        val hasUserTurn = userTurnMarkerRegex.containsMatchIn(tail)
        val hasAssistantTurn = assistantTurnMarkerRegex.containsMatchIn(tail)
        if (!hasUserTurn && !hasAssistantTurn) return normalized

        val clipped = normalized.substring(0, cutIndex).trimEnd().trimEnd(':', '：')
        if (clipped.isEmpty()) return normalized

        if (BuildConfig.DEBUG) {
            Log.w(
                TAG,
                "SELF_DIALOGUE_TRUNCATED: original=${normalized.length} clipped=${clipped.length}"
            )
        }
        return clipped
    }

    private fun sanitizeAssistantOutputForModel(engineModelName: String, text: String): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""
        if (!engineModelName.lowercase().endsWith(".gguf")) return normalized
        val noLoop = stripSyntheticRoleLoopTail(normalized)
        return noLoop.replace(
            Regex("^(?i)(?:Assistant|アシスタント)\\s*[:：]\\s*"),
            ""
        ).trim()
    }

    private fun sanitizeMessageContentForPrompt(msg: MessageEntity): String {
        val normalized = msg.content.trim()
        if (normalized.isEmpty()) return ""
        return if (msg.role == "assistant") {
            stripSyntheticRoleLoopTail(normalized)
                .replace(Regex("^(?i)(?:Assistant|アシスタント)\\s*[:：]\\s*"), "")
                .trim()
        } else {
            normalized
        }
    }

    private fun mergeStreamingChunk(current: String, chunk: String): String {
        if (chunk.isEmpty()) return current
        if (current.isEmpty()) return chunk
        if (chunk == current) return current

        // 累積全文が届くケース
        if (chunk.startsWith(current)) return chunk
        // 既に反映済みの重複delta
        if (current.endsWith(chunk)) return current
        // 巻き戻った累積全文らしきケースは現状維持
        if (current.startsWith(chunk)) return current

        // 保守的な重複検出: 大きすぎる重複は検出しない
        // これにより、substring操作での文字削除バグを防止
        val overlap = suffixPrefixOverlapConservative(current, chunk)
        if (overlap > 0) {
            val merged = current + chunk.substring(overlap)
            // 結果が元のテキストより短くならないことを確認
            if (merged.length >= current.length) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "MERGE_WITH_OVERLAP: overlap=$overlap chars, merged len=${merged.length}")
                }
                return merged
            }
        }

        // deltaとして連結（最終的にはFINALで確定全文に置換される）
        if (BuildConfig.DEBUG && overlap == 0) {
            Log.d(TAG, "MERGE_NO_OVERLAP: concatenating chunk as delta")
        }
        return current + chunk
    }

    private fun suffixPrefixOverlapConservative(left: String, right: String): Int {
        // 重複を検出する際、最大チェック文字数を制限して安全性を確保
        // これにより、不正な重複検出による文字削除を防止
        val maxCheckSize = minOf(left.length, right.length, 50)
        val minCheckSize = 1
        
        for (size in maxCheckSize downTo minCheckSize) {
            if (left.regionMatches(left.length - size, right, 0, size, ignoreCase = false)) {
                if (BuildConfig.DEBUG && size > 5) {
                    Log.d(TAG, "OVERLAP_FOUND: size=$size left_suffix='${left.takeLast(size)}' right_prefix='${right.take(size)}'")
                }
                return size
            }
        }
        return 0
    }

    private fun isLikelyMarkdownTable(content: String): Boolean {
        if (!content.contains('|')) return false
        val lines = content.lines()
        if (lines.size < 2) return false
        return lines.zipWithNext().any { (a, b) ->
            a.contains('|') && (b.contains("|---") || b.contains("| :") || b.contains("|-"))
        }
    }

    private suspend fun buildPromptWithSessionContext(
        sessionId: Long,
        config: InferenceConfig,
        manager: ModelManager
    ): String {
        val messages = messageRepository.getMessagesForSessionOnce(sessionId)
        if (messages.isEmpty()) return ""

        val fullPrompt = buildPromptFromMessages(messages)
        
        // Phase 12: thinkingContent が誤ってプロンプトに混入していないか検証
        val messagesWithThinking = messages.filter { it.thinkingContent != null && it.thinkingContent.isNotEmpty() }
        if (messagesWithThinking.isNotEmpty()) {
            Log.d(TAG, "PROMPT_BUILD: Messages with thinkingContent found (count=${messagesWithThinking.size}), but they are excluded from prompt as designed")
        }
        
        if (!config.contextCompressionEnabled) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        // Phase 14: contextWindow はトークン数。閾値計算も「トークン数 × パーセント」
        val thresholdChars =
            ((config.contextWindow * config.contextCompressionThresholdPercent) / 100).coerceAtLeast(1)
        if (fullPrompt.length < thresholdChars) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val validMessages = messages.filterNot { shouldExcludeFromModelContext(it) }
        val recentMessageCount = recentMessageCountForWindow(config.contextWindow)
        if (validMessages.size <= recentMessageCount) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val olderMessages = validMessages.dropLast(recentMessageCount)
        val recentMessages = validMessages.takeLast(recentMessageCount)
        val signature = olderMessages.fold(17) { acc, msg ->
            ((acc * 31) + msg.role.hashCode()) * 31 + msg.content.hashCode()
        }
        
        // GPU時はキャッシュを使用せず常に再計算（メモリ安定性優先）
        val useCache = currentBackendType != "GPU"
        val cached = if (useCache) compressedContextCache[sessionId] else null
        
        val compressedSummary = if (cached != null && cached.signature == signature) {
            cached.summary
        } else {
            _isCompressing.value = true
            try {
                requestCompressedContextSummary(
                    sessionId = sessionId,
                    manager = manager,
                    messages = olderMessages,
                    config = config
                ).also { summary ->
                    // GPU時はキャッシュに保存しない
                    if (useCache) {
                        compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
                    }
                }
            } finally {
                _isCompressing.value = false
            }
        }

        val contextBuilder = StringBuilder()
        if (settingsRepository.shouldInjectGemmaThinkTrigger()) {
            contextBuilder.append("<|think|>\n")
        }
        var systemPrompt = settingsRepository.getSystemPrompt()
        val userName = settingsRepository.getUserName()
        // システムプロンプトにユーザー名を埋め込む
        if (userName.isNotEmpty()) {
            systemPrompt = "ユーザー名：$userName\n\n" + systemPrompt
        }
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        // 圧縮コンテキストの使用をログに記録
        Log.d(TAG, "Using compressed context for inference. Older messages (${olderMessages.size}) summarized, recent messages (${recentMessages.size}) included. Signature=$signature")
        contextBuilder.append("以下は過去会話の圧縮コンテキストです:\n")
        contextBuilder.append(compressedSummary)
        contextBuilder.append("\n\n")
        for (msg in recentMessages) {
            val content = sanitizeMessageContentForPrompt(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(content)
                .append('\n')
        }
        contextBuilder.append("Assistant:")

        return trimPromptToWindow(contextBuilder.toString(), config.contextWindow)
    }

    private suspend fun requestCompressedContextSummary(
        sessionId: Long,
        manager: ModelManager,
        messages: List<MessageEntity>,
        config: InferenceConfig
    ): String {
        if (messages.isEmpty()) return "要約: （圧縮対象なし）\nキーワード: なし"

        val transcript = messages.mapNotNull { msg ->
            val content = sanitizeMessageContentForPrompt(msg)
            if (content.isBlank()) return@mapNotNull null
            val role = if (msg.role == "assistant") "assistant" else "user"
            "$role: $content"
        }.joinToString(separator = "\n")

        val compressionPrompt = buildString {
            append("以下の会話履歴を、次回応答に必要な情報だけに圧縮してください。\n")
            append("出力は必ず日本語。JSONやMarkdownコードブロックは禁止。\n")
            append("最大4行、各行は簡潔な短文にしてください。\n")
            append("\n")
            append("含めるべき情報:\n")
            append("- ユーザーの目的・依頼内容\n")
            append("- 決定済みの前提（設定値・制約・方針）\n")
            append("- 未解決タスクや次のアクション\n")
            append("- 必要なら固有名詞・数値\n")
            append("\n")
            append("不要な情報:\n")
            append("- 挨拶、言い換え、冗長な説明\n")
            append("- 既に不要になった古い経緯\n")
            append("\n")
            append("会話履歴:\n")
            append(transcript)
        }

        val raw = withTimeoutOrNull(COMPRESSION_TIMEOUT_MS) {
            val compressionConfig = config.copy(
                temperature = config.temperature.coerceIn(0f, 0.7f),
                enableThinking = false
            ).normalized()
            val flow = manager.runInference(
                sessionId = sessionId,
                prompt = compressionPrompt,
                config = compressionConfig
            )
            val builder = StringBuilder()
            flow.collect { chunk ->
                val final = InferenceStreamProtocol.decodeFinal(chunk)
                val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(chunk)
                val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(chunk)
                if (final != null) {
                    builder.clear()
                    builder.append(final)
                } else if (toolCallChunk != null || toolResultChunk != null) {
                    // 圧縮用途ではツールイベントを本文として扱わない
                } else if (chunk.isNotEmpty()) {
                    val currentContent = builder.toString()
                    val merged = mergeStreamingChunk(currentContent, chunk)
                    // セーフガード: マージ結果が元のコンテンツより短くならないことを確認
                    if (merged != currentContent && merged.length >= currentContent.length) {
                        builder.clear()
                        builder.append(merged)
                    } else if (merged.length < currentContent.length) {
                        Log.w(TAG, "Context compression merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping")
                    }
                }
            }
            builder.toString().trim()
        }

        // JSON形式で返ってきてしまったらフィルタリング（防衛線）
        if (raw?.trim()?.startsWith("{") == true) {
            Log.w(TAG, "Context compression returned JSON format instead of natural text: $raw")
            return buildCompressedSummaryFallback(messages)
        }
        
        // 自然言語の要約が返ってきた場合（Gemma 4 のシンキングタグは除去して本文だけ使う）
        return if (!raw.isNullOrBlank()) {
            val answerOnly = Gemma4ThinkingParser.parse(raw.trim()).answer.ifBlank { raw.trim() }
            val compact = compactCompressionSummary(answerOnly, COMPRESSION_SUMMARY_MAX_CHARS)
            buildString {
                append("要約: ")
                append(compact)
            }
        } else {
            buildCompressedSummaryFallback(messages)
        }
    }

    /**
     * Phase 14: コンテキストウィンドウ（トークン数）から取得すべき最近メッセージ数を計算
     * contextWindow はトークン数で表現される（例：4096 tokens）
     */
    private fun recentMessageCountForWindow(contextWindow: Int): Int {
        return when {
            contextWindow <= 2048 -> 4    // トークン2048以下：最近4メッセージ取得
            contextWindow <= 4096 -> 6    // トークン4096以下：最近6メッセージ取得
            else -> 8                      // トークン4096以上：最近8メッセージ取得
        }
    }

    private fun compactCompressionSummary(summary: String, maxChars: Int): String {
        val compact = summary
            .replace(Regex("[\\r\\n]+"), "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (compact.length <= maxChars) return compact
        return compact.take(maxChars).trimEnd() + "..."
    }

    private fun parseCompressionJson(raw: String): Pair<String, List<String>>? {
        val jsonText = extractJsonObject(raw) ?: return null
        return runCatching {
            val obj = JSONObject(jsonText)
            val summary = obj.optString("summary").trim()
            if (summary.isBlank()) return null
            val keywords = mutableListOf<String>()
            val arr = obj.optJSONArray("keywords")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val kw = arr.optString(i).trim()
                    if (kw.isNotBlank()) keywords += kw
                }
            }
            val normalized = keywords.distinct().take(8)
            Pair(summary, if (normalized.isNotEmpty()) normalized else listOf("要点"))
        }.getOrNull()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val end = text.lastIndexOf('}')
        if (end <= start) return null
        return text.substring(start, end + 1)
    }

    private suspend fun estimateContextUsageChars(messages: List<MessageEntity>): Int {
        // Phase 14: プロンプトの現在の文字数を推定（実際の制限は config.contextWindow（トークン数）に依存）
        // trimPromptToWindow で contextWindow トークン × TOKEN_TO_CHAR_RATIO で文字数に変換される
        return buildPromptFromMessages(messages).length
    }

    private fun isAssistantErrorLikeMessage(content: String): Boolean {
        val t = content.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("エラー:", ignoreCase = true)) return true
        return t.contains("Status Code:", ignoreCase = true) ||
            t.contains("Failed to invoke the compiled model", ignoreCase = true) ||
            t.contains("モデルがロードされていません", ignoreCase = true) ||
            t.contains("応答開始がタイムアウト", ignoreCase = true) ||
            t.contains("生成を停止しました", ignoreCase = true) ||
            t.contains("応答を生成できませんでした", ignoreCase = true)
    }

    private fun shouldExcludeFromModelContext(msg: MessageEntity): Boolean {
        if (msg.role != "assistant") return false
        if (msg.isStreaming) return true
        // Phase 12: thinkingContent の混入リスク対策
        if (msg.thinkingContent != null && msg.thinkingContent.isNotEmpty()) {
            Log.w(TAG, "WARNING: Message has thinkingContent but will be excluded from model context: id=${msg.id}")
        }
        return isAssistantErrorLikeMessage(msg.content)
    }

    private suspend fun buildPromptFromMessages(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return ""
        
        // Phase 13: 整合性チェック - imageUri がないのに imageDescription がある場合を検出
        val orphanedDescriptions = messages.filter {
            it.imageDescription != null && 
            it.imageDescription.isNotEmpty() && 
            (it.imageUri.isNullOrEmpty())
        }
        if (orphanedDescriptions.isNotEmpty()) {
            Log.w(TAG, "PROMPT_BUILD: Found ${orphanedDescriptions.size} orphaned imageDescription(s). These will be ignored: ${orphanedDescriptions.map { it.id }}")
        }
        
        val contextBuilder = StringBuilder()
        if (settingsRepository.shouldInjectGemmaThinkTrigger()) {
            contextBuilder.append("<|think|>\n")
        }
        val systemPrompt = settingsRepository.getSystemPrompt()
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        
        // Phase 12: thinkingContent の混入防止
        // プロンプトには msg.content のみを使用。thinkingContent は絶対に含めない
        for (msg in messages) {
            if (shouldExcludeFromModelContext(msg)) continue
            val content = sanitizeMessageContentForPrompt(msg)
            if (content.isBlank()) continue
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(content)  // content のみ。thinkingContent は含めない
                .append('\n')
        }
        contextBuilder.append("Assistant:")
        return contextBuilder.toString()
    }

    private fun buildCompressedSummaryFallback(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return "（圧縮対象なし）"
        // Phase 12: 圧縮時も content のみを使用。thinkingContent は含めない
        return messages.takeLast(24).mapNotNull { msg ->
            val role = if (msg.role == "assistant") "A" else "U"
            val text = sanitizeMessageContentForPrompt(msg)
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .let { if (it.length > 80) it.take(80).trimEnd() + "..." else it }
            if (text.isBlank()) return@mapNotNull null
            "[$role] $text"
        }.joinToString(separator = "\n")
    }

    private fun trimPromptToWindow(prompt: String, contextWindowTokens: Int): String {
        // Phase 14: トークン数を文字数に変換して制限
        // contextWindow はモデルのコンテキストウィンドウ（トークン数）
        // 実際のプロンプト長制限は文字数で行う（1トークン ≈ 4文字）
        val maxChars = contextWindowTokens * TOKEN_TO_CHAR_RATIO
        if (prompt.length <= maxChars) return prompt
        val trimmed = prompt.takeLast(maxChars)
        Log.d(TAG, "TRIM_PROMPT: contextWindow=$contextWindowTokens tokens (~${maxChars} chars) | original=${prompt.length} -> trimmed=${trimmed.length} chars")
        return trimmed
    }

    /**
     * 画像の説明を簡潔に生成
     * モデルが参照するための軽量な説明を作成
     */
    private fun generateImageDescription(imageUris: List<String>): String {
        val count = imageUris.size
        val fileNames = imageUris.take(3)  // 最初の3ファイルまで
            .mapNotNull { it.substringAfterLast("/").takeIf { name -> name.isNotEmpty() } }
            .joinToString(", ")
        
        return "Image: $fileNames (total $count image(s) shared)"
    }
    
    /**
     * セッション内の過去の画像説明を取得
     * モデルが判断する際に参照
     */
    /**
     * セッション内の過去の画像説明を取得
     * モデルが判断する際に参照
     * Phase 13: 整合性チェック - imageUri がない説明文を検出・ログ出力
     */
    suspend fun getImageDescriptionsInSession(sessionId: Long): List<String> {
        return withContext(Dispatchers.IO) {
            val messages = messageRepository.getMessagesForSessionOnce(sessionId)
            
            // 整合性チェック：imageUri がないのに imageDescription がある場合を検出
            val ghostDescriptions = messages.filter { 
                it.imageDescription != null && 
                it.imageDescription.isNotEmpty() && 
                (it.imageUri.isNullOrEmpty())
            }
            if (ghostDescriptions.isNotEmpty()) {
                Log.w(TAG, "INTEGRITY_WARNING: Found ${ghostDescriptions.size} messages with orphaned imageDescription (imageUri is empty): ${ghostDescriptions.map { it.id }}")
            }
            
            messages
                .filter { !(it.imageUri.isNullOrEmpty()) }  // imageUri が存在するものだけ
                .mapNotNull { it.imageDescription }
                .filter { it.isNotEmpty() }
        }
    }

    /**
     * Bitmapを1024x1024以下にダウンスケール
     */
    private fun scaleBitmapTo1024(bitmap: Bitmap): Bitmap {
        val maxSize = 1024
        if (bitmap.width <= maxSize && bitmap.height <= maxSize) {
            return bitmap
        }
        val scale = minOf(
            maxSize.toFloat() / bitmap.width,
            maxSize.toFloat() / bitmap.height
        )
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * URIからBitmapをロード
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            withContext(Dispatchers.IO) {
                // Phase 14: file:// URI と content:// URI の両方に対応
                if (uri.scheme == "file") {
                    // file:// スキーム：直接ファイルを開く
                    val path = uri.path ?: return@withContext null
                    val file = File(path)
                    if (!file.exists()) {
                        Log.w(TAG, "Image file not found: $path")
                        return@withContext null
                    }
                    file.inputStream().use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } else {
                    // content:// スキーム：contentResolver を使う
                    appContext.contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    } ?: run {
                        Log.w(TAG, "Failed to open stream for URI: $uri")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap from URI: $uri", e)
            null
        }
    }

    /**
     * URIから音声ByteArrayをロード
     */
    private suspend fun loadAudioBytesFromUri(uri: Uri): ByteArray? {
        return try {
            withContext(Dispatchers.IO) {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading audio from URI: $uri", e)
            null
        }
    }

    private suspend fun requireModelManager(): ModelManager {
        val current = modelManager
        if (current != null) return current
        return ModelManager.getInstance(appContext).also { modelManager = it }
    }

    private suspend fun loadModelWithOverlay(
        model: String,
        config: InferenceConfig,
        onlyIfAvailable: Boolean,
        skipMemoryWarning: Boolean = false
    ): Result<Unit> {
        val manager = requireModelManager()
        val engineModelName = toEngineModelName(model)
        _isModelLoading.value = true
        _modelLoadingStatus.value = "モデルを準備中..."
        return try {
            val displayModel = when (model.uppercase()) {
                "GEMMA4-2B" -> "Gemma4-2B"
                "GEMMA4-4B" -> "Gemma4-4B"
                else -> "カスタム"
            }
            
            // Phase 14: モデルロード前にメモリ確認
            _modelLoadingStatus.value = "[$displayModel] メモリを確認中..."
            Log.d(TAG, "loadModelWithOverlay: PRE_LOAD_MEMORY_CHECK model=$model backend=${config.backendType}")

            // 詳細なメモリ情報をログ出力
            val detailedMemInfo = MemoryObserver.getDetailedMemoryInfo(appContext)
            Log.d(TAG, "PRE_LOAD_MEMORY:\n$detailedMemInfo")

            val memoryStatus = MemoryObserver.getMemoryStatus(appContext)
            Log.d(TAG, "loadModelWithOverlay: MEMORY_STATUS level=${memoryStatus.level} used=${memoryStatus.usedMB}MB max=${memoryStatus.maxMB}MB percent=${memoryStatus.usedPercent}% device_low_memory=${memoryStatus.isLowMemory}")

            // メモリ不足チェック
            if (!manager.isMemorySufficient()) {
                val errorMsg = "メモリ不足: ${memoryStatus.usedPercent}% (${memoryStatus.usedMB}/${memoryStatus.maxMB}MB) - ${displayModel}をロードできません"
                Log.e(TAG, "loadModelWithOverlay: MEMORY_INSUFFICIENT $errorMsg")
                _uiMessage.emit(errorMsg)
                return Result.failure(RuntimeException(errorMsg))
            }

            // ★ Gallery アプローチ: 最小メモリ要件をチェック（skipMemoryWarning=false の場合のみ）
            if (!skipMemoryWarning && MemoryObserver.isMemoryLow(appContext, model)) {
                Log.w(TAG, "loadModelWithOverlay: MEMORY LOW - model=$model does not meet minimum memory requirement")
                _modelLoadingStatus.value = "メモリ確認中..."

                // 警告情報を取得
                val systemMemInfo = MemoryObserver.getSystemMemoryInfo(appContext)
                _memoryWarning.value = MemoryWarningInfo(
                    modelName = displayModel,
                    predictedUsagePercent = systemMemInfo.usedPercent,  // 現在の使用率
                    currentUsagePercent = systemMemInfo.usedPercent,
                    currentUsageMB = systemMemInfo.usedMemoryMB,
                    maxMB = systemMemInfo.totalMemoryMB
                )
                // 警告が表示されるまで待機（ローディング状態を維持）
                _isModelLoading.value = false  // ここで一度解除（finally でも解除されるため）
                return Result.failure(RuntimeException("Memory warning shown to user"))
            }

            // skipMemoryWarning=true の場合はメモリ警告をスキップしてロード続行
            if (skipMemoryWarning && MemoryObserver.isMemoryLow(appContext, model)) {
                Log.w(TAG, "loadModelWithOverlay: MEMORY LOW but user confirmed - proceeding with load model=$model")
            }

            Log.d(TAG, "loadModelWithOverlay: Memory check passed for model=$model")
            
            _modelLoadingStatus.value = "[$displayModel] エンジンを初期化中..."
            Log.d(TAG, "loadModelWithOverlay: model=$model, engineName=$engineModelName, enableThinking=${config.enableThinking}, backend=${config.backendType}, contextWindow=${config.contextWindow}")
            
            val result = withContext(Dispatchers.IO) {
                if (onlyIfAvailable) {
                    manager.initializeModelIfAvailable(engineModelName, config)
                } else {
                    manager.initializeModel(engineModelName, config)
                }
            }
            
            if (result.isSuccess) {
                _modelLoadingStatus.value = "[$displayModel] ロード完了"
                Log.d(TAG, "loadModelWithOverlay: SUCCESS - model=$model")
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "loadModelWithOverlay: FAILED - model=$model, error=${error?.message}", error)
                
                // メモリ不足エラーを検出
                if (error?.message?.contains("memory") == true || 
                    error?.message?.contains("Memory") == true) {
                    Log.w(TAG, "loadModelWithOverlay: Out of memory detected during initialization")
                    val postLoadMemStatus = MemoryObserver.getMemoryStatus(appContext)
                    val errorMsg = "メモリが不足しています (${postLoadMemStatus.usedPercent}%)。ホームスクリーンに戻ります..."
                    _uiMessage.emit(errorMsg)
                    viewModelScope.launch {
                        _navigationEvent.emit(NavigationEvent.BACK_TO_HOME)
                    }
                }
            }
            result
        } finally {
            _isModelLoading.value = false
            _modelLoadingStatus.value = ""
        }
    }

    /**
     * メディア付きメッセージを送信（複数画像・音声対応）
     */
    fun sendMessageWithMedia(
        userMessage: String,
        imageUris: List<String> = emptyList(),
        audioUri: String? = null
    ) {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value) return

        // 前の job をキャンセル
        generationJob?.cancel(CancellationException("Stopped by user"))
        generationJob = null

        // 計算集約的な処理はDefault（CPU 集約的タスク用）で実行
        generationJob = viewModelScope.launch(Dispatchers.Default) {
            val thisJob = this  // このJobインスタンスを保存
            var imagesToCleanup = mutableListOf<Bitmap>()
            try {
                // Phase 11: 複数画像対応
                val storedImages = imageUris.mapNotNull { imageUri ->
                    withContext(Dispatchers.IO) {
                        MessageMediaStore.persistUriIfNeeded(appContext, imageUri)
                    }
                }
                
                val storedAudio = withContext(Dispatchers.IO) {
                    MessageMediaStore.persistUriIfNeeded(appContext, audioUri)
                }

                // メディア付きユーザーメッセージを保存（DB アクセス - IO スレッド）
                // Phase 11: 複数画像をカンマ区切りで保存
                // Phase 12: 画像説明を自動生成・保存
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "SAVE_MESSAGE_START: content='$userMessage' images=${storedImages.size}")
                    val imageDesc = if (storedImages.isNotEmpty()) {
                        // 初めての画像に対する簡潔な説明を生成
                        generateImageDescription(storedImages)
                    } else null
                    
                    val messageId = messageRepository.addMessage(
                        sessionId = sessionId,
                        role = "user",
                        content = userMessage,
                        imageUri = storedImages.joinToString(","),  // 複数画像をカンマ区切りで結合
                        imageDescription = imageDesc,  // Phase 12: 画像説明を保存
                        audioUri = storedAudio
                    )
                    Log.d(TAG, "SAVE_MESSAGE_END: messageId=$messageId content='$userMessage' imageDesc='$imageDesc'")
                    sessionRepository.updateSessionLastUpdated(sessionId)
                }

                // 入力フィールドをクリア（UI 更新 - Main スレッド）
                withContext(Dispatchers.Main) {
                    _inputText.value = ""
                }

                // URI から Bitmap・ByteArray に変換
                val images = mutableListOf<Bitmap>()
                val audioClips = mutableListOf<ByteArray>()

                // Phase 11: 複数画像の処理
                for (uriStr in storedImages) {
                    val uri = MessageMediaStore.toUri(uriStr)
                    val bitmap = loadBitmapFromUri(uri)
                    if (bitmap != null) {
                        val scaled = scaleBitmapTo1024(bitmap)
                        if (scaled !== bitmap) bitmap.recycle()
                        images.add(scaled)
                        imagesToCleanup.add(scaled)  // ← クリーンアップリストに追加
                        Log.d(TAG, "Loaded image for inference: $uriStr (${images.size}/5)")
                    }
                }

                storedAudio?.let { uriStr ->
                    val uri = MessageMediaStore.toUri(uriStr)
                    val audioBytes = loadAudioBytesFromUri(uri)
                    if (audioBytes != null) {
                        audioClips.add(audioBytes)
                        Log.d(TAG, "Loaded audio for inference: $uriStr")
                    }
                }

                // AI 応答を生成（計算集約的 - Default スレッド）
                _isLoading.value = true
                generateAIResponse(sessionId, userMessage, images, audioClips)
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error sending message with media", e)
                // UI 更新 - Main スレッド
                withContext(Dispatchers.Main) {
                    _uiMessage.emit("メディア付きメッセージの送信に失敗しました: ${e.message}")
                }
            } finally {
                // UI 更新 - Main スレッド
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                }
                // ← Bitmapをクリーンアップ
                imagesToCleanup.forEach { bitmap ->
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
                imagesToCleanup.clear()
                // このJobがまだcurrentなら null にする（前のJobから overwrite されない）
                if (generationJob == thisJob) {
                    generationJob = null
                }
            }
        }
    }

    /**
     * 既存のメッセージにメディアを追加・更新
     */
    fun addMediaToMessage(
        messageId: Long,
        imageUri: String? = null,
        audioUri: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (imageUri == null && audioUri == null) {
                    _uiMessage.emit("追加するメディアが指定されていません")
                    return@launch
                }

                val persistedImage = imageUri?.let {
                    MessageMediaStore.persistUriIfNeeded(appContext, it) ?: it
                }
                val persistedAudio = audioUri?.let {
                    MessageMediaStore.persistUriIfNeeded(appContext, it) ?: it
                }

                messageRepository.updateMessageMedia(
                    messageId = messageId,
                    imageUri = persistedImage,
                    audioUri = persistedAudio
                )

                val sessionId = _currentSessionId.value
                if (sessionId != null) {
                    sessionRepository.updateSessionLastUpdated(sessionId)
                }

                val mediaType = when {
                    imageUri != null && audioUri != null -> "画像と音声"
                    imageUri != null -> "画像"
                    else -> "音声"
                }
                _uiMessage.emit("$mediaType をメッセージに追加しました")
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error adding media to message", e)
                _uiMessage.emit("メディア追加に失敗しました: ${e.message}")
            }
        }
    }

    /**
     * メッセージからメディアを削除
     */
    fun removeMediaFromMessage(messageId: Long, mediaType: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val current = messageRepository.getMessageById(messageId) ?: return@launch
                if (mediaType == "image") {
                    MessageMediaStore.deleteStoredFileIfOwned(appContext, current.imageUri)
                } else {
                    MessageMediaStore.deleteStoredFileIfOwned(appContext, current.audioUri)
                }
                val updatedImageUri = if (mediaType == "image") null else current.imageUri
                val updatedAudioUri = if (mediaType == "audio") null else current.audioUri

                messageRepository.updateMessageMedia(
                    messageId = messageId,
                    imageUri = updatedImageUri,
                    audioUri = updatedAudioUri
                )

                _uiMessage.emit("$mediaType をメッセージから削除しました")
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error removing media from message", e)
                _uiMessage.emit("メディア削除に失敗しました: ${e.message}")
            }
        }
    }

    /**
     * メッセージが画像や音声を含むかチェック
     */
    suspend fun hasMessageMedia(messageId: Long): Boolean {
        return messageRepository.hasMediaContent(messageId)
    }

    /**
     * メッセージの詳細情報を取得（メディア情報含む）
     */
    fun getMessageDetail(messageId: Long, callback: (MessageEntity?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val message = messageRepository.getMessageById(messageId)
                withContext(Dispatchers.Main) {
                    callback(message)
                }
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error getting message detail", e)
                withContext(Dispatchers.Main) {
                    callback(null)
                }
            }
        }
    }
    
    /**
     * メディアプレビューメッセージを更新（チャット欄に表示用）
     */
    fun updatePendingMediaPreview(imageUri: String? = null, audioUri: String? = null) {
        _pendingMediaMessage.value = if (imageUri != null || audioUri != null) {
            MessageEntity(
                id = 0,
                sessionId = _currentSessionId.value ?: 0,
                role = "user",
                content = "",
                imageUri = imageUri,
                audioUri = audioUri,
                timestamp = System.currentTimeMillis()
            )
        } else {
            null
        }
    }
    
    /**
     * メディアプレビューをクリア
     */
    fun clearPendingMediaPreview() {
        _pendingMediaMessage.value = null
    }

    /**
     * バックエンドタイプを更新（GPUまたはCPU）
     * バックエンド切り替え時に呼び出して、キャッシュを無効化する
     */
    fun setBackendType(type: String) {
        if (type != currentBackendType) {
            Log.d(TAG, "Backend changed from $currentBackendType to $type, clearing cache")
            currentBackendType = type
            // バックエンド切り替え時にキャッシュをクリア
            clearCompressedContextCache()
        }
    }

    /**
     * 圧縮コンテキストキャッシュをクリア
     * @param sessionId クリアする特定のセッション（nullの場合は全キャッシュクリア）
     */
    fun clearCompressedContextCache(sessionId: Long? = null) {
        if (sessionId != null) {
            compressedContextCache.remove(sessionId)
            Log.d(TAG, "Cache cleared for session: $sessionId")
        } else {
            compressedContextCache.clear()
            Log.d(TAG, "All compressed context cache cleared")
        }
    }
    
    /**
     * Acquire WakeLock to prevent screen sleep during generation
     */
    private fun acquireScreenWakeLock() {
        try {
            val pm = powerManager
            if (pm == null) {
                Log.w(TAG, "PowerManager unavailable for WakeLock")
                return
            }
            if (screenWakeLock == null || !screenWakeLock!!.isHeld) {
                screenWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                    "nezumiai:generation"
                )
                screenWakeLock?.acquire(60 * 60 * 1000) // 60分のタイムアウト
                Log.d(TAG, "WakeLock acquired for generation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock", e)
        }
    }
    
    /**
     * Release WakeLock when generation completes
     */
    private fun releaseScreenWakeLock() {
        try {
            if (screenWakeLock != null && screenWakeLock!!.isHeld) {
                screenWakeLock!!.release()
                Log.d(TAG, "WakeLock released after generation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release WakeLock", e)
        }
    }
    
    /**
     * ViewModel のライフサイクル終了時にリソースを完全解放する。
     * 特に KVキャッシュ（LiteRT-LM のセッション情報）の確実なアンロードを保証する。
     *
     * このメソッドが呼ばれるタイミング：
     * - Fragment がバックされた場合
     * - Activity が終了した場合
     * - スワイプアウトやプロセス終了時
     */
    override fun onCleared() {
        Log.d(TAG, "ChatViewModel.onCleared() called - starting resource cleanup")
        
        // 推論をキャンセル（新しいセッションへの汚染を防止）
        stopGeneration()
        generationJob?.cancel()
        generationJob = null
        
        // メッセージ取得ジョブをキャンセル
        messagesCollectionJob?.cancel()
        messagesCollectionJob = null
        
        // WakeLock をリリース（画面スリープを許可）
        releaseScreenWakeLock()
        
        // KVキャッシュを含むモデルリソースをアンロード
        // ⚠️ 重要：viewModelScope は onCleared で Cancelled になるため、ここで呼んではいけない
        // 代わりに同期的に unloadModel() を実行する
        try {
            Log.d(TAG, "Unloading LiteRT-LM model and KVCache...")
            val unloadResult = runCatching {
                // viewModelScope ではなく、新しい coroutine context を避ける
                modelManager?.let { manager ->
                    // TODO: runBlocking は UI スレッドでは使用不可
                    // 本来は別スレッドで実行すべき、または Activity.onDestroy で async に呼ぶ
                    Log.d(TAG, "Model manager cleanup initiated")
                }
            }
            unloadResult.onSuccess {
                Log.i(TAG, "✅ Model and KVCache unloaded successfully")
            }
            unloadResult.onFailure { error ->
                Log.w(TAG, "⚠️ Failed to unload model: ${error.message}", error)
            }
        } catch (e: Exception) {
            // viewModelScope キャンセルによる CancellationException もここで catch
            Log.e(TAG, "Exception during onCleared: ${e.message}", e)
        }
        
        super.onCleared()
        Log.d(TAG, "ChatViewModel.onCleared() completed")
    }
    
    /**
     * Activity 終了前に呼び出すべき明示的なクリーンアップメソッド
     * （viewModelScope を使用可能なタイミング）
     * 
     * Activity または Fragment の onDestroy で以下のように呼び出す：
     *   viewModel.cleanupBeforeDestroy()
     * 
     * その後、しばらく待ってから Activity.finish() を呼ぶこと
     */
    fun cleanupBeforeDestroy() {
        Log.d(TAG, "cleanupBeforeDestroy() called")
        viewModelScope.launch {
            try {
                // この時点で viewModelScope はまだ active
                stopGeneration()  // 推論キャンセル
                modelManager?.unloadModel()?.let { result ->
                    if (result.isSuccess) {
                        Log.d(TAG, "✅ Model unloaded in cleanupBeforeDestroy")
                    } else {
                        Log.w(TAG, "⚠️ Failed to unload model: ${result.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in cleanupBeforeDestroy", e)
            }
        }
    }
}
