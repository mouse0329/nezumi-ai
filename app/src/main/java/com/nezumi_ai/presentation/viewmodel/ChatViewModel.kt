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
import com.nezumi_ai.data.media.ImageLibraryStore
import com.nezumi_ai.data.inference.CpuCompatibility
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.media.MessageMediaStore
import com.nezumi_ai.data.inference.ModelDownloadWorker
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.MemoryObserver
import com.nezumi_ai.data.inference.Gemma4ThinkingParser
import com.nezumi_ai.data.inference.GgufToolPromptBuilder
import com.nezumi_ai.data.inference.EngineManager
import com.nezumi_ai.data.inference.ImageGenerationNotificationManager
import com.nezumi_ai.data.inference.GenerateImageToolBridge
import com.nezumi_ai.data.inference.GenerateImageToolHandler
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.TextTokenEstimator
import com.nezumi_ai.data.inference.ToolCallState
import com.nezumi_ai.data.inference.ToolExecutionResult
import com.nezumi_ai.data.inference.ToolResultCard
import com.nezumi_ai.data.inference.PromptBuilder
import com.nezumi_ai.data.memory.MemoryTextEmbedder
import com.nezumi_ai.data.preset.PresetConstants
import com.google.ai.edge.litertlm.ToolCall
import com.nezumi_ai.utils.PreferencesHelper
import com.nezumi_ai.sd.SdScheduler
import com.nezumi_ai.sd.SdModelLayout
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.isActive

