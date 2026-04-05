package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
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
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import java.io.File
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
        private const val STREAM_PERSIST_INTERVAL_MS = 66L
        private const val STREAM_PERSIST_INTERVAL_TABLE_MS = 16L
        private const val DEFAULT_SESSION_TITLE = "新しいチャット"
        const val CONTEXT_WINDOW_CHARS = 4_096
        private const val MAX_CONTEXT_CHARS = CONTEXT_WINDOW_CHARS
        private const val COMPRESSION_RECENT_MESSAGE_COUNT = 6
    }

    private class FirstTokenTimeoutException : CancellationException("FIRST_TOKEN_TIMEOUT")

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

    private val _sessionTitle = MutableStateFlow(DEFAULT_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle

    private val _contextUsageChars = MutableStateFlow(0)
    val contextUsageChars: StateFlow<Int> = _contextUsageChars

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage
    
    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent
    
    enum class NavigationEvent {
        BACK_TO_HOME,  // ホームスクリーンに戻る
        CLEAR_CHAT     // チャット画面をクリア
    }
    
    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null
    private var messagesCollectionJob: Job? = null
    private val compressedContextCache = mutableMapOf<Long, CompressedContextCache>()
    private var currentBackendType = "CPU"  // GPU時はキャッシュを無効化するためのフラグ
    
    init {
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
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error monitoring settings changes", e)
            }
        }
    }
    
    fun setCurrentSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        // キャンセル前のコレクションジョブ
        messagesCollectionJob?.cancel()
        
        messagesCollectionJob = viewModelScope.launch {
            Log.d(TAG, "START_MESSAGE_COLLECTION: sessionId=$sessionId")
            messageRepository.getMessagesForSession(sessionId).collect { msgs ->
                Log.d(TAG, "UPDATE_MESSAGES_FLOW: count=${msgs.size} messages=${msgs.map { "${it.role}:${it.content.take(30)}" }}")
                _messages.value = msgs
                _contextUsageChars.value = estimateContextUsageChars(msgs)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            _sessionTitle.value = session.name
        }
        viewModelScope.launch(Dispatchers.IO) {
            loadModelForSessionIfAvailable(sessionId)
        }
    }
    
    fun updateInputText(text: String) {
        _inputText.value = text
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
            val config = settingsRepository.getInferenceConfigForModel(normalizedModel)
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
                if (errorMsg.contains("Cannot read", ignoreCase = true) ||
                    errorMsg.contains("not found", ignoreCase = true) ||
                    errorMsg.contains("corrupt", ignoreCase = true) ||
                    errorMsg.contains("invalid", ignoreCase = true)) {
                    
                    Log.w(TAG, "モデルファイルの読み込みエラー: $normalizedModel")
                    _uiMessage.emit("❌ モデルファイルが読み込めません。設定画面で再ダウンロードしてください。")
                    
                    // ファイルを削除してリセット
                    try {
                        val modelEnum = when (normalizedModel.uppercase()) {
                            "GEMMA4-4B" -> ModelFileManager.LocalModel.GEMMA4_4B
                            "GEMMA4-2B" -> ModelFileManager.LocalModel.GEMMA4_2B
                            else -> ModelFileManager.LocalModel.GEMMA4_2B
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
        
        generationJob = viewModelScope.launch {
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
                generationJob = null
            }
        }
    }

    fun compressContextManually() {
        val sessionId = _currentSessionId.value ?: return
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
                    .filterNot { it.role == "assistant" && it.isStreaming }
                if (messages.size <= COMPRESSION_RECENT_MESSAGE_COUNT) {
                    _uiMessage.emit("圧縮対象の履歴がまだ少ないです")
                    return@launch
                }

                val olderMessages = messages.dropLast(COMPRESSION_RECENT_MESSAGE_COUNT)
                val signature = olderMessages.fold(17) { acc, msg ->
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
                        messages = olderMessages,
                        config = config
                    )
                } finally {
                    _isCompressing.value = false
                }
                
                // GPU時はキャッシュに保存しない
                if (useCache) {
                    compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
                }
                
                _uiMessage.emit("コンテキストを圧縮しました")
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
        generationJob = null
        _isLoading.value = false
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
        try {
            val manager = requireModelManager()
            val selectedModel = normalizeModel(settingsRepository.getSelectedModel())
            
            // メモリ不足チェック
            if (manager.getMemoryUsagePercent() >= 85) {
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
            val config = settingsRepository.getInferenceConfigForModel(selectedModel)
            val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
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
                if (errorMsg.contains("Cannot read", ignoreCase = true) ||
                    errorMsg.contains("not found", ignoreCase = true) ||
                    errorMsg.contains("corrupt", ignoreCase = true) ||
                    errorMsg.contains("invalid", ignoreCase = true)) {
                    
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
            
            val promptWithContext = buildPromptWithSessionContext(sessionId, config, manager)
            val promptForModel = trimPromptForTokenBudget(promptWithContext, config.maxTokens)
            if (promptForModel.length < promptWithContext.length) {
                Log.w(
                    TAG,
                    "Prompt trimmed for token budget: ${promptWithContext.length} -> ${promptForModel.length}, maxTokens=${config.maxTokens}"
                )
            }

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
                        temperature = config.temperature
                    )
                } else {
                    // テキストのみ推論
                    Log.d(TAG, "Using text-only inference")
                    manager.runInference(
                        sessionId = sessionId,
                        prompt = promptForModel,
                        temperature = config.temperature
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

            val responseBuilder = StringBuilder()
            var lastPersistedContent = ""
            var lastPersistAt = 0L

            // ストリーム内容を収集
            // タイムアウトは「最初の出力が来るまで」のみ有効。
            var firstTokenReceived = false
            try {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val firstTokenTimeoutJob = launch {
                            delay(RESPONSE_TIMEOUT_MS)
                            if (!firstTokenReceived) {
                                cancel(FirstTokenTimeoutException())
                            }
                        }

                        try {
                            aiResponseFlow.collect { chunk ->
                                if (!firstTokenReceived) {
                                    firstTokenReceived = true
                                    firstTokenTimeoutJob.cancel()
                                }
                                val finalFromModel = InferenceStreamProtocol.decodeFinal(chunk)
                                if (finalFromModel != null) {
                                    responseBuilder.clear()
                                    responseBuilder.append(finalFromModel)
                                } else {
                                    if (chunk.isNotEmpty()) {
                                        val currentContent = responseBuilder.toString()
                                        val merged = mergeStreamingChunk(currentContent, chunk)
                                        if (merged != currentContent && merged.length >= currentContent.length) {
                                            responseBuilder.clear()
                                            responseBuilder.append(merged)
                                            Log.d(TAG, "Chunk merged: ${currentContent.length} -> ${merged.length} chars")
                                        } else if (merged.length < currentContent.length) {
                                            Log.w(TAG, "Chunk merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping merge")
                                        }
                                    }
                                }
                                val messageIdToUpdate = streamingMessageId ?: activeStreamingMessageId
                                messageIdToUpdate?.let { id ->
                                    val contentForUi = responseBuilder.toString()
                                    val now = SystemClock.elapsedRealtime()
                                    val persistInterval = if (isLikelyMarkdownTable(contentForUi)) {
                                        STREAM_PERSIST_INTERVAL_TABLE_MS
                                    } else {
                                        STREAM_PERSIST_INTERVAL_MS
                                    }
                                    val shouldPersist = contentForUi != lastPersistedContent &&
                                        (finalFromModel != null || now - lastPersistAt >= persistInterval)
                                    if (shouldPersist) {
                                        messageRepository.updateMessageContent(
                                            messageId = id,
                                            content = contentForUi,
                                            isStreaming = true
                                        )
                                        lastPersistedContent = contentForUi
                                        lastPersistAt = now
                                    }
                                }
                                Log.d(TAG, "Received chunk: $chunk")
                            }
                        } finally {
                            firstTokenTimeoutJob.cancel()
                        }
                    }
                }
            } catch (collectionError: Throwable) {
                Log.e(TAG, "Error during flow collection", collectionError)
                if (collectionError !is FirstTokenTimeoutException && collectionError !is CancellationException) {
                    throw collectionError
                }
            }

            val completeResponse = finalizeResponseForCommit(responseBuilder.toString())

            if (completeResponse.isNotEmpty()) {
                messageRepository.updateMessageContent(
                    messageId = activeStreamingMessageId,
                    content = completeResponse,
                    isStreaming = false
                )
                maybeGenerateSessionTitle(sessionId, userMessage, completeResponse)
                Log.d(TAG, "AI response saved to database: ${completeResponse.take(50)}...")
            } else {
                messageRepository.updateMessageContent(
                    messageId = activeStreamingMessageId,
                    content = "申し訳ありません。応答を生成できませんでした。",
                    isStreaming = false
                )
            }
        } catch (t: Throwable) {
            if (t is FirstTokenTimeoutException) {
                val id = streamingMessageId
                if (id != null) {
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = "応答開始がタイムアウトしました。もう一度お試しください。",
                        isStreaming = false
                    )
                }
                return
            }
            if (t is CancellationException) {
                val id = streamingMessageId
                if (id != null) {
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = "生成を停止しました。",
                        isStreaming = false
                    )
                }
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
                else -> "エラーが発生しました：${e.message}"
            }
            val id = streamingMessageId
            if (id != null) {
                messageRepository.updateMessageContent(
                    messageId = id,
                    content = errorMessage,
                    isStreaming = false
                )
            } else {
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "assistant",
                    content = errorMessage
                )
            }
        }
    }

    private suspend fun loadModelForSessionIfAvailable(sessionId: Long) {
        requireModelManager()
        sessionRepository.getSessionById(sessionId) ?: return
        val selectedModel = normalizeModel(settingsRepository.getSelectedModel())
        _selectedModel.value = selectedModel
        val config = settingsRepository.getInferenceConfigForModel(selectedModel)
        val result = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = true)
        if (result.isFailure) {
            Log.e(TAG, "Failed to load model for session $sessionId", result.exceptionOrNull())
        }
    }

    private fun normalizeModel(model: String): String {
        val trimmed = model.trim()
        val lowered = trimmed.lowercase()
        val isLocalTaskPath =
            (lowered.endsWith(".task") || lowered.endsWith(".litertlm")) && File(trimmed).isAbsolute
        
        return when {
            trimmed.equals("Gemma4-4B", ignoreCase = true) -> "Gemma4-4B"
            trimmed.equals("Gemma4-2B", ignoreCase = true) -> "Gemma4-2B"
            trimmed.equals("E4B", ignoreCase = true) -> "Gemma4-4B"  // 互換性のため E4B → Gemma4-4B
            trimmed.equals("E2B", ignoreCase = true) -> "Gemma4-2B"  // 互換性のため E2B → Gemma4-2B
            isLocalTaskPath -> trimmed
            else -> "Gemma4-2B"  // デフォルト
        }
    }

    private fun toEngineModelName(model: String): String {
        val normalized = normalizeModel(model)
        return when {
            normalized.equals("Gemma4-4B", ignoreCase = true) -> "gemma4-4b"
            normalized.equals("Gemma4-2B", ignoreCase = true) -> "gemma4-2b"
            (normalized.endsWith(".task") || normalized.endsWith(".litertlm")) && normalized.startsWith("/") -> normalized
            else -> "gemma4-2b"  // デフォルト
        }
    }

    private suspend fun maybeGenerateSessionTitle(
        sessionId: Long,
        userMessage: String,
        aiResponse: String
    ) {
        val session = sessionRepository.getSessionById(sessionId) ?: return
        if (session.name != DEFAULT_SESSION_TITLE) return
        val title = buildSessionTitle(userMessage, aiResponse)
        if (title.isBlank()) return
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

    private fun finalizeResponseForCommit(raw: String): String {
        val text = raw.trim()
        if (text.isEmpty()) return text
        // 同一全文が連結されるケースを確定時に除去
        if (text.length % 2 == 0) {
            val half = text.length / 2
            val first = text.substring(0, half)
            val second = text.substring(half)
            if (first == second) {
                return first
            }
        }
        return text
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
                return merged
            }
        }

        // deltaとして連結（最終的にはFINALで確定全文に置換される）
        return current + chunk
    }

    private fun suffixPrefixOverlapConservative(left: String, right: String): Int {
        // 重複を検出する際、最大チェック文字数を制限して安全性を確保
        // これにより、不正な重複検出による文字削除を防止
        val maxCheckSize = minOf(left.length, right.length, 50)
        val minCheckSize = 1
        
        for (size in maxCheckSize downTo minCheckSize) {
            if (left.regionMatches(left.length - size, right, 0, size, ignoreCase = false)) {
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
        if (!config.contextCompressionEnabled) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val thresholdChars =
            ((config.contextWindow * config.contextCompressionThresholdPercent) / 100).coerceAtLeast(1)
        if (fullPrompt.length < thresholdChars) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val validMessages = messages.filterNot { it.role == "assistant" && it.isStreaming }
        if (validMessages.size <= COMPRESSION_RECENT_MESSAGE_COUNT) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val olderMessages = validMessages.dropLast(COMPRESSION_RECENT_MESSAGE_COUNT)
        val recentMessages = validMessages.takeLast(COMPRESSION_RECENT_MESSAGE_COUNT)
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

        val systemPrompt = settingsRepository.getSystemPrompt()
        val contextBuilder = StringBuilder()
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        contextBuilder.append("以下は過去会話の圧縮コンテキストです:\n")
        contextBuilder.append(compressedSummary)
        contextBuilder.append("\n\n")
        for (msg in recentMessages) {
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(msg.content.trim())
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

        val transcript = messages.joinToString(separator = "\n") { msg ->
            val role = if (msg.role == "assistant") "assistant" else "user"
            "$role: ${msg.content.trim()}"
        }

        val compressionPrompt = buildString {
            append("以下の会話履歴を要約してください。\n")
            append("自然な日本語の文章だけで出力してください。\n")
            append("\n")
            append("注意:\n")
            append("- 自然な日本語の文章のみで出力\n")
            append("- JSONやコードブロック形式は使用しないでください\n")
            append("- 挨拶や説明文は不要\n")
            append("- 要約は1-2文で簡潔に\n")
            append("\n")
            append("会話履歴:\n")
            append(transcript)
        }

        val raw = withTimeoutOrNull(COMPRESSION_TIMEOUT_MS) {
            val flow = manager.runInference(
                sessionId = sessionId,
                prompt = compressionPrompt,
                temperature = config.temperature.coerceIn(0f, 0.7f)
            )
            val builder = StringBuilder()
            flow.collect { chunk ->
                val final = InferenceStreamProtocol.decodeFinal(chunk)
                if (final != null) {
                    builder.clear()
                    builder.append(final)
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
        
        // 自然言語の要約が返ってきた場合
        return if (!raw.isNullOrBlank()) {
            buildString {
                append("要約: ")
                append(raw.trim())
                append("\nキーワード: （自動抽出）")
            }
        } else {
            buildCompressedSummaryFallback(messages)
        }
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
        return buildPromptFromMessages(messages).length.coerceAtMost(MAX_CONTEXT_CHARS)
    }

    private suspend fun buildPromptFromMessages(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return ""
        val systemPrompt = settingsRepository.getSystemPrompt()
        val contextBuilder = StringBuilder()
        if (systemPrompt.isNotEmpty()) {
            contextBuilder.append(systemPrompt)
            contextBuilder.append("\n\n")
        }
        for (msg in messages) {
            if (msg.role == "assistant" && msg.isStreaming) continue
            val role = if (msg.role == "assistant") "Assistant" else "User"
            contextBuilder.append(role)
                .append(": ")
                .append(msg.content.trim())
                .append('\n')
        }
        contextBuilder.append("Assistant:")
        return contextBuilder.toString()
    }

    private fun buildCompressedSummaryFallback(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return "（圧縮対象なし）"
        return messages.takeLast(24).joinToString(separator = "\n") { msg ->
            val role = if (msg.role == "assistant") "A" else "U"
            val text = msg.content
                .trim()
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .let { if (it.length > 80) it.take(80).trimEnd() + "..." else it }
            "[$role] $text"
        }
    }

    private fun trimPromptToWindow(prompt: String, contextWindow: Int): String {
        if (prompt.length <= contextWindow) return prompt
        return prompt.takeLast(contextWindow)
    }

    private fun trimPromptForTokenBudget(prompt: String, maxTokens: Int): String {
        // MediaPipeのmaxTokensは入力+出力の合計。出力分を予約して入力を保守的に圧縮する。
        val reservedOutputTokens = (maxTokens / 4).coerceIn(64, 512)
        val maxInputTokens = (maxTokens - reservedOutputTokens).coerceAtLeast(64)
        // 日本語では1文字あたりトークン消費が大きくなりやすいため、1文字=1トークンで保守的に見積もる。
        if (prompt.length <= maxInputTokens) return prompt
        return prompt.takeLast(maxInputTokens)
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
                appContext.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
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
        onlyIfAvailable: Boolean
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
            _modelLoadingStatus.value = "[$displayModel] エンジンを初期化中..."
            
            val result = if (onlyIfAvailable) {
                manager.initializeModelIfAvailable(engineModelName, config)
            } else {
                manager.initializeModel(engineModelName, config)
            }
            
            if (result.isSuccess) {
                _modelLoadingStatus.value = "[$displayModel] ロード完了"
            } else {
                val error = result.exceptionOrNull()
                
                // メモリ不足エラーを検出
                if (error?.message?.contains("memory") == true || 
                    error?.message?.contains("Memory") == true) {
                    _uiMessage.emit("メモリが不足しています。ホームスクリーンに戻ります...")
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
     * メディア付きメッセージを送信（画像・音声対応）
     */
    fun sendMessageWithMedia(
        userMessage: String,
        imageUri: String? = null,
        audioUri: String? = null
    ) {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value) return

        // 計算集約的な処理はDefault（CPU 集約的タスク用）で実行
        generationJob = viewModelScope.launch(Dispatchers.Default) {
            var imagesToCleanup = mutableListOf<Bitmap>()
            try {
                val storedImage = withContext(Dispatchers.IO) {
                    MessageMediaStore.persistUriIfNeeded(appContext, imageUri)
                }
                val storedAudio = withContext(Dispatchers.IO) {
                    MessageMediaStore.persistUriIfNeeded(appContext, audioUri)
                }

                // メディア付きユーザーメッセージを保存（DB アクセス - IO スレッド）
                withContext(Dispatchers.IO) {
                    Log.d(TAG, "SAVE_MESSAGE_START: content='$userMessage'")
                    val messageId = messageRepository.addMessage(
                        sessionId = sessionId,
                        role = "user",
                        content = userMessage,
                        imageUri = storedImage,
                        audioUri = storedAudio
                    )
                    Log.d(TAG, "SAVE_MESSAGE_END: messageId=$messageId content='$userMessage'")
                    sessionRepository.updateSessionLastUpdated(sessionId)
                }

                // 入力フィールドをクリア（UI 更新 - Main スレッド）
                withContext(Dispatchers.Main) {
                    _inputText.value = ""
                }

                // URI から Bitmap・ByteArray に変換
                val images = mutableListOf<Bitmap>()
                val audioClips = mutableListOf<ByteArray>()

                storedImage?.let { uriStr ->
                    val uri = MessageMediaStore.toUri(uriStr)
                    val bitmap = loadBitmapFromUri(uri)
                    if (bitmap != null) {
                        val scaled = scaleBitmapTo1024(bitmap)
                        if (scaled !== bitmap) bitmap.recycle()
                        images.add(scaled)
                        imagesToCleanup.add(scaled)  // ← クリーンアップリストに追加
                        Log.d(TAG, "Loaded image for inference: $uriStr")
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
                generationJob = null
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
    
    override fun onCleared() {
        super.onCleared()
        // ViewModelクリア時のリソース完全解放
        generationJob?.cancel()  // 進行中の推論をキャンセル
        generationJob = null
        
        // モデルをアンロード
        viewModelScope.launch {
            try {
                modelManager?.unloadModel()
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error unloading model", e)
            }
        }
    }
}
