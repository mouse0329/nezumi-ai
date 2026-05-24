package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import com.nezumi_ai.BuildConfig
import com.nezumi_ai.R
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
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.PresetRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.CpuCompatibility
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.GenerateImageToolBridge
import com.nezumi_ai.data.inference.GenerateImageToolHandler
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolExecutionResult
import com.nezumi_ai.data.inference.PromptBuilder
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.preset.PresetConstants
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.utils.PreferencesHelper
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nezumi_ai.MyApplication
import com.nezumi_ai.voicevox.VoicevoxManager
import com.nezumi_ai.voicevox.VoicevoxStreamingTts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChatViewModel(
    private val appContext: Context,
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val presetRepository: PresetRepository? = null,
    private val memoryRepository: MemoryRepository? = null
) : ViewModel() {
    
    private val voicevoxManager: VoicevoxManager by lazy {
        (appContext.applicationContext as MyApplication).getVoicevoxManager()
    }

    private val voicevoxStreamingTts: VoicevoxStreamingTts by lazy {
        VoicevoxStreamingTts(voicevoxManager)
    }
    
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
        /** メモリ注入に使えるコンテキスト予算の最大比率 */
        private const val MEMORY_BUDGET_RATIO = 0.15f
        /** 会話履歴のために最低限確保する文字数 */
        private const val HISTORY_RESERVE_CHARS = 2048
        /** "関連メモリ:\n" ヘッダー文字数 */
        private const val MEMORY_HEADER_CHARS = 10

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

    private suspend fun getActiveSelectedModel(): String {
        val preset = presetRepository?.getCurrentPreset()
        val presetModel = preset?.modelId?.let { mapPresetModelIdToSettingsModel(it) }
        return normalizeModel(presetModel ?: settingsRepository.getSelectedModel())
    }

    private suspend fun getActiveSystemPrompt(): String {
        val preset = presetRepository?.getCurrentPreset()
        return preset?.systemPrompt ?: settingsRepository.getSystemPrompt()
    }

    private suspend fun isMemoryEnabledForCurrentPreset(): Boolean {
        if (_isIncognitoMode.value) return false
        if (_isMemoryTemporarilyDisabled.value) return false
        return presetRepository?.getCurrentPreset()?.memoryEnabled ?: false
    }

    private fun mapPresetModelIdToSettingsModel(modelId: String): String? {
        return when (modelId) {
            PresetConstants.MODEL_GEMMA4_LITERT -> "Gemma4-2B"
            PresetConstants.MODEL_QWEN3_GGUF -> null
            else -> modelId.takeIf { it.isNotBlank() }
        }
    }
    
    data class EmbeddingDownloadProgress(
        val fileName: String,
        val downloaded: Long,
        val total: Long
    )

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId
    
    private val _isChatReady = MutableStateFlow(false)
    val isChatReady: StateFlow<Boolean> = _isChatReady
    
    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages
    
    private val _pendingMediaMessage = MutableStateFlow<MessageEntity?>(null)
    val pendingMediaMessage: StateFlow<MessageEntity?> = _pendingMediaMessage
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _speakingMessageId = MutableStateFlow<Long?>(null)
    val speakingMessageId: StateFlow<Long?> = _speakingMessageId.asStateFlow()

    private val _selectedModel = MutableStateFlow("Gemma4-2B")
    val selectedModel: StateFlow<String> = _selectedModel

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading

    private val _modelLoadingStatus = MutableStateFlow("")
    val modelLoadingStatus: StateFlow<String> = _modelLoadingStatus

    private val _isCompressing = MutableStateFlow(false)
    val isCompressing: StateFlow<Boolean> = _isCompressing

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _isEmbeddingDownloadInProgress = MutableStateFlow(false)
    val isEmbeddingDownloadInProgress: StateFlow<Boolean> = _isEmbeddingDownloadInProgress.asStateFlow()

    private val _embeddingDownloadProgress = MutableStateFlow<EmbeddingDownloadProgress?>(null)
    val embeddingDownloadProgress: StateFlow<EmbeddingDownloadProgress?> = _embeddingDownloadProgress.asStateFlow()

    private val _isMemoryTemporarilyDisabled = MutableStateFlow(false)
    val isMemoryTemporarilyDisabled: StateFlow<Boolean> = _isMemoryTemporarilyDisabled.asStateFlow()

    private var embeddingDownloadJob: Job? = null

    private val memoryExtractionWorker: com.nezumi_ai.data.memory.MemoryExtractionWorker? by lazy {
        val repo = memoryRepository ?: return@lazy null
        val sessionRepo = com.nezumi_ai.data.repository.MemorySessionRepository(
            com.nezumi_ai.data.database.NezumiAiDatabase.getInstance(appContext).memorySessionDao()
        )
        com.nezumi_ai.data.memory.MemoryExtractionWorker(repo, sessionRepo).also { worker ->
            // isExtracting を Worker の StateFlow に橋渡し
            viewModelScope.launch {
                worker.isExtracting.collect { _isExtracting.value = it }
            }
            // 起動時 pending 処理は generateAIResponse 初回呼び出し後に行う
        }
    }

    /** true のとき、このチャットでは設定のシンキングONでも LiteRT の enable_thinking を付けない */
    private val _chatSessionDisableThinking = MutableStateFlow(false)
    val chatSessionDisableThinking: StateFlow<Boolean> = _chatSessionDisableThinking.asStateFlow()
    private var hasUserToggledThinking = false
    private var lastThinkingSessionId: Long? = null

    private val _sessionTitle = MutableStateFlow(DEFAULT_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle

    private val _contextUsageChars = MutableStateFlow(0)
    val contextUsageChars: StateFlow<Int> = _contextUsageChars

    private val _contextWindowSize = MutableStateFlow(4096)
    val contextWindowSize: StateFlow<Int> = _contextWindowSize

    private val _contextWindowCapacityChars = MutableStateFlow(4096 * 4)
    val contextWindowCapacityChars: StateFlow<Int> = _contextWindowCapacityChars

    private val _isIncognitoMode = MutableStateFlow(false)
    val isIncognitoMode: StateFlow<Boolean> = _isIncognitoMode.asStateFlow()

    fun setIncognitoMode(enabled: Boolean) {
        _isIncognitoMode.value = enabled
        
        // シークレットモード無効時：セッションをクリア
        if (!enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val sessionId = _currentSessionId.value ?: return@launch
                    val session = sessionRepository.getSessionById(sessionId) ?: return@launch
                    
                    if (session.isIncognito) {
                        // シークレットセッションのメッセージを削除
                        messageRepository.deleteAllMessagesInSession(sessionId)
                        // セッション自体を削除
                        sessionRepository.deleteSession(sessionId)
                        Log.d(TAG, "Incognito session $sessionId and all messages deleted")
                        
                        // UI更新：メッセージをクリア
                        _messages.value = emptyList()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error clearing incognito session", e)
                }
            }
        }
    }

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage
    
    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent

    enum class NavigationEvent {
        BACK_TO_HOME,  // ホームスクリーンに戻る
        CLEAR_CHAT     // チャット画面をクリア
    }

    data class MemoryErrorInfo(
        val usedPercent: Int,
        val usedMB: Long,
        val totalMB: Long
    )

    private val _memoryError = MutableStateFlow<MemoryErrorInfo?>(null)
    val memoryError: StateFlow<MemoryErrorInfo?> = _memoryError.asStateFlow()

    fun dismissMemoryError() {
        _memoryError.value = null
    }
    
    private val _toolCallState = MutableStateFlow<ToolCallState?>(null)
    val toolCallState: StateFlow<ToolCallState?> = _toolCallState.asStateFlow()

    private val _imageGenProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val imageGenProgress: StateFlow<Pair<Int, Int>?> = _imageGenProgress.asStateFlow()

    private val _confirmationRequest = MutableStateFlow<String?>(null)
    val confirmationRequest: StateFlow<String?> = _confirmationRequest.asStateFlow()

    private var imageGenConfirmCont: CancellableContinuation<String?>? = null

    @Volatile
    private var streamingAssistantMessageIdForTools: Long? = null

    private val generateImageToolHandler = GenerateImageToolHandler { toolCall ->
        invokeGenerateImageFromTool(toolCall)
    }

    data class MemoryWarningInfo(
        val modelName: String,
        val predictedUsagePercent: Int,
        val currentUsagePercent: Int,
        val currentUsageMB: Long,
        val maxMB: Long,
        val usedMemoryMB: Long,
        val totalMemoryMB: Long,
        val usedPercent: Int,
        val lowMemoryFlag: Boolean
    )

    data class CpuCompatibilityWarningInfo(
        val modelName: String,
        val message: String
    )

    private val _memoryWarning = MutableStateFlow<MemoryWarningInfo?>(null)
    val memoryWarning: StateFlow<MemoryWarningInfo?> = _memoryWarning.asStateFlow()

    private val _cpuCompatibilityWarning = MutableStateFlow<CpuCompatibilityWarningInfo?>(null)
    val cpuCompatibilityWarning: StateFlow<CpuCompatibilityWarningInfo?> = _cpuCompatibilityWarning.asStateFlow()

    fun dismissMemoryWarning() {
        _memoryWarning.value = null
    }

    fun cancelMemoryWarningAndGoHome() {
        _memoryWarning.value = null
        viewModelScope.launch {
            _navigationEvent.emit(NavigationEvent.BACK_TO_HOME)
        }
    }

    fun proceedWithModelLoad(model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _memoryWarning.value = null
            val normalizedModel = normalizeModel(model)
            val config = chatInferenceConfigForModel(normalizedModel)
            val result = loadModelWithOverlay(
                normalizedModel,
                config,
                onlyIfAvailable = false,
                skipMemoryWarning = true
            )
            if (result.isSuccess) {
                // ロード完了後、送信待ちメッセージがあれば推論を再開
                val sessionId = _currentSessionId.value ?: return@launch
                val messages = messageRepository.getMessagesForSessionOnce(sessionId)
                val lastUser = messages.lastOrNull { it.role == "user" } ?: return@launch
                val lastAssistant = messages.lastOrNull { it.role == "assistant" }
                // 最後のユーザーメッセージに対応するアシスタント応答がなければ推論実行
                if (lastAssistant == null || lastAssistant.timestamp < lastUser.timestamp) {
                    _isLoading.value = true
                    generateAIResponse(
                        sessionId = sessionId,
                        userMessage = lastUser.content,
                        currentTurnMessageId = lastUser.id
                    )
                }
            }
        }
    }

    fun proceedWithCpuCompatibilityWarning(model: String) {
        viewModelScope.launch {
            _cpuCompatibilityWarning.value = null
            val normalizedModel = normalizeModel(model)
            val config = chatInferenceConfigForModel(normalizedModel)
            loadModelWithOverlay(
                normalizedModel,
                config,
                onlyIfAvailable = false,
                skipCpuCompatibilityWarning = true
            )
        }
    }

    fun cancelCpuCompatibilityWarning() {
        _cpuCompatibilityWarning.value = null
        viewModelScope.launch {
            _uiMessage.emit("モデルロードをキャンセルしました")
        }
    }

    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null
    private val generationControlMutex = Mutex()
    private var messagesCollectionJob: Job? = null
    private val compressedContextCache = mutableMapOf<Long, CompressedContextCache>()
    private var currentBackendType = "CPU"  // GPU時はキャッシュを無効化するためのフラグ
    private val userTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:User|ユーザー)\\s*[:：]")
    private val assistantTurnMarkerRegex = Regex("(?i)(?:^|[\\s\\n\\r])(?:Assistant|アシスタント)\\s*[:：]")
    private val roleTurnMarkerRegex =
        Regex("(?i)(?:^|[\\s\\n\\r])(?:User|Assistant|ユーザー|アシスタント)\\s*[:：]")
    /** 起動時の pending 抽出処理を1回だけ実行するフラグ */
    private var pendingExtractionProcessed = false
    
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
                _selectedModel.value = getActiveSelectedModel()
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
                        refreshContextWindowForSelectedModel()
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
                    refreshContextWindowForModel(model)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error monitoring model changes", e)
            }
        }

        GenerateImageToolBridge.handler = generateImageToolHandler
    }

    private suspend fun refreshContextWindowForSelectedModel() {
        refreshContextWindowForModel(_selectedModel.value)
    }

    private suspend fun refreshContextWindowForModel(model: String) {
        val contextWindow = settingsRepository.getContextWindowForModel(model)
        _contextWindowSize.value = contextWindow
        _contextWindowCapacityChars.value = contextWindow * TOKEN_TO_CHAR_RATIO
        Log.d(TAG, "Context window updated for model=$model: $contextWindow")
    }
    
    suspend fun setCurrentSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        if (lastThinkingSessionId != sessionId) {
            hasUserToggledThinking = false
            lastThinkingSessionId = sessionId
        }
        // チャットを開いた直後は OFF 表示（disableThinking=true）を既定にする。
        if (!hasUserToggledThinking) {
            _chatSessionDisableThinking.value = true
        }
        
        stopGenerationInternal()
        
        // ★ メーター不正確修正: セッション遷移時に圧縮コンテキストキャッシュをクリア（同期的に実行）
        clearCompressedContextCache(sessionId)
        Log.d(TAG, "setCurrentSession: Cleared compressed context cache for sessionId=$sessionId")
        
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
                    // ★ メーター不正確修正: キャッシュクリア完了後にメーター計算を実行
                    _contextUsageChars.value = estimateContextUsageChars(msgs)
                }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            _sessionTitle.value = session.name
        }
        viewModelScope.launch(Dispatchers.IO) {
            // チャット画面表示時にはモデルをロードしない。
            // 実ロードは送信時(generateAIResponse)に遅延させる。
            _selectedModel.value = getActiveSelectedModel()
            // チャット準備完了を示す
            _isChatReady.value = true
        }
    }

    private suspend fun createAndActivateSession(sessionName: String = DEFAULT_SESSION_TITLE): Long {
        val newSessionId = sessionRepository.createSession(sessionName)
        settingsRepository.saveCurrentSessionId(newSessionId)
        appContext.getSharedPreferences("nezumi_ai_prefs", Context.MODE_PRIVATE)
            .edit().putLong("current_session_id", newSessionId).apply()
        setCurrentSession(newSessionId)
        return newSessionId
    }

    private suspend fun ensureValidCurrentSession(): Long? {
        val currentSessionId = _currentSessionId.value
        if (currentSessionId != null) {
            if (sessionRepository.getSessionById(currentSessionId) != null) {
                return currentSessionId
            }
        }

        return createAndActivateSession()
    }
    
    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun setChatSessionDisableThinking(disabled: Boolean) {
        // 設定値のみ更新。モデルリロードは行わない。
        // 次のメッセージ送信時に新しい設定が自動的に適用される。
        Log.d(TAG, "setChatSessionDisableThinking: disabled=$disabled")
        hasUserToggledThinking = true
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

                // ★ 警告ダイアログ表示中はユーザー操作を待つ
                if (error?.message == "MEMORY_WARNING_SHOWN" || error?.message == "CPU_COMPAT_WARNING_SHOWN") {
                    Log.d(TAG, "Model warning shown - waiting for user action: ${error.message}")
                    return@launch
                }

                // メモリエラーを検出（実際の OOM エラー）
                if (error?.message?.contains("memory", ignoreCase = true) == true) {
                    val memStatus = MemoryObserver.getMemoryStatus(appContext)
                    _memoryError.value = MemoryErrorInfo(
                        usedPercent = memStatus.usedPercent,
                        usedMB = memStatus.usedMB,
                        totalMB = memStatus.maxMB
                    )
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

    fun preloadActivePresetModel() {
        if (_isLoading.value || _isModelLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val selectedModel = getActiveSelectedModel()
            _selectedModel.value = selectedModel
            val engineModelName = toEngineModelName(selectedModel)
            if (!ModelFileManager.isModelAvailable(appContext, engineModelName)) {
                _uiMessage.emit("プリセットのモデル($selectedModel)が未ダウンロードです")
                return@launch
            }
            val config = chatInferenceConfigForModel(selectedModel)
            val result = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = true)
            if (result.isFailure) {
                val error = result.exceptionOrNull()
                if (error?.message == "MEMORY_WARNING_SHOWN" || error?.message == "CPU_COMPAT_WARNING_SHOWN") {
                    return@launch
                }
                Log.e(TAG, "Failed to preload preset model: $selectedModel", error)
                _uiMessage.emit("プリセットモデルのロードに失敗しました")
            }
        }
    }
    
    fun sendMessage(userMessage: String) {
        if (_isLoading.value) return

        viewModelScope.launch {
            val thisJob = coroutineContext[Job] ?: return@launch

            generationControlMutex.withLock {
                generationJob?.cancel(CancellationException("Stopped by user"))
                generationJob = thisJob
            }

            try {
                // Phase 3: 抽出キューが処理中なら完了を待つ（最大 30s）
                if (_isExtracting.value) {
                    Log.d(TAG, "sendMessage: waiting for memory extraction to finish...")
                    withTimeoutOrNull(30_000L) {
                        isExtracting.first { !it }
                    }
                }

                val sessionId = ensureValidCurrentSession() ?: return@launch

                // ユーザーメッセージを保存
                val userMessageId = messageRepository.addMessage(
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
                // Note: sendMessage はテキストのみサポート。
                // 画像付きメッセージは sendMessageWithMedia を使用すること。
                generateAIResponse(sessionId, userMessage, images = emptyList(), audioClips = emptyList(), currentTurnMessageId = userMessageId)
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

    fun setSelectedModelSilently(model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedModel = normalizeModel(model)
            settingsRepository.updateModel(normalizedModel)
            _selectedModel.value = normalizedModel
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
                val selectedModel = getActiveSelectedModel()
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
                    if (errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN") {
                        Log.d(TAG, "Model warning shown during compression - waiting for user action: $errorMsg")
                        return@launch
                    }
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
        viewModelScope.launch {
            stopGenerationInternal()
        }
    }

    private suspend fun stopGenerationInternal() {
        val currentJob = generationControlMutex.withLock {
            val job = generationJob ?: return@withLock null
            generationJob = null
            _isLoading.value = false
            job
        }

        try {
            val manager = requireModelManager()
            manager.cancelInference()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel inference", e)
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
        audioClips: List<ByteArray> = emptyList(),
        currentTurnMessageId: Long? = null
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
            val selectedModel = getActiveSelectedModel()
            
            val memoryPercent = manager.getMemoryUsagePercent()
            Log.d(TAG, "generateAIResponse: memoryUsage=$memoryPercent%")
            _selectedModel.value = selectedModel
            val engineModelName = toEngineModelName(selectedModel)
            val hasMediaInput = images.isNotEmpty() || audioClips.isNotEmpty()
            if (!ModelFileManager.isModelAvailable(appContext, engineModelName)) {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    content = "選択モデル($selectedModel)が未ダウンロードです。設定画面でダウンロードしてください。"
                )
                return
            }
            val baseConfig = chatInferenceConfigForModel(selectedModel)
            val backend = settingsRepository.getBackendForModel(selectedModel)
            val config = baseConfig.copy(
                backendType = backend,
                requireMultimodal = hasMediaInput && !engineModelName.endsWith(".gguf", ignoreCase = true)
            ).normalized()
            val aiStartMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse START: model=$selectedModel, enableThinking=${config.enableThinking}, backend=${config.backendType}, requestedBackend=$backend, memoryUsage=$memoryPercent%")
            
            // Phase 11: ロード進捗ログの細分化
            val modelLoadStartMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse LOAD_START: model=$selectedModel")
            
            // ★ バグ修正: ロード済みモデルの場合はメモリ警告をスキップ
            // generateAIResponse は毎回呼ばれるが、モデルが既にロード済みなら
            // 不要な警告を避けるため skipMemoryWarning=true
            val isModelAlreadyLoaded = manager.isModelLoaded(engineModelName, config)
            val skipMemoryWarning = isModelAlreadyLoaded
            Log.d(
                TAG,
                "generateAIResponse: engineModelName=$engineModelName isModelAlreadyLoaded=$isModelAlreadyLoaded skipMemoryWarning=$skipMemoryWarning"
            )
            
            val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false, skipMemoryWarning = skipMemoryWarning)
            
            val modelLoadEndMs = System.currentTimeMillis()
            Log.d(TAG, "generateAIResponse LOAD_END: model=$selectedModel duration=${modelLoadEndMs - modelLoadStartMs}ms success=${loadResult.isSuccess}")
            
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()
                val errorMsg = error?.message ?: "Unknown error"
                Log.e(TAG, "Model loading failed for $selectedModel: $errorMsg", error)

                // ★ 警告ダイアログ表示中はユーザー操作を待つ
                if (errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN") {
                    Log.d(TAG, "Model warning shown - waiting for user action: $errorMsg")
                    return
                }

                // メモリエラーを検出（実際の OOM エラー）
                if (errorMsg.contains("memory", ignoreCase = true)) {
                    val memStatus = MemoryObserver.getMemoryStatus(appContext)
                    _memoryError.value = MemoryErrorInfo(
                        usedPercent = memStatus.usedPercent,
                        usedMB = memStatus.usedMB,
                        totalMB = memStatus.maxMB
                    )
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

            // ① 起動時 pending 抽出処理（モデルロード完了後に1回だけ実行）
            if (!pendingExtractionProcessed) {
                pendingExtractionProcessed = true
                val pendingSaveMode = settingsRepository.getMemorySaveMode()
                memoryExtractionWorker?.processPending(manager, config.copy(
                    temperature = 0.1f,
                    enableThinking = false,
                    contextCompressionEnabled = false
                ), pendingSaveMode, { fetchSessionId ->
                    messageRepository.getMessagesForSessionOnce(fetchSessionId)
                }, suppressContradictionDeletion = true)
            }
            
            val promptForModel = buildPromptWithSessionContext(
                sessionId = sessionId,
                config = config,
                manager = manager,
                engineModelName = engineModelName,
                currentTurnMessageId = currentTurnMessageId
            )

            if (engineModelName.endsWith(".gguf", ignoreCase = true)) {
                val caps = com.nezumi_ai.utils.ImportedModelCapabilityStore.get(appContext, engineModelName)
                if (images.isNotEmpty() && !caps.imageEnabled) {
                    Log.w(
                        TAG,
                        "GGUF: images sent but image capability off. count=${images.size}"
                    )
                    _uiMessage.emit("このGGUFモデルでは画像入力がオフです。モデル設定の機能設定で画像を有効にしてください。")
                    return
                }
                if (audioClips.isNotEmpty() && !caps.audioEnabled) {
                    Log.w(
                        TAG,
                        "GGUF: audio sent but audio capability off. count=${audioClips.size}"
                    )
                    _uiMessage.emit("このGGUFモデルでは音声入力がオフです。モデル設定の機能設定で音声を有効にしてください。")
                    return
                }
            }

            // GGUF マルチモーダル: JNI が mmproj 未指定時もベース GGUF から clip/mtmd を初期化する（単一ファイル統合型）

            // ストリーミング推論を実行（マルチモーダル対応）
            val aiResponseFlow = withContext(Dispatchers.IO) {
                if (hasMediaInput) {
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
            streamingAssistantMessageIdForTools = activeStreamingMessageId

            val answerBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var nativeThinkingStream = false
            var lastPersistedContent = ""
            var lastPersistedThinking: String? = null
            var lastPersistAt = 0L
            var toolResultsJson: String? = null
            var firstOutputAtMs: Long? = null
            var generationEndAtMs: Long? = null

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
                                    firstOutputAtMs = SystemClock.elapsedRealtime()
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
                            generationEndAtMs = SystemClock.elapsedRealtime()
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

            val generationTimeMs = firstOutputAtMs?.let { first ->
                val end = generationEndAtMs ?: SystemClock.elapsedRealtime()
                (end - first).coerceAtLeast(0L)
            }
            val tps = if (generationTimeMs != null && generationTimeMs > 0L) {
                val tokensAfterFirst = if (isGgufEngineModel(engineModelName)) {
                    val nativeTokens = manager.getLastGenerationTokenCount()
                    nativeTokens?.minus(1f)?.coerceAtLeast(0f)
                } else {
                    (completeResponse.length / 2f - 1f).coerceAtLeast(0f)
                }
                if (tokensAfterFirst != null && tokensAfterFirst > 0f) {
                    tokensAfterFirst * 1000f / generationTimeMs
                } else {
                    null
                }
            } else {
                null
            }

            Log.d(TAG, "Inference collection completed: hasPayload=$hasPayload, completeResponse.length=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}, generationTimeMs=$generationTimeMs, tps=$tps")

            if (hasPayload) {
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "Updating message content with final response")
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = contentToSave,
                        isStreaming = false,
                        thinkingContent = finalThinking,
                        toolResultsJson = toolResultsJson,
                        generationTps = tps,
                        generationTimeMs = generationTimeMs
                    )
                    if (contentToSave.isNotEmpty()) {
                        Log.d(TAG, "Generating session title")
                        maybeGenerateSessionTitle(sessionId, userMessage, contentToSave)
                    }
                    syncSessionTitleFromDb(sessionId)
                }
                enqueueMemoryExtraction(sessionId)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AI response saved to database: ${completeResponse.take(50)}...")
                }
            } else {
                Log.w(TAG, "No payload generated, saving default message")
                val emptyExplanation = messageForEmptyInferencePayload(hasMediaInput, engineModelName)
                withContext(Dispatchers.IO) {
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = emptyExplanation,
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
            streamingAssistantMessageIdForTools = null
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

    private suspend fun awaitImageGenerationConfirmation(initialPrompt: String): String? =
        suspendCancellableCoroutine { cont ->
            imageGenConfirmCont = cont
            _confirmationRequest.value = initialPrompt
            cont.invokeOnCancellation {
                imageGenConfirmCont = null
                _confirmationRequest.value = null
            }
        }

    fun onConfirmGenerateImage(editedPrompt: String) {
        _confirmationRequest.value = null
        val c = imageGenConfirmCont
        imageGenConfirmCont = null
        c?.resume(editedPrompt.trim())
    }

    fun onCancelGenerateImage() {
        _confirmationRequest.value = null
        val c = imageGenConfirmCont
        imageGenConfirmCont = null
        c?.resume(null)
    }

    private suspend fun reloadChatModelAfterSd(manager: ModelManager) {
        try {
            // SD解放を確実に実行
            EngineManager.releaseSdKeepNone()
            Log.d(TAG, "reloadChatModelAfterSd: SD engine released")
            
            // メモリ安定化のため少し待機
            delay(500L)
            
            val selectedModel = getActiveSelectedModel()
            val engineModelName = toEngineModelName(selectedModel)
            val baseConfig = chatInferenceConfigForModel(selectedModel)
            val backend = settingsRepository.getBackendForModel(selectedModel)
            val config = baseConfig.copy(backendType = backend).normalized()
            
            Log.d(TAG, "reloadChatModelAfterSd: Reloading LLM model=$selectedModel")
            val result = manager.initializeModel(engineModelName, config)
            if (result.isSuccess) {
                EngineManager.markLlmActive()
                Log.d(TAG, "reloadChatModelAfterSd: LLM model reloaded successfully")
            } else {
                Log.e(TAG, "reloadChatModelAfterSd: Failed to reload LLM", result.exceptionOrNull())
                throw result.exceptionOrNull() ?: IllegalStateException("Failed to reload LLM")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reloadChatModelAfterSd failed", e)
            throw e
        }
    }

    private suspend fun invokeGenerateImageFromTool(toolCall: ToolCall): ToolExecutionResult {
        val prompt = toolCall.arguments["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "missing_prompt")
            )
        }
        val neg = (
            toolCall.arguments["negativePrompt"]
                ?: toolCall.arguments["negative_prompt"]
        )?.toString()?.trim().orEmpty()
        var w = (toolCall.arguments["width"] as? Number)?.toInt() ?: 512
        var h = (toolCall.arguments["height"] as? Number)?.toInt() ?: 512
        val allowed = listOf(256, 512, 768)
        w = allowed.minByOrNull { kotlin.math.abs(it - w) } ?: 512
        h = allowed.minByOrNull { kotlin.math.abs(it - h) } ?: 512
        val steps = (toolCall.arguments["steps"] as? Number)?.toInt()?.coerceIn(1, 50) ?: PreferencesHelper.getSdSteps(appContext)
        val cfg = (toolCall.arguments["cfg"] as? Number)?.toFloat()
            ?: (toolCall.arguments["cfg_scale"] as? Number)?.toFloat()
            ?: PreferencesHelper.getSdCfg(appContext)
        val seed = (toolCall.arguments["seed"] as? Number)?.toLong() ?: -1L

        val edited = awaitImageGenerationConfirmation(prompt)
        if (edited == null) {
            return ToolExecutionResult(
                success = true,
                payload = mapOf("success" to true, "message" to "キャンセルしました")
            )
        }

        val sdPath = PreferencesHelper.getSdModelPath(appContext).trim()
        if (sdPath.isEmpty() || !File(sdPath).isDirectory) {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "sd_model_path_missing")
            )
        }

        val manager = requireModelManager()
        
        // LLMモデルを完全にアンロード
        Log.d(TAG, "invokeGenerateImageFromTool: Unloading LLM before SD")
        manager.unloadModel()
        
        // メモリ安定化のため少し待機
        delay(300L)
        
        return try {
            val localDream = com.nezumi_ai.sd.LocalDreamModule(appContext)
            val backend = PreferencesHelper.getSdBackend(appContext)
            
            Log.d(TAG, "invokeGenerateImageFromTool: Loading SD model")
            val loaded = localDream.loadModel(sdPath, backend)
            if (!loaded) {
                Log.e(TAG, "invokeGenerateImageFromTool: SD model load failed")
                return ToolExecutionResult(
                    success = false,
                    payload = mapOf("success" to false, "error" to "model_load_failed")
                )
            }
            
            Log.d(TAG, "invokeGenerateImageFromTool: Generating image")
            val bmp = localDream.generateImage(
                prompt = edited,
                negativePrompt = neg,
                width = w,
                height = h,
                steps = steps,
                cfg = cfg,
                seed = seed,
                onProgress = { step, totalSteps, _ ->
                    _imageGenProgress.value = Pair(step, totalSteps)
                }
            )
            _imageGenProgress.value = null
            
            Log.d(TAG, "invokeGenerateImageFromTool: Cleaning up SD")
            localDream.cleanup()
            
            // SD完全解放を確実に実行
            EngineManager.releaseSdKeepNone()
            delay(500L)  // メモリ安定化待機
            
            if (bmp == null) {
                Log.w(TAG, "invokeGenerateImageFromTool: Image generation returned null")
                ToolExecutionResult(
                    success = true,
                    payload = mapOf("success" to false, "message" to "生成失敗")
                )
            } else {
                val msgId = streamingAssistantMessageIdForTools
                if (msgId != null) {
                    val uri = MessageMediaStore.savePngBitmap(appContext, bmp, "chat_sd_$msgId")
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            messageRepository.updateMessageImageWithDescription(msgId, uri, null)
                        }
                    }
                }
                Log.d(TAG, "invokeGenerateImageFromTool: Image generated successfully")
                ToolExecutionResult(
                    success = true,
                    payload = mapOf(
                        "success" to true,
                        "message" to "画像を生成しました",
                        "prompt" to edited
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "invokeGenerateImageFromTool: Exception during SD generation", e)
            // エラー時もSD解放を試みる
            try {
                EngineManager.releaseSdKeepNone()
            } catch (cleanupError: Exception) {
                Log.e(TAG, "invokeGenerateImageFromTool: Cleanup failed", cleanupError)
            }
            ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to (e.message ?: "sd_error"))
            )
        } finally {
            // LLMモデルを再ロード
            try {
                Log.d(TAG, "invokeGenerateImageFromTool: Reloading LLM in finally")
                reloadChatModelAfterSd(manager)
            } catch (reloadError: Exception) {
                Log.e(TAG, "invokeGenerateImageFromTool: LLM reload failed in finally", reloadError)
                // UI通知
                withContext(Dispatchers.Main) {
                    _uiMessage.emit("⚠️ LLMモデルの再ロードに失敗しました。チャットを再起動してください。")
                }
            }
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
            trimmed.equals("Gemma3n-4B", ignoreCase = true) -> "E4B"
            trimmed.equals("Gemma3n-2B", ignoreCase = true) -> "E2B"
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
                normalized.endsWith(".gguf")) && File(normalized).isAbsolute -> normalized
            else -> "gemma4-2b"  // デフォルト
        }
    }

    private fun isGgufEngineModel(engineModelName: String): Boolean {
        return engineModelName.lowercase().endsWith(".gguf")
    }

    private fun getEngineModelSizeBytes(engineModelName: String): Long? {
        val lowerName = engineModelName.lowercase()
        if (File(engineModelName).exists()) {
            return File(engineModelName).length()
        }
        return when (lowerName) {
            "gemma4-4b", "gemma-4-4b.litertlm" -> 4_800_000_000L
            "gemma4-2b", "gemma-4-2b.litertlm" -> 2_400_000_000L
            "gemma-3n-4b", "gemma-3n-4b.task" -> 4_000_000_000L
            "gemma-3n-2b", "gemma-3n-2b.task" -> 2_000_000_000L
            else -> null
        }
    }

    /**
     * 推論結果が空のときに保存する説明文。GGUF＋メディアでは mmproj 未設定／不整合を区別する。
     */
    private fun messageForEmptyInferencePayload(hasMediaInput: Boolean, engineModelName: String): String {
        if (!hasMediaInput || !isGgufEngineModel(engineModelName)) {
            return appContext.getString(R.string.assistant_error_generic_empty)
        }
        return appContext.getString(R.string.assistant_error_gguf_media_empty_with_mmproj)
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

    /**
     * @param isCurrentTurn when true (= current-turn user message), GGUF embeds <image> token in prompt.
     *   Past turns use imageDescription as fallback to avoid mismatch between <image> token count and Bitmap list.
     *   LiteRt passes images via Content.ImageBytes, so no <image> token in prompt string.
     */
    private fun sanitizeMessageContentForPrompt(
        msg: MessageEntity,
        isGgufEngine: Boolean = false,
        isCurrentTurn: Boolean = false
    ): String {
        val normalized = msg.content.trim()
        if (msg.role == "assistant") {
            if (normalized.isEmpty()) return ""
            val visibleOnly = Gemma4ThinkingParser.answerOnlyForModelContext(normalized)
            if (visibleOnly.isEmpty()) return ""
            return stripSyntheticRoleLoopTail(visibleOnly)
                .replace(Regex("^(?i)(?:Assistant|アシスタント)\\s*[:：]\\s*"), "")
                .trim()
        } else {
            val imageCount = msg.imageUri
                ?.split(",")
                ?.map { it.trim() }
                ?.count { it.isNotEmpty() }
                ?: 0
            val imageTokens: String = when {
                imageCount <= 0 -> ""
                // GGUF + current turn: embed <image> token (1:1 match with completeWithMedia)
                isGgufEngine && isCurrentTurn ->
                    List(imageCount) { "<image>" }.joinToString(separator = "\n")
                // GGUF past turns or all LiteRt turns: use imageDescription as fallback
                else ->
                    msg.imageDescription?.takeIf { it.isNotBlank() }
                        ?: "(image x$imageCount)"
            }
            return when {
                imageTokens.isNotEmpty() && normalized.isNotEmpty() -> "$imageTokens\n$normalized"
                imageTokens.isNotEmpty() -> imageTokens
                else -> normalized
            }
        }
    }

    /** Lambda adapter for sanitizeMessageContentForPrompt (engine type + current-turn pinned version) */
    private fun makeSanitizer(
        isGgufEngine: Boolean,
        currentTurnMessageId: Long?
    ): (MessageEntity) -> String = { msg ->
        sanitizeMessageContentForPrompt(
            msg,
            isGgufEngine = isGgufEngine,
            isCurrentTurn = (msg.id == currentTurnMessageId && msg.role == "user")
        )
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
        manager: ModelManager,
        engineModelName: String,
        currentTurnMessageId: Long? = null
    ): String {
        val messages = messageRepository.getMessagesForSessionOnce(sessionId)
        if (messages.isEmpty()) return ""

        // 画像をコンテキストに含むための デバッグログ
        val messagesWithImagesForLog = messages.filter { it.imageUri != null && it.imageUri.isNotEmpty() }
        if (messagesWithImagesForLog.isNotEmpty()) {
            Log.d(TAG, "PROMPT_BUILD: Found ${messagesWithImagesForLog.size} messages with images: ${messagesWithImagesForLog.map { "${it.id}:${it.role}" }}")
        }

        val isGgufEngine = isGgufEngineModel(engineModelName)
        val memoryBlock = buildRelevantMemoryBlock(messages, sessionId, config.contextWindow)
        val fullPrompt = buildPromptFromMessages(
            messages = messages,
            isGgufEngine = isGgufEngine,
            engineModelName = engineModelName,
            enableThinking = config.enableThinking,
            currentTurnMessageId = currentTurnMessageId,
            memoryBlock = memoryBlock
        )
        
        // Phase 12: thinkingContent が誤ってプロンプトに混入していないか検証
        val messagesWithThinking = messages.filter { it.thinkingContent != null && it.thinkingContent.isNotEmpty() }
        if (messagesWithThinking.isNotEmpty()) {
            Log.d(TAG, "PROMPT_BUILD: Messages with thinkingContent found (count=${messagesWithThinking.size}), but they are excluded from prompt as designed")
        }
        
        // Phase 16: GPU時のコンテキスト圧縮を無効化（メモリ競合防止）
        // GPU推論中に別の圧縮推論を走らせるとメモリ OOM リスクが高い
        val effectiveCompressionEnabled = config.contextCompressionEnabled && config.backendType != "GPU"
        
        if (!effectiveCompressionEnabled) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val validMessages = messages.filterNot { shouldExcludeFromModelContext(it) }
        val recentMessageCount = recentMessageCountForWindow(config.contextWindow)
        // 件数が少なくても長文であれば圧縮を発火できるようにする。
        // ただし直近メッセージを最低2件は保持し、残りを圧縮対象にする。
        // また、画像を含むメッセージは圧縮対象から除外してコンテキストに残す
        val messagesWithImages = validMessages.filter { it.imageUri != null && it.imageUri.isNotEmpty() }
        val minKeepCount = 2 + messagesWithImages.size  // 画像付きメッセージを除外
        val keepRecentCount = when {
            validMessages.size <= 2 -> validMessages.size
            validMessages.size <= recentMessageCount -> validMessages.size - 1
            else -> maxOf(recentMessageCount, minKeepCount)  // 画像付きメッセージは必ず保持
        }
        if (keepRecentCount <= 0) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        // 圧縮対象のメッセージから画像付きメッセージを除外する
        val allNonCompressibleMessages = validMessages.filterNot { it.imageUri != null && it.imageUri.isNotEmpty() }
        val cutoffIndex = maxOf(0, allNonCompressibleMessages.size - (recentMessageCount - messagesWithImages.size))
        
        val olderMessages = allNonCompressibleMessages.take(cutoffIndex)
        val recentMessages = validMessages.takeLast(keepRecentCount)
        val signature = olderMessages.fold(17) { acc, msg ->
            ((acc * 31) + msg.role.hashCode()) * 31 + msg.content.hashCode()
        }
        
        // GPU時はキャッシュを使用せず常に再計算（メモリ安定性優先）
        val useCache = config.backendType != "GPU"
        val cached = if (useCache) compressedContextCache[sessionId] else null

        // 手動圧縮済みキャッシュがあれば、閾値より先に優先適用する。
        if (cached != null && cached.signature == signature) {
            val prompt = buildPromptWithCompressedSummary(
                isGgufEngine = isGgufEngine,
                engineModelName = engineModelName,
                recentMessages = recentMessages,
                compressedSummary = cached.summary,
                enableThinking = config.enableThinking,
                memoryBlock = memoryBlock
            )
            return trimPromptToWindow(prompt, config.contextWindow)
        }

        // Phase 14: contextWindow はトークン数。閾値計算も「トークン数 × パーセント」
        val thresholdChars =
            ((config.contextWindow * config.contextCompressionThresholdPercent) / 100).coerceAtLeast(1)
        if (fullPrompt.length < thresholdChars) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }
        
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

        // 圧縮コンテキストの使用をログに記録
        Log.d(TAG, "Using compressed context for inference. Older messages (${olderMessages.size}) summarized, recent messages (${recentMessages.size}) included. Signature=$signature")

        val prompt = buildPromptWithCompressedSummary(
            isGgufEngine = isGgufEngine,
            engineModelName = engineModelName,
            recentMessages = recentMessages,
            compressedSummary = compressedSummary,
            enableThinking = config.enableThinking,
            memoryBlock = memoryBlock
        )

        return trimPromptToWindow(prompt, config.contextWindow)
    }

    private suspend fun buildRelevantMemoryBlock(
        messages: List<MessageEntity>,
        sessionId: Long,
        contextWindowTokens: Int = 4096
    ): String? {
        val repo = memoryRepository ?: run {
            Log.d(TAG, "MEMORY_INJECT: memoryRepository is null")
            return null
        }
        if (!isMemoryEnabledForCurrentPreset()) {
            Log.d(TAG, "MEMORY_INJECT: memory disabled for current preset")
            return null
        }
        val query = buildMemorySearchQuery(messages)
        if (query.isBlank()) {
            Log.d(TAG, "MEMORY_INJECT: query is blank")
            return null
        }
        Log.d(TAG, "MEMORY_INJECT: query='$query'")

        // ONNX モデルがあれば初期化（初回のみ実行、以降はキャッシュ）
        if (!MemoryTextEmbedder.hasEmbeddingFiles(appContext)) {
            Log.d(TAG, "MEMORY_INJECT: embedding files not found, downloading...")
            val ready = ensureEmbeddingFilesAvailable()
            if (!ready) {
                Log.d(TAG, "MEMORY_INJECT: failed to download embedding files")
                return null
            }
            Log.d(TAG, "MEMORY_INJECT: embedding files downloaded successfully")
        }
        MemoryTextEmbedder.initialize(appContext)
        Log.d(TAG, "MEMORY_INJECT: MemoryTextEmbedder initialized")

        val results = repo.search(
            queryEmbedding = MemoryTextEmbedder.embed(query),
            topK = 8,
            threshold = 0.0f,
            minSimilarity = 0.18f,
            markAccessed = true
        )
        Log.d(TAG, "MEMORY_INJECT: search returned ${results.size} results")
        if (results.isEmpty()) {
            Log.d(TAG, "MEMORY_INJECT: no search results")
            return null
        }

        // ③ トークン予算管理：メモリに使えるのはコンテキスト全体の最大15%
        // 優先度: システムプロンプト(削らない) > 関連メモリ(スコア低い順に削る) > 会話履歴
        val systemPromptChars = getActiveSystemPrompt().length
        val totalBudgetChars = contextWindowTokens * TOKEN_TO_CHAR_RATIO
        
        // メモリの最小予算を保証（500 chars ≈ 125 tokens）
        val MIN_MEMORY_BUDGET_CHARS = 500
        
        // 予算計算：全体の15% vs 残り領域
        // システムプロンプトと会話履歴のために領域を予約
        val memoryBudgetCharsBeforeCoerce = (totalBudgetChars * MEMORY_BUDGET_RATIO).toInt()
        val availableCharsAfterReserves = totalBudgetChars - systemPromptChars - HISTORY_RESERVE_CHARS
        
        val memoryBudgetChars = if (availableCharsAfterReserves >= MIN_MEMORY_BUDGET_CHARS) {
            // 十分な予算がある場合は、15% と残り領域の小さい方
            minOf(memoryBudgetCharsBeforeCoerce, availableCharsAfterReserves)
        } else if (availableCharsAfterReserves > 0) {
            // わずかな予算しかない場合でも、最小限のメモリ領域を確保
            minOf(MIN_MEMORY_BUDGET_CHARS, availableCharsAfterReserves).coerceAtLeast(100)
        } else {
            // 予備領域を調整してメモリ予算を確保
            val adjustedReserve = HISTORY_RESERVE_CHARS / 2  // 会話履歴予約を半減
            val remainingBudget = totalBudgetChars - systemPromptChars - adjustedReserve
            minOf(MIN_MEMORY_BUDGET_CHARS, remainingBudget).coerceAtLeast(0)
        }

        Log.d(TAG, "MEMORY_INJECT: contextWindow=$contextWindowTokens tokens -> totalBudget=${totalBudgetChars}chars")
        Log.d(TAG, "MEMORY_INJECT: systemPrompt=$systemPromptChars chars, historyReserve=$HISTORY_RESERVE_CHARS chars")
        Log.d(TAG, "MEMORY_INJECT: budget calculation: 15%=$memoryBudgetCharsBeforeCoerce vs available=$availableCharsAfterReserves -> final=$memoryBudgetChars chars")

        if (memoryBudgetChars == 0) {
            Log.d(TAG, "MEMORY_INJECT: budget=0 (systemPrompt=$systemPromptChars > available space), skipping injection")
            return null
        }

        // スコア高い順に予算内に収まるだけ詰める
        val trimmedResults = mutableListOf<MemoryRepository.ScoredMemory>()
        var usedChars = MEMORY_HEADER_CHARS  // "関連メモリ:\n" のオーバーヘッド
        for (result in results) {
            val entryChars = result.memory.content.length + 5  // "N. \n" のオーバーヘッド
            if (usedChars + entryChars > memoryBudgetChars) break
            trimmedResults.add(result)
            usedChars += entryChars
        }

        if (trimmedResults.isEmpty()) {
            Log.d(TAG, "MEMORY_INJECT: all results filtered out by budget constraints")
            return null
        }

        Log.d(TAG, "MEMORY_INJECT: session=$sessionId query='$query' count=${trimmedResults.size}/${results.size} budgetChars=$memoryBudgetChars usedChars=$usedChars")
        return buildString {
            append("関連メモリ:\n")
            trimmedResults.forEachIndexed { index, scored ->
                append(index + 1)
                append(". ")
                append(scored.memory.content.trim())
                append('\n')
            }
        }.trim()
    }

    /**
     * Phase 3: LLM ベースのメモリ抽出をキューに積む
     * - MemoryExtractionWorker の直列キュー（limitedParallelism(1)）に委譲
     * - 抽出完了後に _isExtracting が false に戻る
     */
    private fun enqueueMemoryExtraction(sessionId: Long) {
        val worker = memoryExtractionWorker ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (!isMemoryEnabledForCurrentPreset()) return@launch
            val messages = messageRepository.getMessagesForSessionOnce(sessionId)
            if (messages.isEmpty()) return@launch
            val manager = try { requireModelManager() } catch (e: Exception) {
                Log.w(TAG, "MEMORY_EXTRACT: no model manager available, skipping", e)
                return@launch
            }
            val selectedModel = getActiveSelectedModel()
            val config = chatInferenceConfigForModel(selectedModel).copy(
                temperature = 0.1f,
                enableThinking = false,
                contextCompressionEnabled = false
            ).normalized()
            val saveMode = settingsRepository.getMemorySaveMode()
            worker.enqueue(sessionId, messages, manager, config, saveMode)
        }
    }

    private suspend fun ensureEmbeddingFilesAvailable(): Boolean {
        if (MemoryTextEmbedder.hasEmbeddingFiles(appContext)) return true
        if (_isMemoryTemporarilyDisabled.value) return false

        _isEmbeddingDownloadInProgress.value = true
        _embeddingDownloadProgress.value = null
        try {
            val result = withContext(Dispatchers.IO) {
                embeddingDownloadJob = coroutineContext[Job]
                MemoryTextEmbedder.ensureEmbeddingFilesDownloaded(appContext) { file, downloaded, total ->
                    _embeddingDownloadProgress.value = EmbeddingDownloadProgress(file, downloaded, total)
                }
            }
            if (!result && !_isMemoryTemporarilyDisabled.value) {
                viewModelScope.launch {
                    _uiMessage.emit("埋め込みファイルのダウンロードに失敗しました。ネットワークを確認してください。")
                }
            }
            return result
        } finally {
            _isEmbeddingDownloadInProgress.value = false
            _embeddingDownloadProgress.value = null
            embeddingDownloadJob = null
        }
    }

    fun cancelEmbeddingDownload() {
        if (!_isEmbeddingDownloadInProgress.value) return
        _isMemoryTemporarilyDisabled.value = true
        embeddingDownloadJob?.cancel(CancellationException("Embedding download canceled by user"))
        viewModelScope.launch {
            _uiMessage.emit("埋め込みダウンロードがキャンセルされたため、メモリ機能を一時的に無効化しました。")
        }
    }

    private fun appendMemoryBlockToSystemPrompt(systemPrompt: String, memoryBlock: String?): String {
        if (memoryBlock.isNullOrBlank()) {
            Log.d(TAG, "appendMemoryBlockToSystemPrompt: memoryBlock is null/blank")
            return systemPrompt
        }
        Log.d(TAG, "appendMemoryBlockToSystemPrompt: appending ${memoryBlock.length} chars of memory to system prompt")
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
                append("\n\n")
            }
            append(memoryBlock)
        }
    }

    private fun buildMemorySearchQuery(messages: List<MessageEntity>): String {
        return messages.takeLast(4)
            .mapNotNull { msg ->
                val text = msg.content.trim().takeIf { it.isNotBlank() }
                when {
                    text.isNullOrBlank() -> null
                    msg.role == "assistant" -> "AI: $text"
                    msg.role == "user" -> "ユーザー: $text"
                    else -> null
                }
            }
            .joinToString(separator = "\n")
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
        // ★ バグ修正: メーター計算を実際の推論ロジック（buildPromptWithSessionContext）と統一
        // Phase 14: プロンプトの現在の文字数を推定（実際の制限は config.contextWindow（トークン数）に依存）
        val selectedModel = getActiveSelectedModel()
        val engineModelName = toEngineModelName(selectedModel)
        val isGgufEngine = isGgufEngineModel(engineModelName)
        val config = settingsRepository.getInferenceConfigForModel(selectedModel)
        val basePrompt = buildPromptFromMessages(messages, isGgufEngine, engineModelName, config.enableThinking)
        
        // ★ 常に trimPromptToWindow で実際に使用される文字数を計算
        val maxChars = config.contextWindow * TOKEN_TO_CHAR_RATIO
        val basePromptSize = trimPromptToWindow(basePrompt, config.contextWindow).length
        
        // コンテキスト圧縮が無効な場合、またはGPU使用時は未圧縮のサイズをそのまま返す
        if (!config.contextCompressionEnabled || config.backendType == "GPU") {
            return basePromptSize
        }
        
        val sessionId = _currentSessionId.value
        if (sessionId == null) {
            return basePromptSize
        }

        // ★ buildPromptWithSessionContext と同じロジックで圧縮判定
        val validMessages = messages.filterNot { shouldExcludeFromModelContext(it) }
        val recentMessageCount = recentMessageCountForWindow(config.contextWindow)
        val keepRecentCount = when {
            validMessages.size <= 2 -> validMessages.size
            validMessages.size <= recentMessageCount -> validMessages.size - 1
            else -> recentMessageCount
        }
        if (keepRecentCount <= 0) {
            return basePromptSize
        }
        
        val olderMessages = validMessages.dropLast(keepRecentCount)
        if (olderMessages.isEmpty()) {
            return basePromptSize
        }
        
        val recentMessages = validMessages.takeLast(keepRecentCount)
        val signature = olderMessages.fold(17) { acc, msg ->
            ((acc * 31) + msg.role.hashCode()) * 31 + msg.content.hashCode()
        }
        
        // ★ キャッシュヒット時のみ圧縮サイズを計算
        val cached = compressedContextCache[sessionId]
        if (cached != null && cached.signature == signature) {
            val compressedPrompt = buildPromptWithCompressedSummary(
                isGgufEngine = isGgufEngine,
                engineModelName = engineModelName,
                recentMessages = recentMessages,
                compressedSummary = cached.summary,
                enableThinking = config.enableThinking
            )
            val compressedSize = trimPromptToWindow(compressedPrompt, config.contextWindow).length
            Log.d(TAG, "CONTEXT_METER: Using cached compression | original=${basePromptSize}ch -> compressed=${compressedSize}ch")
            return compressedSize
        }
        
        // ★ キャッシュヒット不成功：未圧縮サイズを返す（推論時に圧縮判定され圧縮される可能性あり）
        // この場合、次の推論で圧縮キャッシュが生成されてメーター精度が向上する
        Log.d(TAG, "CONTEXT_METER: No cached compression yet | showing uncompressed=${basePromptSize}ch (may be compressed during inference)")
        return basePromptSize
    }

    private suspend fun buildPromptWithCompressedSummary(
        isGgufEngine: Boolean,
        engineModelName: String,
        recentMessages: List<MessageEntity>,
        compressedSummary: String,
        enableThinking: Boolean = false,
        memoryBlock: String? = null
    ): String {
        Log.d(TAG, "buildPromptWithCompressedSummary: memoryBlock=${if (memoryBlock != null) "present (${memoryBlock.length} chars)" else "null"}")
        var systemPrompt = getActiveSystemPrompt()
        val userName = settingsRepository.getUserName()
        if (userName.isNotEmpty()) {
            systemPrompt = "ユーザー名：$userName\n\n$systemPrompt"
        }
        systemPrompt = appendMemoryBlockToSystemPrompt(systemPrompt, memoryBlock)
        return if (isGgufEngine) {
            PromptBuilder.buildForGguf(
                messages = recentMessages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                format = PromptBuilder.detectGgufFormat(engineModelName),
                enableThinking = enableThinking,
                sanitizeMessageContent = ::sanitizeMessageContentForPrompt
            )
        } else {
            PromptBuilder.buildForLiteRt(
                messages = recentMessages,
                systemPrompt = systemPrompt,
                injectGemmaThinkTrigger = enableThinking && settingsRepository.shouldInjectGemmaThinkTrigger(),
                compressedSummary = compressedSummary,
                sanitizeMessageContent = ::sanitizeMessageContentForPrompt
            )
        }
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
            t.contains("応答を生成できませんでした", ignoreCase = true) ||
            t.contains("マルチモーダル推論を行うには「mmproj」", ignoreCase = true) ||
            t.contains("指定した mmproj がこのベース GGUF", ignoreCase = true) ||
            t.contains("本文が得られませんでした", ignoreCase = true) ||
            t.contains("ビジョンを初期化", ignoreCase = true)
    }

    private fun shouldExcludeFromModelContext(msg: MessageEntity): Boolean {
        if (msg.role != "assistant") return false
        if (msg.isStreaming) return true
        return isAssistantErrorLikeMessage(msg.content)
    }

    private suspend fun buildPromptFromMessages(
        messages: List<MessageEntity>,
        isGgufEngine: Boolean,
        engineModelName: String = "",
        enableThinking: Boolean = false,
        currentTurnMessageId: Long? = null,
        memoryBlock: String? = null
    ): String {
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
        
        Log.d(TAG, "PROMPT_BUILD: memoryBlock=${if (memoryBlock != null) "present (${memoryBlock.length} chars)" else "null"}")
        
        val filteredMessages = messages.filterNot { shouldExcludeFromModelContext(it) }
        val systemPrompt = appendMemoryBlockToSystemPrompt(getActiveSystemPrompt(), memoryBlock)

        val sanitizer = makeSanitizer(isGgufEngine, currentTurnMessageId)

        return if (isGgufEngine) {
            PromptBuilder.buildForGguf(
                messages = filteredMessages,
                systemPrompt = systemPrompt,
                format = PromptBuilder.detectGgufFormat(engineModelName),
                enableThinking = enableThinking,
                sanitizeMessageContent = sanitizer
            )
        } else {
            PromptBuilder.buildForLiteRt(
                messages = filteredMessages,
                systemPrompt = systemPrompt,
                injectGemmaThinkTrigger = enableThinking && settingsRepository.shouldInjectGemmaThinkTrigger(),
                sanitizeMessageContent = sanitizer
            )
        }
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
        skipMemoryWarning: Boolean = false,
        skipCpuCompatibilityWarning: Boolean = false
    ): Result<Unit> {
        val manager = requireModelManager()
        val engineModelName = toEngineModelName(model)
        val isModelAlreadyLoaded = manager.isModelLoaded(engineModelName, config)
        val isSameModelLoaded = manager.isSameModelLoaded(engineModelName)
        val effectiveSkipMemoryWarning = skipMemoryWarning || isModelAlreadyLoaded
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
            Log.d(
                TAG,
                "loadModelWithOverlay: PRE_LOAD_MEMORY_CHECK model=$model backend=${config.backendType} alreadyLoaded=$isModelAlreadyLoaded sameModelLoaded=$isSameModelLoaded skipMemoryWarning=$skipMemoryWarning effectiveSkip=$effectiveSkipMemoryWarning"
            )

            if (!skipCpuCompatibilityWarning && !isModelAlreadyLoaded && isGgufEngineModel(engineModelName)) {
                CpuCompatibility.armV82aWarningOrNull()?.let { warning ->
                    Log.w(TAG, "loadModelWithOverlay: ${warning.logMessage}")
                    _cpuCompatibilityWarning.value = CpuCompatibilityWarningInfo(
                        modelName = displayModel,
                        message = warning.userMessage
                    )
                    _isModelLoading.value = false
                    return Result.failure(RuntimeException("CPU_COMPAT_WARNING_SHOWN"))
                }
            }

            // 詳細なメモリ情報をログ出力
            val detailedMemInfo = MemoryObserver.getDetailedMemoryInfo(appContext)
            Log.d(TAG, "PRE_LOAD_MEMORY:\n$detailedMemInfo")

            val memoryStatus = MemoryObserver.getMemoryStatus(appContext)
            Log.d(TAG, "loadModelWithOverlay: MEMORY_STATUS level=${memoryStatus.level} used=${memoryStatus.usedMB}MB max=${memoryStatus.maxMB}MB percent=${memoryStatus.usedPercent}% device_low_memory=${memoryStatus.isLowMemory}")

            val shouldShowMemoryWarning = !effectiveSkipMemoryWarning

            if (shouldShowMemoryWarning) {
                val thresholdPercent = settingsRepository.getPreloadMemoryWarningThresholdPercent()

                // 既にモデルがロード済みで、現在の設定とは異なる場合は、
                // メモリ警告前に現在のモデルをアンロードしてメモリを解放する
                if (!isModelAlreadyLoaded && manager.getCurrentModelName() != null) {
                    Log.d(TAG, "loadModelWithOverlay: unloading current model before memory warning check for model=$model")
                    val unloadResult = manager.unloadModel()
                    unloadResult.onFailure { Log.w(TAG, "loadModelWithOverlay: pre-warning unload failed", it) }
                }
                
                // 0%に設定されている場合は警告をスキップ（警告無効化）
                if (thresholdPercent <= 0) {
                    Log.d(TAG, "loadModelWithOverlay: Memory warning threshold is 0%, skipping check")
                    // 警告を表示しない
                } else {
                    // ★ 新機能: モデルファイルサイズからメモリ不足を検知
                    // 注意: isMemoryLow()（モデル名ベース）は空きメモリ基準で不正確なため使用しない
                    val isMemoryLowByFileSize = if (File(engineModelName).exists()) {
                        val fileSize = File(engineModelName).length()
                        MemoryObserver.isMemoryLowForFileSize(appContext, fileSize, thresholdPercent)
                    } else {
                        val knownSize = getEngineModelSizeBytes(engineModelName)
                        if (knownSize != null) {
                            MemoryObserver.isMemoryLowForFileSize(appContext, knownSize, thresholdPercent)
                        } else {
                            Log.w(TAG, "loadModelWithOverlay: unknown model size for engineModelName=$engineModelName, skipping memory size warning")
                            false
                        }
                    }
                    
                    if (isMemoryLowByFileSize) {
                        Log.w(TAG, "loadModelWithOverlay: MEMORY LOW - model=$model byFileSize=$isMemoryLowByFileSize")
                        _modelLoadingStatus.value = "メモリ確認中..."

                        // 警告情報を取得
                        val systemMemInfo = MemoryObserver.getSystemMemoryInfo(appContext)
                        
                        // ★ バグ修正: 既に警告が表示されている場合はスキップ
                        if (_memoryWarning.value == null) {
                            _memoryWarning.value = MemoryWarningInfo(
                                modelName = displayModel,
                                predictedUsagePercent = systemMemInfo.usedPercent,
                                currentUsagePercent = systemMemInfo.usedPercent,
                                currentUsageMB = systemMemInfo.usedMemoryMB,
                                maxMB = systemMemInfo.totalMemoryMB,
                                usedMemoryMB = systemMemInfo.usedMemoryMB,
                                totalMemoryMB = systemMemInfo.totalMemoryMB,
                                usedPercent = systemMemInfo.usedPercent,
                                lowMemoryFlag = systemMemInfo.lowMemoryFlag
                            )
                            // 警告が表示されるまで待機（ローディング状態を維持）
                            _isModelLoading.value = false
                            return Result.failure(RuntimeException("MEMORY_WARNING_SHOWN"))
                        }
                    }
                }
            }

            // effectiveSkipMemoryWarning=true の場合はメモリ警告をスキップしてロード続行
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
                    val errorMsg = "メモリが不足しています (${postLoadMemStatus.usedPercent}%)"
                    _uiMessage.emit(errorMsg)

                    // skipMemoryWarning=false（最初の警告チェック時）はモーダルを表示
                    // skipMemoryWarning=true（ユーザーが続行選択済み）はチャットに留まる
                    if (!skipMemoryWarning) {
                        val memStatus = MemoryObserver.getMemoryStatus(appContext)
                        _memoryError.value = MemoryErrorInfo(
                            usedPercent = memStatus.usedPercent,
                            usedMB = memStatus.usedMB,
                            totalMB = memStatus.maxMB
                        )
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
        if (_isLoading.value) return

        // 計算集約的な処理はDefault（CPU 集約的タスク用）で実行
        viewModelScope.launch(Dispatchers.Default) {
            val thisJob = coroutineContext[Job]  // このJobインスタンスを保存
            generationControlMutex.withLock {
                generationJob?.cancel(CancellationException("Stopped by user"))
                generationJob = thisJob
            }
            val sessionId = ensureValidCurrentSession() ?: return@launch
            var imagesToCleanup = mutableListOf<Bitmap>()
            try {
                // ★ 最初に立てる（二重送信防止＆UI競合防止）
                withContext(Dispatchers.Main) {
                    _isLoading.value = true
                }

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
                val userMessageId = withContext(Dispatchers.IO) {
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
                    messageId
                }

                // ★ DB保存後にpendingをクリア（messagesフローが更新済みのタイミング）
                withContext(Dispatchers.Main) {
                    _inputText.value = ""
                    clearPendingMediaPreview()
                }

                // URI から Bitmap・ByteArray に変換
                val images = mutableListOf<Bitmap>()
                val audioClips = mutableListOf<ByteArray>()

                // Phase 11: 複数画像の処理
                for (uriStr in storedImages) {
                    val uri = MessageMediaStore.toUri(uriStr) ?: continue
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
                    if (uri != null) {
                        val audioBytes = loadAudioBytesFromUri(uri)
                        if (audioBytes != null) {
                            audioClips.add(audioBytes)
                            Log.d(TAG, "Loaded audio for inference: $uriStr")
                        }
                    }
                }

                // AI 応答を生成（計算集約的 - Default スレッド）
                generateAIResponse(sessionId, userMessage, images, audioClips, currentTurnMessageId = userMessageId)
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

    fun synthesizeText(messageId: Long, text: String) {
        if (_speakingMessageId.value != null) return
        _speakingMessageId.value = messageId
        voicevoxStreamingTts.stop()

        val job = voicevoxStreamingTts.speakStreaming(
            scope = viewModelScope,
            text = text,
            onChunkStart = { chunk ->
                Log.d(TAG, "VOICEVOX streaming chunk start: ${chunk.take(32)}")
            },
            onError = { error ->
                viewModelScope.launch {
                    _uiMessage.emit("音声合成エラー: ${error.message}")
                }
            },
            onComplete = {
                viewModelScope.launch {
                    if (_speakingMessageId.value == messageId) {
                        _speakingMessageId.value = null
                    }
                }
            }
        )

        job.invokeOnCompletion {
            viewModelScope.launch {
                if (_speakingMessageId.value == messageId) {
                    _speakingMessageId.value = null
                }
            }
        }
    }

    private suspend fun playAudio(audioData: ByteArray) {
        val tempFile = withContext(Dispatchers.IO) {
            java.io.File.createTempFile("tts", ".wav", appContext.cacheDir).also { file ->
                file.outputStream().use { it.write(audioData) }
            }
        }

        withContext(Dispatchers.Main) {
            var mediaPlayer: android.media.MediaPlayer? = null
            var completed = false

            fun cleanup() {
                if (completed) return
                completed = true
                runCatching { mediaPlayer?.release() }
                mediaPlayer = null
                tempFile.delete()
            }

            try {
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    val player = android.media.MediaPlayer()
                    mediaPlayer = player
                    player.setOnCompletionListener(android.media.MediaPlayer.OnCompletionListener {
                        cleanup()
                        if (continuation.isActive) continuation.resume(Unit)
                    })
                    player.setOnErrorListener(android.media.MediaPlayer.OnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaPlayer error during TTS playback: what=$what extra=$extra")
                        cleanup()
                        if (continuation.isActive) continuation.resume(Unit)
                        true
                    })
                    continuation.invokeOnCancellation {
                        cleanup()
                    }
                    player.setDataSource(tempFile.absolutePath)
                    player.prepare()
                    player.start()
                    Log.d(TAG, "VOICEVOX playback started")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio", e)
                cleanup()
            }
        }
    }

    /**
     * ViewModel のライフサイクル終了時にリソースを完全解放する。
     * 特に KVキャッシュ（LiteRT-LM のセッション情報）の確実なアンロードを保証する。
     *
     * このメソッドが呼ばれるタイミング：
     * - Fragment がバックされた場合
     * - Activity が終了した場合
     * -voicevoxStreamingTts.stop()
         スワイプアウトやプロセス終了時
     */
    override fun onCleared() {
        Log.d(TAG, "ChatViewModel.onCleared() called - starting resource cleanup")

        GenerateImageToolBridge.handler = null
        imageGenConfirmCont?.cancel(null)
        imageGenConfirmCont = null
        _confirmationRequest.value = null
        
        // 推論をキャンセル（新しいセッションへの汚染を防止）
        stopGeneration()
        generationJob?.cancel()
        generationJob = null
        
        // メッセージ取得ジョブをキャンセル
        messagesCollectionJob?.cancel()
        messagesCollectionJob = null
        
        // WakeLock をリリース（画面スリープを許可）
        releaseScreenWakeLock()
        
        // Unload model resources including KV cache
        // viewModelScope is Cancelled here; cannot launch new coroutines.
        // Use GlobalScope + IO for async unload; wait up to 3 seconds.
        try {
            Log.d(TAG, "Unloading LiteRT-LM model and KVCache...")
            val unloadJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                modelManager?.unloadModel()
            }
            runCatching {
                kotlinx.coroutines.runBlocking {
                    withTimeoutOrNull(3000L) { unloadJob.join() }
                }
            }
            Log.i(TAG, "Model and KVCache unloaded successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unload model in onCleared: ${e.message}", e)
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