class UserStopCancellationException : CancellationException("Stopped by user")

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
        /** Phase 14: トークン数と文字数の変換比率（1トークン ≈ 3.5～4文字）*/
        private const val TOKEN_TO_CHAR_RATIO = 4
        private const val COMPRESSION_RECENT_MESSAGE_COUNT = 6
        private const val COMPRESSION_SUMMARY_MAX_CHARS = 700
        /** 1 回の生成の上限（ネイティブが onDone を返さない場合の保険） */
        private const val GENERATION_WALL_TIMEOUT_MS = 900_000L
        /** 最初のトークン以降、この時間チャンクが無ければ打ち切り */
        private const val GENERATION_STALL_TIMEOUT_MS = 180_000L
        private const val GENERATION_STALL_CHECK_MS = 5_000L
        /** 推論開始を拒否するメモリ使用率閾値 */
        private const val MEMORY_BLOCK_INFERENCE_PERCENT = 90
        /** 推論中にキャンセルするメモリ使用率閾値 */
        private const val MEMORY_ABORT_INFERENCE_PERCENT = 95
        /** 推論中メモリ監視の確認間隔 */
        private const val MEMORY_WATCH_INTERVAL_MS = 10_000L
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

        private fun Throwable?.isMemoryLoadFailure(): Boolean {
            if (this == null) return false
            if (this is OutOfMemoryError) return true
            val errorMsg = message?.lowercase() ?: ""
            if (errorMsg.contains("llamainit failed") && errorMsg.contains("invalid model file or insufficient memory")) {
                return false
            }
            if (errorMsg.contains("out of memory") ||
                errorMsg.contains("failed to allocate memory") ||
                errorMsg.contains("memory allocation failed") ||
                errorMsg.contains("memory usage is too high") ||
                errorMsg.contains("memory pressure") ||
                errorMsg.contains("memory limit") ||
                errorMsg.contains("insufficient memory")
            ) {
                return true
            }
            return cause?.isMemoryLoadFailure() == true
        }

        private fun Throwable?.isModelLoadWarningMarker(): Boolean {
            val errorMsg = this?.message ?: return false
            return errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN"
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

    /**
     * ★ 応答バリアント選択状態:
     *   key = parentUserMessageId, value = 現在選択中の variantIndex。
     *   未登録の user メッセージは "最新バリアント" をデフォルト選択とする。
     *   DB には保存せずメモリのみで管理（セッション切替でリセット）。
     */
    private val _selectedVariantByParent = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val selectedVariantByParent: StateFlow<Map<Long, Int>> = _selectedVariantByParent

    /**
     * 同じ parentUserMessageId を持つ assistant メッセージをグループ化して、
     * 現在選択中のバリアント 1 件だけを残したリストを返す。
     * ★ 既存レコードの互換性: parentUserMessageId == null の assistant メッセージは
     *   マイグレーション前の旧データなので、バリアントグループ化せずそのまま保持。
     */
    private fun applyVariantSelection(
        all: List<MessageEntity>,
        selection: Map<Long, Int>
    ): List<MessageEntity> {
        // parent ごとに assistant メッセージをグループ化
        val byParent: Map<Long, List<MessageEntity>> = all
            .filter { it.role != "user" && it.parentUserMessageId != null }
            .groupBy { it.parentUserMessageId!! }
            .mapValues { entry ->
                entry.value.sortedWith(compareBy({ it.variantIndex }, { it.timestamp }))
            }

        val visibleIds = HashSet<Long>()
        byParent.forEach { (parentId, variants) ->
            if (variants.isEmpty()) return@forEach
            val requested = selection[parentId]
            val chosen = if (requested != null && requested in variants.indices) {
                variants[requested]
            } else {
                // デフォルトは最新 (一番後に生成されたもの)
                variants.last()
            }
            visibleIds.add(chosen.id)
        }

        return all.filter { msg ->
            when {
                msg.role == "user" -> true
                msg.parentUserMessageId == null -> true  // 旧データはそのまま表示
                else -> msg.id in visibleIds
            }
        }
    }

    /**
     * 未フィルタで保持している "全メッセージ" スナップショット。選択バリアントを
     * 変えただけで再フィルタできるようキャッシュしておく。DB Flow から検取すると
     * 逆に Room クエリが増えて共有トラブルの元になるので、メモリに保持する。
     */
    private val allMessagesSnapshot = MutableStateFlow<List<MessageEntity>>(emptyList())

    /**
     * ★ UI 向けに公開するバリアント情報。
     *   key = parentUserMessageId, value = (全バリアント件数, 現在選択中の index)。
     *   allMessagesSnapshot / _selectedVariantByParent が変化するたびに UI へ届くよう combine する。
     */
    val variantInfoByParent: StateFlow<Map<Long, Pair<Int, Int>>> =
        combine(allMessagesSnapshot, _selectedVariantByParent) { all, selection ->
            val byParent: Map<Long, List<MessageEntity>> = all
                .filter { it.role != "user" && it.parentUserMessageId != null }
                .groupBy { it.parentUserMessageId!! }
            byParent.mapValues { entry ->
                val siblings = entry.value.sortedWith(compareBy({ it.variantIndex }, { it.timestamp }))
                val total = siblings.size
                val requested = selection[entry.key]
                val idx = if (requested != null && requested in 0 until total) requested else total - 1
                total to idx
            }
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            emptyMap()
        )

    /**
     * 外部 (UI) から呼ばれる: ある parent のバリアントを切り替える。
     * インデックスはクリップされるので鶴々かな値を渡しても安全。
     */
    fun selectAssistantVariant(parentUserMessageId: Long, newIndex: Int) {
        val all = allMessagesSnapshot.value
        val siblings = all.filter { it.parentUserMessageId == parentUserMessageId && it.role != "user" }
        if (siblings.isEmpty()) return
        val clamped = newIndex.coerceIn(0, siblings.size - 1)
        val newMap = _selectedVariantByParent.value.toMutableMap()
        newMap[parentUserMessageId] = clamped
        _selectedVariantByParent.value = newMap
        _messages.value = applyVariantSelection(all, newMap)
    }

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

    // ★ 「モデル準備中」のフェーズラベルと経過秒数を一元管理し、以前の「[gemma4-2b] エンジンを初期化中」などの
    //   モデル名付きバラバラ表記を全て「モデル準備中」に統一し、進捗は (n秒) の形で見えるようにする。
    private val _modelLoadingPhase = MutableStateFlow<String?>(null)
    private val _modelLoadingElapsedSec = MutableStateFlow(0)
    private var modelLoadingTickerJob: Job? = null
    private var modelLoadingStartMs: Long = 0L

    private fun composeModelLoadingLabel(): String {
        val phase = _modelLoadingPhase.value
        val elapsed = _modelLoadingElapsedSec.value
        val base = "モデル準備中"
        return buildString {
            append(base)
            if (!phase.isNullOrBlank()) {
                append(" · ")
                append(phase)
            }
            if (elapsed > 0) {
                append(" (")
                append(elapsed)
                append("秒)")
            }
        }
    }

    /**
     * モデルロード待ちのオーバーレイ表示を開始する。重複起動しても安全。
     * ティッカーが 1 秒毎に _modelLoadingStatus を更新し経過秒数を反映させる。
     */
    private fun startModelLoadingIndicator(initialPhase: String? = null) {
        _modelLoadingPhase.value = initialPhase
        if (modelLoadingTickerJob?.isActive == true) {
            _modelLoadingStatus.value = composeModelLoadingLabel()
            return
        }
        modelLoadingStartMs = System.currentTimeMillis()
        _modelLoadingElapsedSec.value = 0
        _modelLoadingStatus.value = composeModelLoadingLabel()
        modelLoadingTickerJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000L)
                val secs = ((System.currentTimeMillis() - modelLoadingStartMs) / 1000L).toInt().coerceAtLeast(0)
                _modelLoadingElapsedSec.value = secs
                _modelLoadingStatus.value = composeModelLoadingLabel()
            }
        }
    }

    /** ロードフェーズのラベルだけ差し替える。タイマーは止めない。 */
    private fun updateModelLoadingPhase(phase: String?) {
        _modelLoadingPhase.value = phase
        _modelLoadingStatus.value = composeModelLoadingLabel()
    }

    /**
     * モデルロード待ちのオーバーレイを完全にクリアする。早期 return / 例外 / 既ロードスキップのすべての終端で呼ぶことを想定。
     * ★ 「モデル準備中が終わらない」バグの防御の中核。
     */
    private fun clearModelLoadingIndicator() {
        modelLoadingTickerJob?.cancel()
        modelLoadingTickerJob = null
        _modelLoadingElapsedSec.value = 0
        _modelLoadingPhase.value = null
        _isModelLoading.value = false
        _modelLoadingStatus.value = ""
    }

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
        val db = com.nezumi_ai.data.database.NezumiAiDatabase.getInstance(appContext)
        val sessionRepo = com.nezumi_ai.data.repository.MemorySessionRepository(
            db.memorySessionDao()
        )
        val chunkRepo = com.nezumi_ai.data.repository.ChatChunkRepository(
            db.chatChunkDao(), appContext
        )
        com.nezumi_ai.data.memory.MemoryExtractionWorker(repo, sessionRepo, chunkRepo).also { worker ->
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

    /** true のとき、このチャットでは Thinking を強制ONする */
    private val _chatSessionThinkingEnabledOverride = MutableStateFlow(false)
    private val chatSessionThinkingEnabledOverride: StateFlow<Boolean> = _chatSessionThinkingEnabledOverride.asStateFlow()

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

    private val _inferenceStream = MutableSharedFlow<String>()
    val inferenceStream: SharedFlow<String> = _inferenceStream

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

    private val _currentTps = MutableStateFlow<Float?>(null)
    val currentTps: StateFlow<Float?> = _currentTps.asStateFlow()

    private val _confirmationRequest = MutableStateFlow<String?>(null)
    val confirmationRequest: StateFlow<String?> = _confirmationRequest.asStateFlow()

    private var imageGenConfirmCont: CancellableContinuation<String?>? = null

    @Volatile
    private var streamingAssistantMessageIdForTools: Long? = null

    private val generateImageToolHandler = GenerateImageToolHandler { toolCall ->
        // ツール実行の完了を待機して結果を返すように変更
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

    private val _modelErrorDialogMessage = MutableStateFlow<String?>(null)
    val modelErrorDialogMessage: StateFlow<String?> = _modelErrorDialogMessage.asStateFlow()

    private fun formatModelErrorDialogMessage(
        title: String,
        message: String,
        details: String? = null
    ): String {
        return buildString {
            append(title)
            append("\n\n")
            append(message)
            details?.takeIf { it.isNotBlank() }?.let {
                append("\n\n")
                append(it)
            }
        }
    }

    fun dismissMemoryWarning() {
        _memoryWarning.value = null
    }

    fun dismissModelErrorDialogMessage() {
        _modelErrorDialogMessage.value = null
    }

    private fun handleModelLoadIssue(
        selectedModel: String,
        error: Throwable?,
        title: String = "モデルロードエラー",
        message: String = "モデルのロードに失敗しました。設定画面で再ダウンロードしてください。"
    ) {
        val errorMsg = error?.message?.trim().takeUnless { it.isNullOrBlank() }
        val details = errorMsg?.let { "$it\nモデル: $selectedModel" }
            ?: "モデル: $selectedModel"
        _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
            title = title,
            message = message,
            details = details
        )
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

                    val resumedImages = mutableListOf<Bitmap>()
                    val resumedAudioClips = mutableListOf<ByteArray>()
                    try {
                        lastUser.imageUri
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.forEach { uriStr ->
                                val uri = MessageMediaStore.toUri(uriStr) ?: return@forEach
                                val bitmap = loadBitmapFromUri(uri) ?: return@forEach
                                val scaled = scaleBitmapTo1024(bitmap)
                                if (scaled !== bitmap) bitmap.recycle()
                                resumedImages.add(scaled)
                                Log.d(TAG, "Reloaded image for resumed inference: $uriStr (${resumedImages.size}/5)")
                            }

                        lastUser.audioUri?.let { uriStr ->
                            val uri = MessageMediaStore.toUri(uriStr)
                            if (uri != null) {
                                val audioBytes = loadAudioBytesFromUri(uri)
                                if (audioBytes != null) {
                                    resumedAudioClips.add(audioBytes)
                                    Log.d(TAG, "Reloaded audio for resumed inference: $uriStr")
                                }
                            }
                        }

                        generateAIResponse(
                            sessionId = sessionId,
                            userMessage = lastUser.content,
                            images = resumedImages,
                            audioClips = resumedAudioClips,
                            currentTurnMessageId = lastUser.id
                        )
                    } finally {
                        resumedImages.forEach { bitmap ->
                            if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                    }
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
        val previousSessionId = _currentSessionId.value
        _currentSessionId.value = sessionId
        if (lastThinkingSessionId != sessionId) {
            hasUserToggledThinking = false
            lastThinkingSessionId = sessionId
            _chatSessionThinkingEnabledOverride.value = false
        }
        // チャットを開いた直後は OFF 表示（disableThinking=true）を既定にする。
        if (!hasUserToggledThinking) {
            _chatSessionDisableThinking.value = true
            _chatSessionThinkingEnabledOverride.value = false
        }

        // ★ Bug fix: セッションを作り直した / 切り替えた際に KV キャッシュをクリアして
        //   前セッションの Thinking コンテキストが残るのを防ぐ。
        //   （「セッションを作り直すと OFF にしても Thinking される」バグの修正）
        if (previousSessionId != sessionId) {
            runCatching { ModelManager.getInstance(appContext).clearKvCache() }
                .onFailure { Log.w(TAG, "clearKvCache on session change failed", it) }
        }

        stopGenerationInternal()

        // ★ メーター不正確修正: セッション遷移時に圧縮コンテキストキャッシュをクリア（同期的に実行）
        clearCompressedContextCache(sessionId)
        Log.d(TAG, "setCurrentSession: Cleared compressed context cache for sessionId=$sessionId")

        // キャンセル前のコレクションジョブ
        messagesCollectionJob?.cancel()

        messagesCollectionJob = viewModelScope.launch(Dispatchers.IO) {
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
                    val snapshot = msgs.toList()
                    // ★ バリアント適用前の全件を保持しておき、UI 側で切替可能にする
                    allMessagesSnapshot.value = snapshot
                    val filtered = applyVariantSelection(snapshot, _selectedVariantByParent.value)
                    val contextUsageChars = estimateContextUsageChars(filtered)
                    withContext(Dispatchers.Main) {
                        _messages.value = filtered
                        // ★ メーター不正確修正: キャッシュクリア完了後にメーター計算を実行
                        _contextUsageChars.value = contextUsageChars
                    }
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
        val previousDisabled = _chatSessionDisableThinking.value
        val previousOverride = _chatSessionThinkingEnabledOverride.value
        hasUserToggledThinking = true
        _chatSessionDisableThinking.value = disabled
        _chatSessionThinkingEnabledOverride.value = !disabled
        // ★ Qwen 等の `/think` `/no_think` directive はチャットテンプレ自体を切り替える効果を
        //   持つため、KV キャッシュに前モードの状態が残っているとトグル直後の生成が崩壊する。
        //   Thinking トグルが実際に切り替わったタイミングで KV キャッシュをクリアする。
        val effectiveChanged = previousDisabled != disabled || previousOverride == disabled
        if (effectiveChanged) {
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { ModelManager.getInstance(appContext).clearKvCache() }
                    .onFailure { Log.w(TAG, "clearKvCache on thinking toggle failed", it) }
            }
        }
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

                // ★ 修正: ファイル読み込みエラーを先に検出（PATH NOT FOUND など）
                // これをメモリエラーより先にチェックすることで、ファイルロード失敗が正規のエラーモーダルで表示される
                val errorMsg = error?.message ?: ""
                if (shouldDeleteLocalModelFileOnLoadError(errorMsg)) {
                    Log.w(TAG, "モデルファイルの読み込みエラー: $normalizedModel")
                    _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                        title = "モデルロードエラー",
                        message = "モデルファイルが読み込めません。設定画面で再ダウンロードしてください。",
                        details = errorMsg
                    )
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
                    return@launch
                }

                // メモリエラーを検出（実際の OOM エラー）
                if (error.isMemoryLoadFailure()) {
                    val memStatus = MemoryObserver.getMemoryStatus(appContext)
                    _memoryError.value = MemoryErrorInfo(
                        usedPercent = memStatus.usedPercent,
                        usedMB = memStatus.usedMB,
                        totalMB = memStatus.maxMB
                    )
                    return@launch
                }

                // その他のモデルロードエラー
                _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                    title = "モデルロードエラー",
                    message = "モデルのロードに失敗しました。",
                    details = errorMsg
                )


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

                val errorMsg = error?.message ?: ""
                if (shouldDeleteLocalModelFileOnLoadError(errorMsg)) {
                    _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                        title = "モデルロードエラー",
                        message = "プリセットモデルのロードに失敗しました。ファイルが見つかりません。",
                        details = errorMsg
                    )
                } else if (error.isMemoryLoadFailure()) {
                    val memStatus = MemoryObserver.getMemoryStatus(appContext)
                    _memoryError.value = MemoryErrorInfo(
                        usedPercent = memStatus.usedPercent,
                        usedMB = memStatus.usedMB,
                        totalMB = memStatus.maxMB
                    )
                } else {
                    _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                        title = "モデルロードエラー",
                        message = "プリセットモデルのロードに失敗しました",
                        details = error?.message
                    )
                }
            }
        }
    }

    fun sendMessage(userMessage: String) {
        if (_isLoading.value) return

        // ★ UI フリーズ対策: 送信タップ直後に UI 状態を同期的に反映する。
        //   MutableStateFlow.value は thread-safe。ここで先に true にすることで、
        //   ensureValidCurrentSession() 等のサスペンド前に送信ボタン無効化と
        //   ローディングオーバーレイが表示され、フリーズしたように見える問題を回避。
        //   ★ モデルロード進捗は loadModelWithOverlay() が自身で立てるので、ここで先立てはしない。
        //     以前は入口で _isModelLoading=true + startModelLoadingIndicator() を先立てしていたが、
        //     モデル既ロード時は loadModelWithOverlay がショートカットして一切のロード処理をしないため、
        //     入口で立てたインジケーターだけが残り「ロード不要なのにグルグルが無限に続く」バグの原因になっていた。
        _isLoading.value = true

        viewModelScope.launch {
            val thisJob = coroutineContext[Job] ?: return@launch

            generationControlMutex.withLock {
                generationJob?.cancel(UserStopCancellationException())
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

                // セッション名を送信直後に反映
                maybeUpdateSessionTitleFromUserMessage(sessionId, userMessage)

                // 入力フィールドをクリア
                _inputText.value = ""

                // AI応答を生成（_isLoading は送信タップ時にすでに true）
                // Note: sendMessage はテキストのみサポート。
                // 画像付きメッセージは sendMessageWithMedia を使用すること。
                generateAIResponse(sessionId, userMessage, images = emptyList(), audioClips = emptyList(), currentTurnMessageId = userMessageId)
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error sending message", e)
            } finally {
                _isLoading.value = false
                // ★ 早期 return / 例外パスで loadModelWithOverlay に到達せず
                //   _isModelLoading が残り UI が固まるのを防止する防御的クリーンアップ。
                //   loadModelWithOverlay 自体が到達した場合は既に自身の finally でクリア済み。
                if (_isModelLoading.value) {
                    clearModelLoadingIndicator()
                }
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

                val config = settingsRepository.getInferenceConfigForModel(selectedModel, appContext)
                val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
                if (loadResult.isFailure) {
                    val error = loadResult.exceptionOrNull()
                    val errorMsg = error?.message ?: "Unknown error"
                    Log.e(TAG, "Compression model load failed: $errorMsg", error)
                    if (errorMsg == "MEMORY_WARNING_SHOWN" || errorMsg == "CPU_COMPAT_WARNING_SHOWN") {
                        Log.d(TAG, "Model warning shown during compression - waiting for user action: $errorMsg")
                        return@launch
                    }
                    _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                        title = "モデルロードエラー",
                        message = "圧縮用時モデルのロードに失敗しました",
                        details = errorMsg
                    )
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
            val job = generationJob
            generationJob = null
            _isLoading.value = false
            job
        }

        currentJob?.cancel(UserStopCancellationException())

        try {
            val manager = requireModelManager()
            val sessionId = _currentSessionId.value
            if (sessionId != null) {
                manager.cancelInferenceForSession(sessionId)
            } else {
                manager.cancelInference()
            }
            // ★ Bug fix: ユーザー停止 / 取り消し後は DB 上の履歴とネイティブ KV キャッシュが
            //   乖離しやすい。特に Qwen の `/think` `/no_think` 切替後に途中停止すると、
            //   次の送信でキャッシュ側に残った中途半端な assistant ターンが再利用され、
            //   「こんにちは」に対して `2.0.0 ...` のような壊れた出力を返すことがあった。
            //   停止時点で KV を明示的にクリアして、次回は DB 履歴から組み直す。
            manager.clearKvCache()
            // ★ GGUF では cancelInference() だけではネイティブ KV に途中トークンが
            //   残るケースがあるため、次回推論開始前の force-clear もリクエストしておく。
            //   manager 側は GGUF / LiteRT を含めてエンジンチェーンを踏んだ
            //   ため、未対応エンジンではこの呼び出しは no-op として育てる。
            runCatching { manager.requestForceClearBeforeNextInference() }
                .onFailure { Log.w(TAG, "requestForceClearBeforeNextInference (stopGenerationInternal) failed", it) }
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

    /**
     * 直前の AI 応答を削除し、直前のユーザーメッセージを使って再度応答を生成する。
     * 対象エントリだけでなく、それより後ろに作られた AI→ツール結果などの連鎖メッセージも削除し、ユーザーメッセージ自体は残して応答のみやり直す。
     * ボタンはUI側で末尾のAIメッセージにのみ表示されるが、万一他のメッセージから呼ばれても安全に動作させる。
     */
    /**
     * ★ 応答バリアント方式の再生成:
     *   既存の AI 応答は削除せずにそのまま保持し、同じ user プロンプト・同じ会話コンテキストで
     *   新しい assistant バリアントを追加する。UI 側で◀ n/m ▶ で切り替え可能。
     *   引数 aiMessageId は「この応答に対して再生成を走らせる」対象の応答 id 。
     *   未指定の場合は末尾の assistant 応答に対して実行。
     */
    fun regenerateLastResponse(aiMessageId: Long? = null) {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value) return

        // ★ UI フリーズ対策：送信タップ同様に同期的に「生成中」を立てる。
        _isLoading.value = true

        viewModelScope.launch(Dispatchers.Default) {
            val thisJob = coroutineContext[Job]
            generationControlMutex.withLock {
                generationJob?.cancel(UserStopCancellationException())
                generationJob = thisJob
            }

            try {
                val messages = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForSessionOnce(sessionId)
                }
                if (messages.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiMessage.emit("再生成する応答がありません")
                    }
                    return@launch
                }

                // 対象のAIメッセージを決定。id指定があればそれを、なければ末尾の assistant を使う。
                val targetAi = if (aiMessageId != null) {
                    messages.firstOrNull { it.id == aiMessageId && it.role != "user" }
                } else {
                    messages.lastOrNull { it.role != "user" }
                }
                if (targetAi == null) {
                    withContext(Dispatchers.Main) {
                        _uiMessage.emit("再生成する応答がありません")
                    }
                    return@launch
                }

                // 対象応答の parent (元になった user メッセージ) を探す。
                // parentUserMessageId が付いていればそれを使い、なければ旧データなので
                // タイムスタンプ順で直前の user を探す。
                val parentUserMessageId: Long = targetAi.parentUserMessageId ?: run {
                    val targetIdx = messages.indexOf(targetAi)
                    val prevUser = if (targetIdx > 0) {
                        (targetIdx - 1 downTo 0).firstNotNullOfOrNull { i ->
                            messages[i].takeIf { it.role == "user" }
                        }
                    } else null
                    prevUser?.id ?: run {
                        withContext(Dispatchers.Main) {
                            _uiMessage.emit("再生成に必要なユーザーメッセージが見つかりません")
                        }
                        return@launch
                    }
                }
                val userMessage = messages.firstOrNull { it.id == parentUserMessageId && it.role == "user" }
                    ?: run {
                        withContext(Dispatchers.Main) {
                            _uiMessage.emit("再生成に必要なユーザーメッセージが見つかりません")
                        }
                        return@launch
                    }

                // 旧データ (parentUserMessageId == null) の assistant レコードもグループにひとまとめにするため、
                // このタイミングで parent 付けにマイグレーションしてしまう。
                withContext(Dispatchers.IO) {
                    val siblings = messages.filter {
                        it.role != "user" &&
                            it.parentUserMessageId == null &&
                            it.id == targetAi.id
                    }
                    siblings.forEachIndexed { idx, msg ->
                        messageRepository.updateParentUserMessageId(
                            messageId = msg.id,
                            parentUserMessageId = parentUserMessageId,
                            variantIndex = idx
                        )
                    }
                }

                // 共有する parent を持つ既存バリアントの件数 (これが新バリアントの variantIndex)
                val existingVariantCount = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForSessionOnce(sessionId).count {
                        it.role != "user" && it.parentUserMessageId == parentUserMessageId
                    }
                }

                // ★ 既存 AI 応答は削除しない。stopGenerationInternal() だけでストリームを安全に止めておく。
                stopGenerationInternal()
                // stopGenerationInternal() は _isLoading=false にするので UI のフリーズ対策でクリンチとして true に戻す。
                withContext(Dispatchers.Main) {
                    _isLoading.value = true
                }

                // 同じプロンプトを使い回すため、前ターンの KV キャッシュは新バリアント向けにクリアする。
                // プロンプト内容は applyVariantSelection ・ 新規バリアント選択によって自動的に
                // 「今選択中の応答だけ」を履歴に含む形で再構築される。
                withContext(Dispatchers.IO) {
                    compressedContextCache.remove(sessionId)
                    runCatching { requireModelManager().clearKvCache() }
                        .onFailure { Log.w(TAG, "clearKvCache after regenerate failed", it) }
                    sessionRepository.updateSessionLastUpdated(sessionId)
                }

                // 新しいバリアントの作成は generateAIResponse の streamingMessageId 作成後に行う。
                //   事前に _pendingAssistantVariantSpec に parent と index をセットしておけば、
                //   generateAIResponse 内の addMessage 呼び出しがそれを拾って assistant レコードに付与してくれる。
                _pendingAssistantVariantSpec = AssistantVariantSpec(
                    parentUserMessageId = parentUserMessageId,
                    variantIndex = existingVariantCount
                )
                // 新しいバリアントを選択ターゲットにしておく (UI を新応答にジャンプさせる)
                val newSelection = _selectedVariantByParent.value.toMutableMap()
                newSelection[parentUserMessageId] = existingVariantCount
                _selectedVariantByParent.value = newSelection

                // ユーザー側に保存されていた画像/音声を復元して同じ入力で推論を走らせる。
                val images = mutableListOf<Bitmap>()
                val audioClips = mutableListOf<ByteArray>()
                val storedImageUris = userMessage.imageUri
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                for (uriStr in storedImageUris) {
                    val uri = MessageMediaStore.toUri(uriStr) ?: continue
                    val bitmap = loadBitmapFromUri(uri)
                    if (bitmap != null) images.add(bitmap)
                }
                val storedAudioUri = userMessage.audioUri
                if (!storedAudioUri.isNullOrBlank()) {
                    val uri = MessageMediaStore.toUri(storedAudioUri)
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                appContext.contentResolver.openInputStream(uri)?.use { input ->
                                    audioClips.add(input.readBytes())
                                }
                            }.onFailure { Log.w(TAG, "regenerate: read audio failed", it) }
                        }
                    }
                }

                generateAIResponse(
                    sessionId = sessionId,
                    userMessage = userMessage.content,
                    images = images,
                    audioClips = audioClips,
                    currentTurnMessageId = userMessage.id
                )
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error regenerating response", e)
            } finally {
                _pendingAssistantVariantSpec = null
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    if (_isModelLoading.value) {
                        clearModelLoadingIndicator()
                    }
                }
                if (generationJob == thisJob) generationJob = null
            }
        }
    }

    /** 再生成リクエストに伴う assistant バリアントのスペック。generateAIResponse 内で拾い上げられる。 */
    private data class AssistantVariantSpec(val parentUserMessageId: Long, val variantIndex: Int)
    private var _pendingAssistantVariantSpec: AssistantVariantSpec? = null

    private suspend fun revokePromptFromMessageInternal(sessionId: Long, promptMessageId: Long) {
        // ★ Bug fix: 非同期 stopGeneration() だと、取り消し処理が message delete より先に終わる保証がなく、
        //   停止中のストリームが削除済みメッセージへ後から書き戻してしまうレースがあった。
        //   ここでは suspend 版を直接呼び、停止完了後に削除する。
        stopGenerationInternal()
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
        runCatching { requireModelManager().clearKvCache() }
            .onFailure { Log.w(TAG, "clearKvCache after revoke failed", it) }
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
        var currentHasMediaInput = false
        var currentEngineModelName: String? = null
        // Bug fix(#5): 過去ターンの media を LiteRT に再添付する際に生成する Bitmap を保持し、
        // finally で確実に recycle するため try の外側にスコープを出しておく。
        val pastHistoryBitmapsToRecycle = mutableListOf<Bitmap>()
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
            if (memoryPercent >= MEMORY_BLOCK_INFERENCE_PERCENT) {
                Log.w(TAG, "generateAIResponse: memory too high ($memoryPercent%), blocking inference")
                val memStatus = MemoryObserver.getMemoryStatus(appContext)
                _memoryError.value = MemoryErrorInfo(
                    usedPercent = memStatus.usedPercent,
                    usedMB = memStatus.usedMB,
                    totalMB = memStatus.maxMB
                )
                return
            }
            _selectedModel.value = selectedModel
            val engineModelName = toEngineModelName(selectedModel)
            currentEngineModelName = engineModelName
            val hasMediaInput = images.isNotEmpty() || audioClips.isNotEmpty()
            currentHasMediaInput = hasMediaInput
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

            // チャット推論時はメモリ警告を出さずロードを続行する。
            // 警告はモデルダウンロード・設定画面側に限定し、会話のたびにブロックしない。
            val isModelAlreadyLoaded = manager.isModelLoaded(engineModelName, config)
            val skipMemoryWarning = true
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
                if (error.isModelLoadWarningMarker()) {
                    Log.d(TAG, "Model warning shown - waiting for user action: $errorMsg")
                    return
                }

                // メモリエラーを検出（実際の OOM エラー）
                if (error.isMemoryLoadFailure()) {
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
                    // モーダルダイアログ用に詳細をセット
                    _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                        title = "モデルロードエラー",
                        message = "モデルファイルが読み込めません。設定画面で再ダウンロードしてください。",
                        details = if (errorMsg.isNotBlank()) "${errorMsg}\nパス: $selectedModel" else "パス: $selectedModel"
                    )
                    // 軽い通知も出す
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

                handleModelLoadIssue(selectedModel, error)
                return
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

            // Bug fix(#5): LiteRT-LM では会話の KV キャッシュを再利用するため、以前のターンで渡した
            // 画像/音声をモデルが参照できない。以前ターンの media を送信履歴から採集し、今ターンの
            // images/audioClips の先頭に追加することで、マルチターンでも画像/音声を参照できるようにする。
            // GGUF エンジンは現状 mtmd 側の仕様上マルチターン参照をサポートしていないため、
            // LiteRT エンジン使用時のみ過去 media を取り込む。
            val isLiteRtEngine = !isGgufEngineModel(engineModelName)
            val combinedImages = mutableListOf<Bitmap>().also { it.addAll(images) }
            val combinedAudio = mutableListOf<ByteArray>().also { it.addAll(audioClips) }
            if (isLiteRtEngine) {
                runCatching {
                    val historyMessages = messageRepository.getMessagesForSessionOnce(sessionId)
                    // 今ターンの user メッセージを除外するため currentTurnMessageId より古いものだけを対象にする。
                    val pastMessages = if (currentTurnMessageId != null) {
                        historyMessages.filter { it.id != currentTurnMessageId && it.timestamp <
                            (historyMessages.firstOrNull { m -> m.id == currentTurnMessageId }?.timestamp ?: Long.MAX_VALUE) }
                    } else {
                        historyMessages.dropLast(1)
                    }
                    // 過去の user メッセージの画像/音声を古い順に読み込んで先頭にプレフィックスする。
                    val historicalImages = mutableListOf<Bitmap>()
                    val historicalAudio = mutableListOf<ByteArray>()
                    for (m in pastMessages.filter { it.role == "user" }) {
                        m.imageUri
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.forEach { uriStr ->
                                val uri = MessageMediaStore.toUri(uriStr) ?: return@forEach
                                val bmp = loadBitmapFromUri(uri) ?: return@forEach
                                val scaled = scaleBitmapTo1024(bmp)
                                if (scaled !== bmp) bmp.recycle()
                                historicalImages.add(scaled)
                                pastHistoryBitmapsToRecycle.add(scaled)
                            }
                        m.audioUri?.let { uriStr ->
                            val uri = MessageMediaStore.toUri(uriStr) ?: return@let
                            val bytes = loadAudioBytesFromUri(uri) ?: return@let
                            historicalAudio.add(bytes)
                        }
                    }
                    if (historicalImages.isNotEmpty() || historicalAudio.isNotEmpty()) {
                        combinedImages.addAll(0, historicalImages)
                        combinedAudio.addAll(0, historicalAudio)
                        Log.d(
                            TAG,
                            "LiteRT multi-turn media replay: addedImages=${historicalImages.size} addedAudio=${historicalAudio.size}"
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to load past-turn media for LiteRT replay", it)
                }
            }
            val effectiveHasMediaInput = combinedImages.isNotEmpty() || combinedAudio.isNotEmpty()
            if (effectiveHasMediaInput && isLiteRtEngine) {
                // LiteRT 側に「このセッションで media を取り扱う」と伝え、 KV キャッシュ再利用ではなく
                // 毎ターン conversation を作り直すようにさせる。
                runCatching {
                    requireModelManager().liteRtEngineForMultiTurnMedia()?.markSessionHasMedia(sessionId)
                }
            }

            // ストリーミング推論を実行（マルチモーダル対応）
            val aiResponseFlow: Flow<String> = withContext(Dispatchers.IO) {
                if (effectiveHasMediaInput) {
                    // マルチモーダル推論
                    Log.d(TAG, "Using multimodal inference: ${combinedImages.size} images, ${combinedAudio.size} audio clips (incl. past turns)")
                    manager.runInferenceWithMedia(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        images = combinedImages,
                        audioClips = combinedAudio,
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

            // ★ 応答バリアント: 再生成リクエストの場合は _pendingAssistantVariantSpec がセットされているので
            //   その parent + variantIndex を使う。通常送信の場合は currentTurnMessageId (user メッセージ id) を
            //   parent にして variantIndex=0 で新規登録する。currentTurnMessageId が null なら旧互換の null。
            val variantSpec = _pendingAssistantVariantSpec
            val effectiveParentId = variantSpec?.parentUserMessageId ?: currentTurnMessageId
            val effectiveVariantIndex = variantSpec?.variantIndex ?: 0
            streamingMessageId = messageRepository.addMessage(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                isStreaming = true,
                parentUserMessageId = effectiveParentId,
                variantIndex = effectiveVariantIndex
            )
            val activeStreamingMessageId = streamingMessageId
                ?: throw IllegalStateException("Failed to create streaming message")
            streamingAssistantMessageIdForTools = activeStreamingMessageId
            // ★ ストリーミングレコードの id が定まったので、この parent の選択ターゲットを
            //   新規作成した variantIndex に合わせておく。UI を新応答にジャンプさせるため。
            if (effectiveParentId != null) {
                val newSelection = _selectedVariantByParent.value.toMutableMap()
                newSelection[effectiveParentId] = effectiveVariantIndex
                _selectedVariantByParent.value = newSelection
            }

            val answerBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            var nativeThinkingStream = false
            // Only seed a synthetic `<think>` opener when the selected model family actually uses
            // assistant-side `<think>...</think>` prefilling. Qwen uses `/think` appended to the
            // last user turn, and Gemma uses a global `<|think|>` prefix instead; seeding `<think>`
            // for those models would misparse their outputs.
            val implicitThinkPrefill =
                config.enableThinking &&
                    isGgufEngineModel(engineModelName) &&
                    PromptBuilder.usesAssistantThinkingPrefill(engineModelName)
            if (implicitThinkPrefill) {
                answerBuilder.append("<think>\n")
            }
            var lastPersistedContent = ""
            var lastPersistedThinking: String? = null
            var lastPersistAt = 0L
            var toolResultsJson: String? = null
            var firstOutputAtMs: Long? = null
            var generationEndAtMs: Long? = null
            var tokenCount = 0f

            // ストリーム内容を収集
            // タイムアウトは「最初の出力が来るまで」のみ有効。
            val firstTokenSeen = AtomicBoolean(false)

            val lastChunkAt = AtomicLong(SystemClock.elapsedRealtime())
            val wallEndAt = SystemClock.elapsedRealtime() + GENERATION_WALL_TIMEOUT_MS
            val toolCallInProgress = AtomicBoolean(false)
            var streamAbortNote: String? = null
            var collectionCancelledByUser = false
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
                                if (toolCallInProgress.get()) continue
                                val idle = SystemClock.elapsedRealtime() - lastChunkAt.get()
                                if (idle >= GENERATION_STALL_TIMEOUT_MS) {
                                    throw GenerationStalledException()
                                }
                            }
                        }

                        // 推論中メモリ監視：閾値超えたらキャンセル
                        val memoryWatchJob = launch {
                            while (isActive) {
                                delay(MEMORY_WATCH_INTERVAL_MS)
                                val mem = manager.getMemoryUsagePercent()
                                if (mem >= MEMORY_ABORT_INFERENCE_PERCENT) {
                                    Log.w(TAG, "memoryWatchJob: memory critical ($mem%), aborting inference")
                                    val memStatus = MemoryObserver.getMemoryStatus(appContext)
                                    _memoryError.value = MemoryErrorInfo(
                                        usedPercent = memStatus.usedPercent,
                                        usedMB = memStatus.usedMB,
                                        totalMB = memStatus.maxMB
                                    )
                                    cancel(CancellationException("MEMORY_CRITICAL"))
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
                                // Split incoming chunk by embedded control markers so that
                                // markers like \u0000__TPS__\u0000 or \u0000__FINAL__\u0000
                                // are handled separately even if they arrive inside a single
                                // delivered chunk.
                                var finalFromModelGlobal: String? = null
                                val segments = InferenceStreamProtocol.splitStreamChunks(chunk)
                                for (seg in segments) {
                                    val finalFromModel = InferenceStreamProtocol.decodeFinal(seg)
                                    val thinkDelta = InferenceStreamProtocol.decodeThinkChunk(seg)
                                    val toolCallChunk = InferenceStreamProtocol.decodeToolCallChunk(seg)
                                    val toolResultChunk = InferenceStreamProtocol.decodeToolResultChunk(seg)
                                    val toolResults = InferenceStreamProtocol.decodeToolResults(seg)
                                    val tpsValue = InferenceStreamProtocol.decodeTps(seg)

                                    when {
                                        finalFromModel != null -> {
                                            Log.d(TAG, "FINAL received: length=${finalFromModel.length}")
                                            val sanitizedFinal =
                                                Gemma4ThinkingParser.sanitizeVisibleText(finalFromModel)
                                            val resolvedFinal = sanitizedFinal.ifBlank {
                                                lastPersistedContent.ifBlank { finalFromModel }
                                            }
                                            finalFromModelGlobal = resolvedFinal
                                            answerBuilder.clear()
                                            answerBuilder.append(resolvedFinal)
                                        }
                                        thinkDelta != null -> {
                                            if (!nativeThinkingStream && answerBuilder.isNotBlank()) {
                                                val leadingThinking =
                                                    Gemma4ThinkingParser.sanitizeVisibleText(answerBuilder.toString())
                                                if (leadingThinking.isNotBlank()) {
                                                    thinkingBuilder.append(leadingThinking)
                                                }
                                                answerBuilder.clear()
                                            }
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
                                        }
                                        toolCallChunk != null -> {
                                            toolCallInProgress.set(true)
                                            Log.d(TAG, "Tool call detected: $toolCallChunk")
                                            val toolNames = toolCallChunk.split(",").map { it.trim() }
                                            for (toolName in toolNames) {
                                                viewModelScope.launch {
                                                    _toolCallState.value = ToolCallState.Executing(
                                                        toolName = toolName,
                                                        elapsedMs = System.currentTimeMillis() - lastChunkAt.get()
                                                    )
                                                    // 画像生成以外のツール実行時は、前回の画像生成進捗をクリア
                                                    if (!toolName.equals("generate_image", ignoreCase = true)) {
                                                        _imageGenProgress.value = null
                                                    }
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
                                        }
                                        toolResultChunk != null -> {
                                            toolCallInProgress.set(false)
                                            Log.d(TAG, "Tool result received: $toolResultChunk")
                                            val parts = toolResultChunk.split(":", limit = 2)
                                            if (parts.size >= 2) {
                                                val toolName = parts[0].trim()
                                                val status = parts[1].trim()
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
                                                    else -> "⏳ $toolName: $status"
                                                }
                                                _uiMessage.emit(resultMsg)
                                                Log.d(TAG, "Tool execution completed: $toolName status=$status")
                                            }
                                        }
                                        toolResults != null -> {
                                            toolCallInProgress.set(false)
                                            if (toolResults != "[]") {
                                                toolResultsJson = toolResults
                                            }
                                            Log.d(TAG, "Tool results JSON received: length=${toolResults.length}")
                                        }
                                        tpsValue != null -> {
                                            _currentTps.value = tpsValue
                                        }
                                        else -> {
                                            val executedToolsList = InferenceStreamProtocol.decodeExecutedToolsList(seg)
                                            if (executedToolsList != null) {
                                                Log.d(TAG, "Executed tools list: $executedToolsList")
                                                if (executedToolsList.isNotEmpty()) {
                                                    val toolsDisplay = executedToolsList.joinToString(", ")
                                                    val toolListMsg = "🔧 実行ツール: $toolsDisplay"
                                                    _uiMessage.emit(toolListMsg)
                                                }
                                            } else if (seg.isNotEmpty()) {
                                                if (_toolCallState.value is ToolCallState.Result) {
                                                    _toolCallState.value = ToolCallState.Responding
                                                }
                                                val currentContent = answerBuilder.toString()
                                                if (BuildConfig.DEBUG) {
                                                    Log.d(TAG, "RAW_CHUNK: length=${seg.length} content='${seg.take(100)}'")
                                                }
                                                val merged = mergeStreamingChunk(currentContent, seg)
                                                if (merged != currentContent && merged.length >= currentContent.length) {
                                                    answerBuilder.clear()
                                                    answerBuilder.append(merged)
                                                    // Bug fix(#6): 以前は seg 全体を estimateOutputTokens に渡していたため、
                                                    // LiteRT から「累積テキスト」が送られたケースで token 数が過大計上されていた。
                                                    // merged と currentContent の実際の差分文字列を使ってトークン数を加算し、
                                                    // TPS 表示を実態に一致させる。
                                                    val deltaText = merged.substring(currentContent.length)
                                                    tokenCount += TextTokenEstimator.estimateOutputTokens(deltaText)
                                                    if (tokenCount >= 10f && firstOutputAtMs != null) {
                                                        val elapsed = SystemClock.elapsedRealtime() - firstOutputAtMs
                                                        if (elapsed > 0) {
                                                            _currentTps.value = (tokenCount * 1000f) / elapsed
                                                        }
                                                    }
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(
                                                            TAG,
                                                            "Chunk merged: ${currentContent.length} -> ${merged.length} chars (added ${merged.length - currentContent.length} chars)"
                                                        )
                                                    }
                                                    if (merged.length - currentContent.length != seg.length) {
                                                        Log.w(TAG, "⚠ OVERLAP DETECTED: chunk=${seg.length} chars, but added only ${merged.length - currentContent.length} chars")
                                                    }
                                                } else if (merged.length < currentContent.length) {
                                                    Log.w(TAG, "❌ Chunk merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping merge")
                                                    if (BuildConfig.DEBUG) {
                                                        Log.w(TAG, "  original chunk: '${seg.take(80)}'")
                                                        Log.w(TAG, "  current: '${currentContent.take(80)}'")
                                                        Log.w(TAG, "  merged: '${merged.take(80)}'")
                                                    }
                                                } else if (merged == currentContent) {
                                                    if (BuildConfig.DEBUG) {
                                                        Log.d(TAG, "DUPLICATE_CHUNK: skipped (already present)")
                                                    }
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
                                        // With the `<think>` prefill seeded in answerBuilder, the
                                        // parser can detect thinking boundaries natively. We no
                                        // longer need `treatUnmarkedInputAsThinking`, which caused
                                        // unmarked answers to be misclassified as thinking.
                                        val parsedStream =
                                            Gemma4ThinkingParser.parseStreaming(
                                                rawInput = answerBuilder.toString(),
                                                treatUnmarkedInputAsThinking = false
                                            )
                                        // Instant / Thinking OFF 中でも、モデルが実際に <think> を吐いた場合は
                                        // それを捨てずに UI へ表示する。本文側は従来どおり visible answer のみを使う。
                                        val extractedThinking = parsedStream.thinking?.let {
                                            Gemma4ThinkingParser.sanitizeVisibleText(it)
                                        }?.ifBlank { null }
                                        if (config.enableThinking) {
                                            contentForUi =
                                                sanitizeAssistantOutputForModel(
                                                    engineModelName = engineModelName,
                                                    text = parsedStream.answer
                                                )
                                            thinkingForUi = extractedThinking
                                        } else {
                                            // Thinking OFF 中は本文から <think> ブロックだけ除去し、
                                            // もしモデルが思考を漏らしたら disclosure 側へそのまま載せる。
                                            val rawNoThink = stripThinkSectionsForDisplay(answerBuilder.toString())
                                            contentForUi =
                                                sanitizeAssistantOutputForModel(
                                                    engineModelName = engineModelName,
                                                    text = Gemma4ThinkingParser.sanitizeVisibleText(rawNoThink)
                                                )
                                            thinkingForUi = extractedThinking
                                        }
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
                                                (finalFromModelGlobal != null ||
                                                    isFirstVisibleContent ||
                                                    isFirstThinkingPersist ||
                                                    now - lastPersistAt >= persistInterval)
                                        }
                                    if (shouldPersistToDb) {
                                        messageRepository.updateMessageContent(
                                            messageId = id,
                                            content = contentForUi,
                                            isStreaming = finalFromModelGlobal == null,
                                            thinkingContent = thinkingForUi,
                                            toolResultsJson = if (finalFromModelGlobal != null) toolResultsJson else null
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
                            memoryWatchJob.cancel()
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
                            _uiMessage.emit("⏱️ 応答が長時間途切れました。表示された分まで保存しました。")
                        }
                    }
                    collectionError is GenerationWallTimeoutException -> {
                        Log.w(TAG, "Generation wall timeout; finalizing partial", collectionError)
                        streamAbortNote =
                            "\n\n（生成時間の上限に達したため、ここで打ち切りました）"
                        withContext(Dispatchers.Main) {
                            _uiMessage.emit("⏱️ 生成時間が上限に達しました。表示された分まで保存しました。")
                        }
                    }
                    collectionError is UserStopCancellationException -> {
                        collectionCancelledByUser = true
                        Log.d(TAG, "Flow collection was cancelled by user stop")
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
                val rawAnswer = if (config.enableThinking) {
                    answerBuilder.toString()
                } else {
                    // Thinking OFF 中も本文には visible answer だけを残す。
                    stripThinkSectionsForDisplay(answerBuilder.toString())
                }
                completeResponse =
                    sanitizeAssistantOutputForModel(
                        engineModelName = engineModelName,
                        text = Gemma4ThinkingParser.sanitizeVisibleText(rawAnswer)
                    )
                finalThinking =
                    Gemma4ThinkingParser.sanitizeVisibleText(thinkingBuilder.toString()).ifBlank { null }
            } else {
                val sanitizedAnswer =
                    Gemma4ThinkingParser.sanitizeVisibleText(answerBuilder.toString())
                // See the comment near answerBuilder initialization: with the `<think>` prefill
                // applied, raw text without tags is always a real answer, never thinking.
                val finalParsed = Gemma4ThinkingParser.parse(
                    rawInput = answerBuilder.toString(),
                    treatUnmarkedInputAsThinking = false
                )
                if (!config.enableThinking) {
                    // Instant / Thinking OFF 中でも、漏れ出た <think> は本文へ混ぜずに別表示する。
                    val visibleOnly = stripThinkSectionsForDisplay(answerBuilder.toString())
                    completeResponse =
                        sanitizeAssistantOutputForModel(
                            engineModelName = engineModelName,
                            text = Gemma4ThinkingParser.sanitizeVisibleText(visibleOnly)
                                .ifBlank { sanitizedAnswer.ifBlank { finalParsed.answer } }
                                .ifBlank { lastPersistedContent }
                        )
                    val parsedThinkingSanitized = finalParsed.thinking?.let {
                        Gemma4ThinkingParser.sanitizeVisibleText(it)
                    }
                    finalThinking = when {
                        parsedThinkingSanitized.isNullOrBlank() -> null
                        parsedThinkingSanitized == completeResponse -> null
                        else -> parsedThinkingSanitized
                    }
                } else {
                    completeResponse =
                        sanitizeAssistantOutputForModel(
                            engineModelName = engineModelName,
                            text = sanitizedAnswer.ifBlank { finalParsed.answer }
                                .ifBlank { lastPersistedContent }
                        )
                    // Guard against the duplicate-payload bug: when the model never emitted `</think>`
                    // but produced a real answer, the parser may return both `thinking` and `answer`
                    // pointing to the same text (because the prefilled `<think>` was never closed).
                    // In that case we treat the model as having skipped thinking and keep only the
                    // visible answer, otherwise the UI shows the answer twice (once in the Thinking
                    // disclosure and once as the final message).
                    val parsedThinking = finalParsed.thinking
                    val parsedThinkingSanitized = parsedThinking?.let {
                        Gemma4ThinkingParser.sanitizeVisibleText(it)
                    }
                    finalThinking = when {
                        parsedThinkingSanitized.isNullOrBlank() -> null
                        parsedThinkingSanitized == completeResponse -> null
                        else -> parsedThinkingSanitized
                    }
                }
            }
            val note = streamAbortNote
            val stoppedWithoutPayload =
                collectionCancelledByUser && completeResponse.isEmpty() && finalThinking.isNullOrEmpty()
            val stoppedDuringThinkingOnly =
                collectionCancelledByUser && completeResponse.isEmpty() && !finalThinking.isNullOrEmpty()
            val contentToSave =
                when {
                    stoppedWithoutPayload -> ""  // 空の場合は空文字列を保存（後でフォールバックメッセージに置換）
                    stoppedDuringThinkingOnly -> appContext.getString(R.string.assistant_no_response)
                    note == null -> completeResponse
                    completeResponse.isNotEmpty() -> completeResponse + note
                    else -> note.trim()
                }

            // ★ ユーザー停止時はツール実行結果カードとして保存
            val finalToolResultsJson =
                if (collectionCancelledByUser) withUserStopCard(toolResultsJson) else toolResultsJson

            val hasPayload =
                contentToSave.isNotEmpty() || !finalThinking.isNullOrEmpty()

            Log.d(TAG, "generateAIResponse finalization: hasPayload=$hasPayload, activeStreamingMessageId=$activeStreamingMessageId, completeResponse.len=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}")

            val generationTimeMs = firstOutputAtMs?.let { first ->
                val end = generationEndAtMs ?: SystemClock.elapsedRealtime()
                (end - first).coerceAtLeast(0L)
            }
            val tps = if (generationTimeMs != null && generationTimeMs > 0L) {
                val tokensAfterFirst = if (isGgufEngineModel(engineModelName)) {
                    val nativeTokens = manager.getLastGenerationTokenCount()
                    (nativeTokens?.minus(1f))?.coerceAtLeast(0f)
                        ?: (TextTokenEstimator.estimateOutputTokens(completeResponse) - 1f).coerceAtLeast(0f)
                } else {
                    (TextTokenEstimator.estimateOutputTokens(completeResponse) - 1f).coerceAtLeast(0f)
                }
                if (tokensAfterFirst > 0f) {
                    tokensAfterFirst * 1000f / generationTimeMs
                } else {
                    null
                }
            } else {
                null
            }

            Log.d(TAG, "Inference collection completed: hasPayload=$hasPayload, completeResponse.length=${completeResponse.length}, finalThinking=${!finalThinking.isNullOrEmpty()}, generationTimeMs=$generationTimeMs, tps=$tps")

            val finalizationContext =
                if (collectionCancelledByUser) Dispatchers.IO + NonCancellable else Dispatchers.IO

            if (hasPayload) {
                withContext(finalizationContext) {
                    Log.d(TAG, "Updating message content with final response")
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = contentToSave,
                        isStreaming = false,
                        thinkingContent = finalThinking,
                        toolResultsJson = finalToolResultsJson,
                        generationTps = tps,
                        generationTimeMs = generationTimeMs
                    )
                    Log.d(TAG, "Message content update complete")
                    if (contentToSave.isNotEmpty() && !stoppedWithoutPayload) {
                        Log.d(TAG, "Generating session title")
                        maybeGenerateSessionTitle(sessionId, userMessage, contentToSave)
                        Log.d(TAG, "Session title generation complete")
                    }
                    syncSessionTitleFromDb(sessionId)
                    Log.d(TAG, "Session title sync complete")
                }
                if (!stoppedWithoutPayload) {
                    enqueueMemoryExtraction(sessionId)
                    Log.d(TAG, "Memory extraction enqueued")
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "AI response saved to database: ${completeResponse.take(50)}...")
                }
            } else {
                Log.w(TAG, "No payload generated, saving default message")
                val emptyExplanation = messageForEmptyInferencePayload(hasMediaInput, engineModelName)
                if (!collectionCancelledByUser) {
                    withContext(Dispatchers.Main) {
                        _modelErrorDialogMessage.value = formatModelErrorDialogMessage(
                            title = appContext.getString(R.string.assistant_error_empty_output_title),
                            message = emptyExplanation,
                            details = if (engineModelName.isNotBlank()) "モデル: $engineModelName" else null
                        )
                    }
                }
                withContext(finalizationContext) {
                    messageRepository.updateMessageContent(
                        messageId = activeStreamingMessageId,
                        content = emptyExplanation,
                        isStreaming = false,
                        thinkingContent = null
                    )
                    Log.d(TAG, "Empty payload message saved to DB")
                    syncSessionTitleFromDb(sessionId)
                    Log.d(TAG, "Session title sync complete (empty payload)")
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
                    withContext(Dispatchers.IO + NonCancellable) {
                        // ★ 既存の内容を取得して保存（上書きしない）
                        val current = messageRepository.getMessageById(id)
                        val existingContent = current?.content?.trim() ?: ""
                        // ★ 停止時の「途中 assistant 出力」を表示上閉じるための終端補完。
                        //   コードフェンスの未閉鎖によるレンダリング崩れを防ぐ。
                        val finalContent = if (existingContent.isNotEmpty()) {
                            closePartialAssistantContent(existingContent)
                        } else {
                            ""  // 空の場合は空文字列（後でフォールバックメッセージに置換）
                        }
                        // ★ thinking ブロックも未閉鎖のまま残っていたら、閉じタグを補う。
                        val finalThinking = closePartialThinking(current?.thinkingContent)

                        val updatedToolResultsJson = withUserStopCard(current?.toolResultsJson)

                        messageRepository.updateMessageContent(
                            messageId = id,
                            content = finalContent,
                            isStreaming = false,
                            thinkingContent = finalThinking,
                            toolResultsJson = updatedToolResultsJson
                        )
                    }
                }
                // ★ 停止直後はネイティブ KV に途中トークンが残っているため、
                //   次回推論開始前に必ずクリアさせる。実際のクリアは stopGenerationInternal
                //   と、次回 inference へのエントリで二重に守られる。
                runCatching { requireModelManager().requestForceClearBeforeNextInference() }
                    .onFailure { Log.w(TAG, "requestForceClearBeforeNextInference (on cancel) failed", it) }
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
            Log.d(TAG, "generateAIResponse finally entered")
            // Ensure cleanup runs even if the coroutine job was cancelled.
            withContext(NonCancellable) {
                streamingAssistantMessageIdForTools = null
                // Safety fallback: if the streaming message still exists and is still marked as streaming,
                // clear the flag so the UI does not stay stuck in "生成中".
                if (streamingMessageId != null) {
                    try {
                        withContext(Dispatchers.IO) {
                            val current = messageRepository.getMessageById(streamingMessageId)
                            if (current?.isStreaming == true) {
                                Log.w(
                                    TAG,
                                    "generateAIResponse finally: message $streamingMessageId still streaming after completion, clearing flag"
                                )
                                messageRepository.updateMessageContent(
                                    messageId = streamingMessageId,
                                    content = current.content.ifBlank {
                                        messageForEmptyInferencePayload(
                                            currentHasMediaInput,
                                            currentEngineModelName ?: ""
                                        )
                                    },
                                    isStreaming = false,
                                    thinkingContent = current.thinkingContent
                                )
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to clear streaming flag on message $streamingMessageId", t)
                    }
                }

                // Bug fix(#5): 過去ターンの画像をロードした Bitmap を recycle する。
                pastHistoryBitmapsToRecycle.forEach { bmp ->
                    if (!bmp.isRecycled) {
                        runCatching { bmp.recycle() }
                    }
                }
                pastHistoryBitmapsToRecycle.clear()

                // Gallery パターン: 全パスで _isLoading を false にする
                Log.d(TAG, "Generation concluded, setting isLoading=false")
                _isLoading.value = false

                // Tool Call State マシンを Done に設定
                _toolCallState.value = ToolCallState.Done
                _currentTps.value = null

                // Phase 11: 全体のロード時間をログ出力
                val aiTotalMs = System.currentTimeMillis() - aiStartMs
                Log.d(TAG, "generateAIResponse TOTAL_DURATION: ${aiTotalMs}ms (model load, inference, and all processing)")

                // Release WakeLock when generation completes
                releaseScreenWakeLock()
            }
        }
    }

    /**
     * ★ ユーザー停止 / 例外で生成が途中で折れた場合に、partial assistant 出力を
     *   表示上「安全に閉じる」ための軽量な終端補完。
     *
     * モデル言語の終端トークン (`<end_of_turn>` / `<|im_end|>` など) は
     * チャットテンプレート侧で扱うため、ここでは保存される本文に
     * そのまま追記しない（追記するとコピーや読み上げにノイズとして出てしまう）。
     *
     * 代わりに、UI / Markdown レンダラーにとって未閉鎖のままだと
     * 表示が壊れる「途中のコードフェンス」を軽く閉じるだけに留める。
     */
    private fun closePartialAssistantContent(content: String): String {
        if (content.isBlank()) return content
        var result = content
        // コードフェンスが奇数個 = 未閉鎖 → 閉じる。
        val codeFenceCount = Regex("```").findAll(result).count()
        if (codeFenceCount % 2 == 1) {
            if (!result.endsWith("\n")) result += "\n"
            result += "```"
        }
        return result
    }

    /**
     * ★ 途中で折れた thinking ブロックの終端補完。
     *
     * `<think>` / `<|think|>` を開いたまま \</think> を出さずに生成が
     * 折れると、後続の MessageAdapter 側で thinking トークン除去が不完全に
     * なり、本文と thinking が交ざって見えるケースがある。そのため
     * 未閉じのタグを検出したら末尾に閉じタグを付ける。
     */
    private fun closePartialThinking(thinking: String?): String? {
        if (thinking.isNullOrBlank()) return thinking
        var result = thinking
        // <think> / </think>
        val openCount = Regex("(?i)<think>").findAll(result).count()
        val closeCount = Regex("(?i)</think>").findAll(result).count()
        if (openCount > closeCount) {
            result += "</think>"
        }
        // <|think|> / <|/think|>
        val openCount2 = Regex("<\\|think\\|>").findAll(result).count()
        val closeCount2 = Regex("<\\|/think\\|>").findAll(result).count()
        if (openCount2 > closeCount2) {
            result += "<|/think|>"
        }
        return result
    }

    private fun withUserStopCard(toolResultsJson: String?): String {
        val existingCards = if (!toolResultsJson.isNullOrBlank() && toolResultsJson != "[]") {
            ToolResultCard.listFromJsonArray(toolResultsJson)
        } else {
            emptyList()
        }
        if (existingCards.any { it.toolName == "user_stop" }) {
            return ToolResultCard.listToJsonArray(existingCards)
        }

        val stopCard = ToolResultCard(
            toolName = "user_stop",
            success = true,
            payload = mapOf(
                "message" to kotlinx.serialization.json.JsonPrimitive("ユーザーが生成を停止しました"),
                "icon" to kotlinx.serialization.json.JsonPrimitive("⏸️")
            )
        )
        return ToolResultCard.listToJsonArray(existingCards + stopCard)
    }

    private suspend fun awaitImageGenerationConfirmation(initialPrompt: String): String? =
        coroutineScope {
            withTimeoutOrNull(120_000L) {  // 120 秒タイムアウトに延長
                suspendCancellableCoroutine { cont ->
                    imageGenConfirmCont = cont
                    _confirmationRequest.value = initialPrompt
                    Log.d(TAG, "awaitImageGenerationConfirmation: Waiting for user confirmation. Prompt: ${initialPrompt.take(50)}...")
                    cont.invokeOnCancellation {
                        Log.d(TAG, "awaitImageGenerationConfirmation: Coroutine cancelled")
                        imageGenConfirmCont = null
                        _confirmationRequest.value = null
                    }
                }
            }.also { result ->
                // タイムアウトまたは戻り値に関わらず、状態をクリア
                Log.d(TAG, "awaitImageGenerationConfirmation: Completed with result: ${if (result == null) "null/cancelled" else "success (prompt=${result.take(30)}...)"}")
                _confirmationRequest.value = null
                imageGenConfirmCont = null
            }
        }

    fun onConfirmGenerateImage(editedPrompt: String) {
        Log.d(TAG, "onConfirmGenerateImage: Called with prompt: ${editedPrompt.take(50)}...")
        _confirmationRequest.value = null
        val c = imageGenConfirmCont
        imageGenConfirmCont = null
        if (c != null) {
            Log.d(TAG, "onConfirmGenerateImage: Resuming continuation")
            c.resume(editedPrompt.trim())
        } else {
            Log.w(TAG, "onConfirmGenerateImage: No continuation to resume (already cleared?)")
        }
    }

    fun onCancelGenerateImage() {
        Log.d(TAG, "onCancelGenerateImage: Called")
        _confirmationRequest.value = null
        val c = imageGenConfirmCont
        imageGenConfirmCont = null
        if (c != null) {
            Log.d(TAG, "onCancelGenerateImage: Resuming continuation with null")
            c.resume(null)
        } else {
            Log.w(TAG, "onCancelGenerateImage: No continuation to resume (already cleared?)")
        }
    }

    private suspend fun reloadChatModelAfterSd(manager: ModelManager) {
        val selectedModel = getActiveSelectedModel()
        try {
            // SD解放を確実に実行
            EngineManager.releaseSdKeepNone()
            Log.d(TAG, "reloadChatModelAfterSd: SD engine released")

            // メモリ安定化のため少し待機
            delay(500L)

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
                handleModelLoadIssue(
                    selectedModel = selectedModel,
                    error = result.exceptionOrNull(),
                    title = "モデル再ロードエラー",
                    message = "LLMモデルの再ロードに失敗しました。設定画面で再ダウンロードしてください。"
                )
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "reloadChatModelAfterSd failed", e)
            handleModelLoadIssue(
                selectedModel = selectedModel,
                error = e,
                title = "モデル再ロードエラー",
                message = "LLMモデルの再ロードに失敗しました。設定画面で再ダウンロードしてください。"
            )
            return
        }
    }

    private fun findAvailableSdModelPath(): String {
        // First try the saved preference path
        val savedPath = PreferencesHelper.getSdModelPath(appContext).trim()
        if (savedPath.isNotEmpty() && File(savedPath).isDirectory && isProbableSdModelDir(File(savedPath))) {
            return savedPath
        }

        // Search in standard directories (same logic as ImageGenViewModel.loadAvailableModels)
        val models = mutableListOf<String>()
        
        // sd_models directory
        val sdModelsDir = File(appContext.filesDir, "sd_models")
        sdModelsDir.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }
        
        // App external files directory
        val appDir = appContext.getExternalFilesDir(null)
        appDir?.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }
        
        // Imported models directory
        val importedDir = File(appContext.filesDir, "models/imported")
        importedDir.listFiles()?.forEach { file ->
            if (isProbableSdModelDir(file)) {
                models.add(file.absolutePath)
            }
        }
        
        // Return first found model, or empty string if none
        return models.firstOrNull() ?: ""
    }

    private fun isProbableSdModelDir(file: File): Boolean {
        return SdModelLayout.isUsableModelDir(file) || SdModelLayout.isLegacyQnnDir(file)
    }

    private suspend fun invokeGenerateImageFromTool(toolCall: ToolCall): ToolExecutionResult {
        val prompt = toolCall.arguments["prompt"]?.toString()?.trim().orEmpty()
        if (prompt.isEmpty()) {
            // UI通知：ツール実行開始
            _imageGenProgress.value = null
            viewModelScope.launch {
                _toolCallState.value = ToolCallState.Executing(
                    toolName = "generate_image",
                    elapsedMs = 0
                )
            }
            _uiMessage.emit("🔧 generate_image を実行中...")
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "missing_prompt")
            )
        }
        val neg = (
            toolCall.arguments["negativePrompt"]
                ?: toolCall.arguments["negative_prompt"]
        )?.toString()?.trim().orEmpty()
        var w = (toolCall.arguments["width"] as? Number)?.toInt() ?: 256
        var h = (toolCall.arguments["height"] as? Number)?.toInt() ?: 256
        val allowed = listOf(256, 512, 768)
        w = allowed.minByOrNull { kotlin.math.abs(it - w) } ?: 512
        h = allowed.minByOrNull { kotlin.math.abs(it - h) } ?: 512
        val steps = (toolCall.arguments["steps"] as? Number)?.toInt()?.coerceIn(1, 50) ?: PreferencesHelper.getSdSteps(appContext)
        val cfg = (toolCall.arguments["cfg"] as? Number)?.toFloat()
            ?: (toolCall.arguments["cfg_scale"] as? Number)?.toFloat()
            ?: PreferencesHelper.getSdCfg(appContext)
        val seed = (toolCall.arguments["seed"] as? Number)?.toLong() ?: -1L
        val scheduler = SdScheduler.fromId(toolCall.arguments["scheduler"] as? String)

        val edited = awaitImageGenerationConfirmation(prompt)
        if (edited == null) {
            // UI通知：キャンセル
            _imageGenProgress.value = null
            viewModelScope.launch {
                _toolCallState.value = ToolCallState.Result(
                    toolName = "generate_image",
                    status = "cancelled",
                    resultMessage = "キャンセルしました"
                )
            }
            _uiMessage.emit("❌ generate_image: キャンセルしました")
            return ToolExecutionResult(
                success = true,
                payload = mapOf("success" to true, "message" to "キャンセルしました")
            )
        }

        // 初期状態を早期にセットして、チャット画面へ進捗表示を開始する
        viewModelScope.launch {
            _toolCallState.value = ToolCallState.Executing(
                toolName = "generate_image",
                elapsedMs = SystemClock.elapsedRealtime()
            )
            _imageGenProgress.value = Pair(0, steps)
        }

        val sdPath = findAvailableSdModelPath()
        if (sdPath.isEmpty()) {
            // UI通知：失敗
            _imageGenProgress.value = null
            viewModelScope.launch {
                _toolCallState.value = ToolCallState.Result(
                    toolName = "generate_image",
                    status = "error",
                    resultMessage = "sd_model_path_missing"
                )
            }
            _uiMessage.emit("❌ generate_image: SDモデルパスが見つかりません")
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "sd_model_path_missing")
            )
        }

        val activeTurnJob = generationControlMutex.withLock { generationJob }
        val targetMessageId = streamingAssistantMessageIdForTools
        queueGenerateImageFromTool(
            activeTurnJob = activeTurnJob,
            targetMessageId = targetMessageId,
            prompt = edited,
            negativePrompt = neg,
            width = w,
            height = h,
            steps = steps,
            cfg = cfg,
            seed = seed,
            scheduler = scheduler,
            sdPath = sdPath
        )
        _uiMessage.emit("🎨 generate_image: 画像生成を開始します")
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "success" to true,
                "message" to "画像生成を開始しました。完了後にユーザーの画面へ表示されます。",
                "prompt" to edited
            )
        )
    }

    private fun queueGenerateImageFromTool(
        activeTurnJob: Job?,
        targetMessageId: Long?,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: SdScheduler,
        sdPath: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (activeTurnJob != null) {
                val turnFinished = withTimeoutOrNull(15_000L) {
                    activeTurnJob.join()
                    true
                } == true
                if (!turnFinished && activeTurnJob.isActive) {
                    Log.w(TAG, "queueGenerateImageFromTool: active LLM turn did not finish; aborting SD generation")
                    _imageGenProgress.value = null
                    _toolCallState.value = ToolCallState.Result(
                        toolName = "generate_image",
                        status = "error",
                        resultMessage = "LLM応答終了待ちタイムアウト"
                    )
                    _uiMessage.emit("❌ generate_image: LLM応答の終了待ちがタイムアウトしました")
                    return@launch
                }
            }
            performGenerateImageFromTool(
                targetMessageId = targetMessageId,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfg = cfg,
                seed = seed,
                scheduler = scheduler,
                sdPath = sdPath
            )
        }
    }

    private suspend fun performGenerateImageFromTool(
        targetMessageId: Long?,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        scheduler: SdScheduler,
        sdPath: String
    ) {
        if (BuildConfig.SAFETY_IMAGE_GUARD_ENABLED &&
            !ModelDownloadWorker.awaitSafetyModelReady(appContext)) {
            Log.e(TAG, "performGenerateImageFromTool: Safety model download failed or timeout")
            _imageGenProgress.value = null
            _toolCallState.value = ToolCallState.Result(
                toolName = "generate_image",
                status = "error",
                resultMessage = "セーフティモデルDL失敗"
            )
            _uiMessage.emit("❌ generate_image: セーフティモデルのダウンロードに失敗しました")
            return
        }

        val manager = requireModelManager()
        Log.d(TAG, "performGenerateImageFromTool: requireModelManager succeeded")

        Log.d(TAG, "performGenerateImageFromTool: Unloading LLM before SD")
        val unloadResult = try {
            withTimeoutOrNull(20_000L) {
                manager.unloadModel(skipCancelInference = false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "performGenerateImageFromTool: unloadModel threw exception", e)
            Result.failure(e)
        }

        if (unloadResult == null || unloadResult.isFailure) {
            Log.w(TAG, "performGenerateImageFromTool: unloadModel failed; aborting SD generation")
            _imageGenProgress.value = null
            _toolCallState.value = ToolCallState.Result(
                toolName = "generate_image",
                status = "error",
                resultMessage = "LLMモデル解放失敗"
            )
            _uiMessage.emit("❌ generate_image: LLMモデルを解放できませんでした")
            return
        }

        EngineManager.releaseSdKeepNone()
        System.gc()
        delay(800L)

        Log.d(TAG, "performGenerateImageFromTool: Starting image generation server...")

        try {
            val localDream = EngineManager.acquireLocalDream(appContext, sdPath, "auto")

            Log.d(TAG, "performGenerateImageFromTool: LocalDream acquired successfully")
            Log.d(TAG, "performGenerateImageFromTool: Model loaded successfully, starting image generation")
            
            // UI通知：実行中
            viewModelScope.launch {
                _toolCallState.value = ToolCallState.Executing(
                    toolName = "generate_image",
                    elapsedMs = SystemClock.elapsedRealtime()
                )
            }
            _uiMessage.emit("🎨 画像生成中...")
            val promptPreview = prompt.trim().replace("\n", " ").let { if (it.length <= 48) it else it.take(48) + "…" }
            ImageGenerationNotificationManager.showChatToolProgress(
                appContext,
                step = 0,
                totalSteps = steps,
                promptPreview = promptPreview
            )

            val bmp = localDream.generateImage(
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfg = cfg,
                seed = seed,
                scheduler = scheduler,
                onProgress = { step, totalSteps, _ ->
                    _imageGenProgress.value = Pair(step, totalSteps)
                    _toolCallState.value = ToolCallState.Executing(
                        toolName = "generate_image",
                        elapsedMs = SystemClock.elapsedRealtime()
                    )
                    ImageGenerationNotificationManager.showChatToolProgress(
                        appContext,
                        step = step.coerceAtMost(totalSteps),
                        totalSteps = totalSteps,
                        promptPreview = promptPreview
                    )
                    Log.d(TAG, "performGenerateImageFromTool: Progress $step/$totalSteps")
                }
            )
            // 完了後は progress を null に戻す
            _imageGenProgress.value = null

            Log.d(TAG, "performGenerateImageFromTool: Cleaning up SD (bmp=${bmp != null})")
            // SD完全解放を確実に実行
            EngineManager.releaseSdKeepNone()
            delay(500L)  // メモリ安定化待機

            if (bmp == null) {
                Log.w(TAG, "performGenerateImageFromTool: Image generation returned null")
                // UI通知：失敗
                _toolCallState.value = ToolCallState.Result(
                    toolName = "generate_image",
                    status = "error",
                    resultMessage = "生成失敗"
                )
                _uiMessage.emit("❌ generate_image: 画像生成失敗")
                ImageGenerationNotificationManager.showError(
                    appContext,
                    ImageGenerationNotificationManager.chatToolNotificationId(),
                    "チャット画像生成に失敗しました",
                    promptPreview
                )
                clearImageGenerationStatusSoon()
            } else {
                ImageLibraryStore.save(appContext, bmp, prompt)

                if (targetMessageId != null) {
                    val uri = MessageMediaStore.savePngBitmap(appContext, bmp, "chat_sd_$targetMessageId")
                    if (uri != null) {
                        withContext(Dispatchers.IO) {
                            messageRepository.updateMessageImageWithDescription(targetMessageId, uri, null)
                        }
                    }
                }
                Log.d(TAG, "performGenerateImageFromTool: ✓ Image generated successfully")
                
                // UI通知：成功
                _toolCallState.value = ToolCallState.Result(
                    toolName = "generate_image",
                    status = "success",
                    resultMessage = "画像を生成しました"
                )
                _uiMessage.emit("✅ generate_image: 画像を生成しました")
                ImageGenerationNotificationManager.showCompleted(
                    appContext,
                    ImageGenerationNotificationManager.chatToolNotificationId(),
                    "チャット画像生成が完了しました",
                    promptPreview
                )
                clearImageGenerationStatusSoon()
            }
        } catch (e: Exception) {
            Log.e(TAG, "performGenerateImageFromTool: Exception during SD generation", e)
            // エラー時もSD解放を試みる
            try {
                EngineManager.releaseSdKeepNone()
            } catch (cleanupError: Exception) {
                Log.e(TAG, "performGenerateImageFromTool: Cleanup failed", cleanupError)
            }
            // UI通知：エラー
            _imageGenProgress.value = null
            _toolCallState.value = ToolCallState.Result(
                toolName = "generate_image",
                status = "error",
                resultMessage = e.message ?: "sd_error"
            )
            _uiMessage.emit("❌ generate_image: エラー - ${e.message ?: "不明なエラー"}")
            ImageGenerationNotificationManager.showError(
                appContext,
                ImageGenerationNotificationManager.chatToolNotificationId(),
                "チャット画像生成に失敗しました",
                e.message ?: "不明なエラー"
            )
            clearImageGenerationStatusSoon()

            // LLMへの報告をストリームに流す
            viewModelScope.launch {
                _inferenceStream.emit(InferenceStreamProtocol.encodeToolResultChunk("generate_image", "error"))
            }
        } finally {
            // LLMモデルを再ロード
            try {
                Log.d(TAG, "performGenerateImageFromTool: Reloading LLM in finally")
                reloadChatModelAfterSd(manager)
            } catch (reloadError: Exception) {
                Log.e(TAG, "performGenerateImageFromTool: LLM reload failed in finally", reloadError)
                // UI通知
                withContext(Dispatchers.Main) {
                    _uiMessage.emit("⚠️ LLMモデルの再ロードに失敗しました。チャットを再起動してください。")
                }
            }
        }
    }

    private fun clearImageGenerationStatusSoon() {
        viewModelScope.launch {
            delay(1200L)
            if (_toolCallState.value is ToolCallState.Result) {
                _toolCallState.value = ToolCallState.Done
            }
            _imageGenProgress.value = null
        }
    }

    private suspend fun chatInferenceConfigForModel(model: String): InferenceConfig {
        val base = settingsRepository.getInferenceConfigForModel(model, appContext)
        val disableThinking = _chatSessionDisableThinking.value
        val thinkingEnabledOverride = _chatSessionThinkingEnabledOverride.value
        // ★ Bug fix: 以前は modelSupportsGemmaThinking() でしか override を受け付けないため、
        //   Qwen 系 GGUF などで「このチャットで Thinking: ON」にしても enable_thinking が
        //   反映されず、結果として `/no_think` directive のままユーザーには Thinking 表示だけ
        //   ONになるという矛盾が発生していた。Thinking のオーバーライドはモデル種別に依らず
        //   常に enableThinking を上書きする (チャットテンプレ側で `/think` `/no_think` を
        //   正しく付け替える)。
        val result = when {
            thinkingEnabledOverride -> base.copy(enableThinking = true)
            disableThinking -> base.copy(enableThinking = false)
            else -> base
        }
        Log.d(
            TAG,
            "chatInferenceConfigForModel: model=$model, disableThinking=$disableThinking, overrideEnabled=$thinkingEnabledOverride, enableThinking=${result.enableThinking}"
        )
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

    private suspend fun maybeUpdateSessionTitleFromUserMessage(sessionId: Long, userMessage: String) {
        if (userMessage.isBlank()) return
        val session = sessionRepository.getSessionById(sessionId) ?: return
        if (session.name.trim() != DEFAULT_SESSION_TITLE) return
        val title = buildSessionTitle(userMessage, "")
        if (title.isBlank() || title == DEFAULT_SESSION_TITLE) return
        sessionRepository.updateSessionName(sessionId, title)
        _sessionTitle.value = title
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

    /**
     * 可視本文用に <think>...</think> ブロックだけを取り除く。
     * </think> がまだ来ていない途中のストリームでは <think> 以降を一時的に非表示にし、
     * Thinking 本体は別 UI ブロックで表示する。
     */
    private fun stripThinkSectionsForDisplay(raw: String): String {
        if (raw.isEmpty()) return raw
        var text = raw
        while (true) {
            val start = text.indexOf("<think>")
            if (start < 0) break
            val end = text.indexOf("</think>", start)
            text = if (end >= 0) {
                text.removeRange(start, end + "</think>".length)
            } else {
                // ストリーム途中: <think> 以降をすべてトリムして可視部分だけ返す。
                text.substring(0, start)
            }
        }
        return text.trim()
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
                // GGUF + current turn: embed mtmd default media marker so that
                // native processMedia() finds it inside the user turn instead of
                // appending one at the very end (which would land after the
                // "<|im_start|>assistant\n" prefix and immediately trigger EOS).
                // The marker MUST be "<__media__>" — see mtmd_default_marker()
                // in tools/mtmd/mtmd.cpp. The previous "<image>" string was just
                // plain text from the model's point of view and was silently
                // ignored by mtmd_tokenize().
                isGgufEngine && isCurrentTurn ->
                    List(imageCount) { "<__media__>" }.joinToString(separator = "\n")
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
        // 既に反映済みの重複delta。短い chunk は通常単語にも出るので捨てない。
        if (chunk.length >= 8 && current.endsWith(chunk)) return current
        // 巻き戻った累積全文らしきケースは現状維持。短い prefix chunk は本文中に再登場するので捨てない。
        if (chunk.length >= 32 && chunk.length >= current.length / 2 && current.startsWith(chunk)) {
            return current
        }

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
        // Short overlaps are often just ordinary word/token boundaries
        // ("test" + "time", "し" + "した" etc.). Only trim clear repeated tails.
        val maxCheckSize = minOf(left.length, right.length, 50)
        val minCheckSize = 8
        if (maxCheckSize < minCheckSize) return 0

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
        val rawMessages = messageRepository.getMessagesForSessionOnce(sessionId)
        if (rawMessages.isEmpty()) return ""
        // ★ バリアント選択をプロンプトにも反映させる。
        //   選択されていない assistant バリアントを除外しないと、同じ user ターンに対して
        //   複数の assistant ターンがプロンプトに並んでしまい、LLM にとって奇妙な会話になる。
        val messages = applyVariantSelection(rawMessages, _selectedVariantByParent.value)

        // 画像をコンテキストに含むための デバッグログ
        val messagesWithImagesForLog = messages.filter { it.imageUri != null && it.imageUri.isNotEmpty() }
        if (messagesWithImagesForLog.isNotEmpty()) {
            Log.d(TAG, "PROMPT_BUILD: Found ${messagesWithImagesForLog.size} messages with images: ${messagesWithImagesForLog.map { "${it.id}:${it.role}" }}")
        }

        val isGgufEngine = isGgufEngineModel(engineModelName)
        val memoryBlock = buildRelevantMemoryBlock(messages, sessionId, config.contextWindow)
        // Tool calling should not suppress GGUF thinking directives such as Qwen /think.
        val enableThinkingForPrompt = config.enableThinking
        val fullPrompt = buildPromptFromMessages(
            messages = messages,
            isGgufEngine = isGgufEngine,
            engineModelName = engineModelName,
            enableThinking = enableThinkingForPrompt,
            enableToolCalling = config.enableToolCalling,
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
        val effectiveCompressionEnabled = config.isContextCompressionEnabledForRuntime() && config.backendType != "GPU"

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
                enableToolCalling = config.enableToolCalling,
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
            enableToolCalling = config.enableToolCalling,
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
        MemoryTextEmbedder.initializeAsync(appContext)
        Log.d(TAG, "MEMORY_INJECT: MemoryTextEmbedder initialized (async)")

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
            val messages = messageRepository.getMessagesForSessionOnce(sessionId)
            if (messages.isEmpty()) return@launch

            // チャンクインデックスはメモリ設定に関係なく常に実行
            val chunkRepo = try {
                val db = com.nezumi_ai.data.database.NezumiAiDatabase.getInstance(appContext)
                com.nezumi_ai.data.repository.ChatChunkRepository(db.chatChunkDao(), appContext)
            } catch (e: Exception) {
                Log.w(TAG, "CHUNK_INDEX: failed to get repository", e)
                null
            }
            chunkRepo?.let { repo ->
                messages.filter { it.content.isNotBlank() }.forEach { msg ->
                    try {
                        repo.indexMessage(msg.id, sessionId, msg.content)
                    } catch (e: Exception) {
                        Log.w(TAG, "CHUNK_INDEX: failed for messageId=${msg.id}", e)
                    }
                }
                Log.d(TAG, "CHUNK_INDEX: session=$sessionId indexed ${messages.size} messages")
            }

            // メモリ抽出はメモリ設定が有効な場合のみ
            if (!isMemoryEnabledForCurrentPreset()) return@launch
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
        val config = settingsRepository.getInferenceConfigForModel(selectedModel, appContext)
        val basePrompt = buildPromptFromMessages(
            messages,
            isGgufEngine,
            engineModelName,
            config.enableThinking,
            enableToolCalling = config.enableToolCalling
        )

        // ★ 常に trimPromptToWindow で実際に使用される文字数を計算
        val maxChars = config.contextWindow * TOKEN_TO_CHAR_RATIO
        val basePromptSize = trimPromptToWindow(basePrompt, config.contextWindow).length

        // コンテキスト圧縮が無効な場合、またはGPU使用時は未圧縮のサイズをそのまま返す
        if (!config.isContextCompressionEnabledForRuntime() || config.backendType == "GPU") {
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
                enableThinking = config.enableThinking,
                enableToolCalling = config.enableToolCalling
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
        enableToolCalling: Boolean = false,
        memoryBlock: String? = null
    ): String {
        Log.d(TAG, "buildPromptWithCompressedSummary: memoryBlock=${if (memoryBlock != null) "present (${memoryBlock.length} chars)" else "null"}")
        var systemPrompt = getActiveSystemPrompt()
        val userName = settingsRepository.getUserName()
        if (userName.isNotEmpty()) {
            systemPrompt = "ユーザー名：$userName\n\n$systemPrompt"
        }
        systemPrompt = appendMemoryBlockToSystemPrompt(systemPrompt, memoryBlock)
        if (isGgufEngine && enableToolCalling) {
            systemPrompt = GgufToolPromptBuilder.appendToolDefinitions(appContext, systemPrompt)
        }
        // Tool calling can coexist with thinking directives; do not suppress thinking when tool calling is enabled.
        val enableThinkingForPrompt = enableThinking
        return if (isGgufEngine) {
            PromptBuilder.buildForGguf(
                messages = recentMessages,
                systemPrompt = systemPrompt,
                compressedSummary = compressedSummary,
                format = PromptBuilder.detectGgufFormat(engineModelName),
                enableThinking = enableThinkingForPrompt,
                modelPath = engineModelName,
                sanitizeMessageContent = ::sanitizeMessageContentForPrompt,
                appContext = appContext
            )
        } else {
            PromptBuilder.buildForLiteRt(
                messages = recentMessages,
                systemPrompt = systemPrompt,
                injectGemmaThinkTrigger = enableThinkingForPrompt && settingsRepository.shouldInjectGemmaThinkTrigger(),
                compressedSummary = compressedSummary,
                sanitizeMessageContent = ::sanitizeMessageContentForPrompt,
                appContext = appContext,
                modelPath = engineModelName
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
        enableToolCalling: Boolean = false,
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
        var systemPrompt = appendMemoryBlockToSystemPrompt(getActiveSystemPrompt(), memoryBlock)
        if (isGgufEngine && enableToolCalling) {
            systemPrompt = GgufToolPromptBuilder.appendToolDefinitions(appContext, systemPrompt)
        }

        val sanitizer = makeSanitizer(isGgufEngine, currentTurnMessageId)

        // Tool calling can coexist with thinking directives; do not suppress thinking when tool calling is enabled.
        val enableThinkingForPrompt = enableThinking
        return if (isGgufEngine) {
            PromptBuilder.buildForGguf(
                messages = filteredMessages,
                systemPrompt = systemPrompt,
                format = PromptBuilder.detectGgufFormat(engineModelName),
                enableThinking = enableThinkingForPrompt,
                modelPath = engineModelName,
                sanitizeMessageContent = sanitizer,
                appContext = appContext
            )
        } else {
            PromptBuilder.buildForLiteRt(
                messages = filteredMessages,
                systemPrompt = systemPrompt,
                injectGemmaThinkTrigger = enableThinkingForPrompt && settingsRepository.shouldInjectGemmaThinkTrigger(),
                sanitizeMessageContent = sanitizer,
                appContext = appContext,
                modelPath = engineModelName
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

        // ★★★ 重要なショートカット:
        //   モデルが既にロード済みで現コンフィグと互換なら、一切のオーバーレイを出さずに成功を返す。
        //   以前はこのケースでも _isModelLoading = true を一旦立ててしまい、入り口側で先に立てたタイマーと不整合を起こし
        //   「ロード不要なのにグルグルが永遠に続く」バグの主な原因だった。
        //   ここで先手を打ってリターンすれば、呼び元は瞬時に推論以降のフェーズに進める。
        if (isModelAlreadyLoaded) {
            Log.d(TAG, "loadModelWithOverlay: SHORT_CIRCUIT model=$model already loaded, skip overlay entirely")
            // 万一呼び元が先にインジケーターを立てていた場合に備えてもクリアしておく。
            if (_isModelLoading.value || modelLoadingTickerJob?.isActive == true) {
                clearModelLoadingIndicator()
            }
            return Result.success(Unit)
        }

        _isModelLoading.value = true
        // ★ モデル名やフェーズごとのラベルは以前「[Gemma4-2B] エンジンを初期化中...」などバラバラだったのを
        //   全て「モデル準備中 · <フェーズ> (n秒)」に統一する。タイマーを 1秒毎に回して
        //   進捗ラベルを自動更新。
        startModelLoadingIndicator()
        return try {
            val displayModel = when (model.uppercase()) {
                "GEMMA4-2B" -> "Gemma4-2B"
                "GEMMA4-4B" -> "Gemma4-4B"
                else -> "カスタム"
            }

            // Phase 14: モデルロード前にメモリ確認
            updateModelLoadingPhase("メモリ確認")
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
                    // ★ クリーンアップ強化: 以前は _isModelLoading = false だけだったため
                    //   _modelLoadingStatus の旧ラベルやタイマージョブが残って
                    //   「モデル準備中」が終わらないのバグの一因だった。
                    clearModelLoadingIndicator()
                    return Result.failure(RuntimeException("CPU_COMPAT_WARNING_SHOWN"))
                }
            }

            // 詳細なメモリ情報をログ出力
            val detailedMemInfo = MemoryObserver.getDetailedMemoryInfo(appContext)
            Log.d(TAG, "PRE_LOAD_MEMORY:\n$detailedMemInfo")

            var memoryStatus = MemoryObserver.getMemoryStatus(appContext)
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
                    memoryStatus = MemoryObserver.getMemoryStatus(appContext)
                    Log.d(TAG, "loadModelWithOverlay: MEMORY_STATUS after pre-warning unload level=${memoryStatus.level} used=${memoryStatus.usedMB}MB max=${memoryStatus.maxMB}MB percent=${memoryStatus.usedPercent}% device_low_memory=${memoryStatus.isLowMemory}")
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
                        updateModelLoadingPhase("メモリ確認")

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
                            // ★ 以前は _isModelLoading だけ false にしてラベルを残していたが、
                            //   メモリ警告ダイアログを閉じた後に UI で「モデル準備中」が見え施けていた
                            //   不具合を防ぐため、タイマー・フェーズも一旦クリアする。
                            clearModelLoadingIndicator()
                            return Result.failure(RuntimeException("MEMORY_WARNING_SHOWN"))
                        }
                    }
                }
            }

            // effectiveSkipMemoryWarning=true の場合はメモリ警告をスキップしてロード続行
            Log.d(TAG, "loadModelWithOverlay: Memory check passed for model=$model")

            // ★ 以前「[Gemma4-2B] エンジンを初期化中...」だったのを「エンジン初期化」フェーズに統一。
            updateModelLoadingPhase(if (isModelAlreadyLoaded) "重みロード" else "エンジン初期化")
            Log.d(TAG, "loadModelWithOverlay: model=$model, engineName=$engineModelName, enableThinking=${config.enableThinking}, backend=${config.backendType}, contextWindow=${config.contextWindow}")

            // エンジンの loadModel は進捗コールバックを提供していないため、ロード中はフェーズを「重みロード」に切り替えて
            //   タイマーの経過秒数だけで進捗感を見せる。
            updateModelLoadingPhase("重みロード")
            val result = withContext(Dispatchers.IO) {
                if (onlyIfAvailable) {
                    manager.initializeModelIfAvailable(engineModelName, config)
                } else {
                    manager.initializeModel(engineModelName, config)
                }
            }

            if (result.isSuccess) {
                updateModelLoadingPhase("ロード完了")
                Log.d(TAG, "loadModelWithOverlay: SUCCESS - model=$model")
            } else {
                val error = result.exceptionOrNull()
                Log.e(TAG, "loadModelWithOverlay: FAILED - model=$model, error=${error?.message}", error)

                // メモリ不足エラーを検出（isMemoryLoadFailure を使用）
                if (error.isMemoryLoadFailure()) {
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
            clearModelLoadingIndicator()
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

        // ★ UI フリーズ対策: 送信タップ直後に UI 状態を同期的に反映する。
        //   MutableStateFlow.value は thread-safe。ここで先に true にすることで、
        //   generationControlMutex 取得 / ensureValidCurrentSession() 等のサスペンド前に
        //   送信ボタン無効化を反映させる。
        //   ★ モデルロード表示 (_isModelLoading + startModelLoadingIndicator) はここでは立てない。
        //     モデルが既ロードの場合は loadModelWithOverlay がショートカットしてオーバーレイを出さない仕様に統一。
        //     以前は入口で先立てしていたため、既ロード時に「ロード不要なのにグルグルが無限に続く」バグを起こしていた。
        _isLoading.value = true

        // 計算集約的な処理はDefault（CPU 集約的タスク用）で実行
        viewModelScope.launch(Dispatchers.Default) {
            val thisJob = coroutineContext[Job]  // このJobインスタンスを保存
            generationControlMutex.withLock {
                generationJob?.cancel(UserStopCancellationException())
                generationJob = thisJob
            }
            val sessionId = ensureValidCurrentSession()
            if (sessionId == null) {
                // セッション取得失敗時はローディング UI を必ず解除する
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    clearModelLoadingIndicator()
                }
                if (generationJob == thisJob) generationJob = null
                return@launch
            }
            var imagesToCleanup = mutableListOf<Bitmap>()
            try {
                // ★ 二重送信防止＆UI競合防止（同期側で既に立てているが冪等性のため再度セット）
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
                    sessionRepository.updateSessionLastUpdated(sessionId)
                    messageId
                }

                // セッション名を送信直後に反映
                withContext(Dispatchers.IO) {
                    maybeUpdateSessionTitleFromUserMessage(sessionId, userMessage)
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
                    // ★ 送信入り口で早期に立てた _isModelLoading が loadModelWithOverlay に
                    //   到達せずに早期 return したケース（モデル未ダウンロード / メモリ不足 等）で
                    //   フラグが残り UI が固まるのを防止する防御的クリーンアップ。
                    if (_isModelLoading.value) {
                        clearModelLoadingIndicator()
                    }
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
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
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
        if (!com.nezumi_ai.voicevox.VoicevoxFeatureFlag.ENABLED) {
            Log.i(TAG, "synthesizeText: VOICEVOX is disabled. Skipping.")
            return
        }
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

    private fun saveBitmapToGallery(bmp: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            val name = "nezumi_chat_sd_${System.currentTimeMillis()}.png"
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/NezumiAI")
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = appContext.contentResolver
                    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                            values.clear()
                            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        Log.d(TAG, "Saved to gallery: $uri")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val uriStr = android.provider.MediaStore.Images.Media.insertImage(
                        appContext.contentResolver,
                        bmp,
                        name,
                        "nezumi-ai SD"
                    )
                    Log.d(TAG, "Saved to gallery (legacy): $uriStr")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save to gallery", e)
            }
        }
    }
}
