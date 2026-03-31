package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.data.repository.MessageRepository
import com.nezumi_ai.data.repository.SettingsRepository
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.InferenceStreamProtocol
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
        private const val COMPRESSION_SIMULATED_COST_MS = 450L
        private const val DEFAULT_SESSION_TITLE = "新しいチャット"
        const val CONTEXT_WINDOW_CHARS = 4_096
        private const val MAX_CONTEXT_CHARS = CONTEXT_WINDOW_CHARS
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
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedModel = MutableStateFlow("E2B")
    val selectedModel: StateFlow<String> = _selectedModel

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading

    private val _sessionTitle = MutableStateFlow(DEFAULT_SESSION_TITLE)
    val sessionTitle: StateFlow<String> = _sessionTitle

    private val _contextUsageChars = MutableStateFlow(0)
    val contextUsageChars: StateFlow<Int> = _contextUsageChars

    private val _uiMessage = MutableSharedFlow<String>()
    val uiMessage: SharedFlow<String> = _uiMessage
    
    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null
    private val compressedContextCache = mutableMapOf<Long, CompressedContextCache>()
    
    init {
        // ViewModel初期化時は設定のみ取得（モデルロードはしない）
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _selectedModel.value = settingsRepository.getSelectedModel().uppercase()
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error initializing ModelManager", e)
            }
        }
    }
    
    fun setCurrentSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        viewModelScope.launch {
            messageRepository.getMessagesForSession(sessionId).collect { msgs ->
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
        viewModelScope.launch(Dispatchers.IO) {
            val normalizedModel = normalizeModel(model)
            settingsRepository.updateModel(normalizedModel)
            _selectedModel.value = normalizedModel
            val config = settingsRepository.getInferenceConfig()
            val result = loadModelWithOverlay(normalizedModel, config, onlyIfAvailable = true)
            if (result.isFailure) {
                Log.e(TAG, "Failed to switch model: $normalizedModel", result.exceptionOrNull())
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

    fun stopGeneration() {
        generationJob?.cancel(CancellationException("Stopped by user"))
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

        val toDelete = messages.subList(targetIndex, messages.size).map { it.id }
        toDelete.forEach { messageId ->
            messageRepository.deleteMessageById(messageId)
        }
        compressedContextCache.remove(sessionId)
        sessionRepository.updateSessionLastUpdated(sessionId)
        _uiMessage.emit("プロンプトを取り消しました")
    }
    
    private suspend fun generateAIResponse(sessionId: Long, userMessage: String) {
        var streamingMessageId: Long? = null
        try {
            val manager = requireModelManager()
            val selectedModel = normalizeModel(settingsRepository.getSelectedModel())
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
            val config = settingsRepository.getInferenceConfig()
            val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
            if (loadResult.isFailure) {
                throw (loadResult.exceptionOrNull()
                    ?: IllegalStateException("モデルのロードに失敗しました"))
            }
            
            Log.d(TAG, "Starting inference for session $sessionId")
            
            val promptWithContext = buildPromptWithSessionContext(sessionId, config)

            // ストリーミング推論を実行
            val aiResponseFlow = withContext(Dispatchers.IO) {
                manager.runInference(
                    sessionId = sessionId,
                    prompt = promptWithContext,
                    temperature = config.temperature
                )
            }

            streamingMessageId = messageRepository.addMessage(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                isStreaming = true
            )

            val responseBuilder = StringBuilder()

            // ストリーム内容を収集
            // タイムアウトは「最初の出力が来るまで」のみ有効。
            var firstTokenReceived = false
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
                                val currentText = responseBuilder.toString()
                                when {
                                    chunk.isEmpty() -> Unit
                                    chunk == currentText -> Unit
                                    chunk.startsWith(currentText) -> {
                                        responseBuilder.append(chunk.removePrefix(currentText))
                                    }
                                    else -> {
                                        responseBuilder.append(chunk)
                                    }
                                }
                            }
                            streamingMessageId?.let { id ->
                                messageRepository.updateMessageContent(
                                    messageId = id,
                                    content = responseBuilder.toString(),
                                    isStreaming = true
                                )
                            }
                            Log.d(TAG, "Received chunk: $chunk")
                        }
                    } finally {
                        firstTokenTimeoutJob.cancel()
                    }
                }
            }

            val completeResponse = finalizeResponseForCommit(responseBuilder.toString())

            if (completeResponse.isNotEmpty()) {
                streamingMessageId?.let { id ->
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = completeResponse,
                        isStreaming = false
                    )
                }
                maybeGenerateSessionTitle(sessionId, userMessage, completeResponse)
                Log.d(TAG, "AI response saved to database: ${completeResponse.take(50)}...")
            } else {
                streamingMessageId?.let { id ->
                    messageRepository.updateMessageContent(
                        messageId = id,
                        content = "申し訳ありません。応答を生成できませんでした。",
                        isStreaming = false
                    )
                }
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

            val errorMessage = "エラーが発生しました: ${e.message}"
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
        val config = settingsRepository.getInferenceConfig()
        val result = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = true)
        if (result.isFailure) {
            Log.e(TAG, "Failed to load model for session $sessionId", result.exceptionOrNull())
        }
    }

    private fun normalizeModel(model: String): String {
        return when {
            model.uppercase() == "E4B" -> "E4B"
            (model.endsWith(".task") || model.endsWith(".litertlm")) && model.startsWith("/") -> model
            else -> "E2B"
        }
    }

    private fun toEngineModelName(model: String): String {
        val normalized = normalizeModel(model)
        return when {
            normalized == "E4B" -> "gemma-3.2:e4b"
            (normalized.endsWith(".task") || normalized.endsWith(".litertlm")) && normalized.startsWith("/") -> normalized
            else -> "gemma-3.2:e2b"
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

    private suspend fun buildPromptWithSessionContext(
        sessionId: Long,
        config: InferenceConfig
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
        if (validMessages.size <= 6) {
            return trimPromptToWindow(fullPrompt, config.contextWindow)
        }

        val olderMessages = validMessages.dropLast(6)
        val recentMessages = validMessages.takeLast(6)
        val signature = olderMessages.fold(17) { acc, msg ->
            ((acc * 31) + msg.role.hashCode()) * 31 + msg.content.hashCode()
        }
        val cached = compressedContextCache[sessionId]
        val compressedSummary = if (cached != null && cached.signature == signature) {
            cached.summary
        } else {
            delay(COMPRESSION_SIMULATED_COST_MS)
            buildCompressedSummary(olderMessages).also { summary ->
                compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
            }
        }

        val contextBuilder = StringBuilder()
        contextBuilder.append("あなたは親切で簡潔なアシスタントです。会話の文脈を踏まえて日本語で回答してください。\n\n")
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

    private fun estimateContextUsageChars(messages: List<MessageEntity>): Int {
        return buildPromptFromMessages(messages).length.coerceAtMost(MAX_CONTEXT_CHARS)
    }

    private fun buildPromptFromMessages(messages: List<MessageEntity>): String {
        if (messages.isEmpty()) return ""
        val contextBuilder = StringBuilder()
        contextBuilder.append("あなたは親切で簡潔なアシスタントです。会話の文脈を踏まえて日本語で回答してください。\n\n")
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

    private fun buildCompressedSummary(messages: List<MessageEntity>): String {
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
        return try {
            if (onlyIfAvailable) {
                manager.initializeModelIfAvailable(engineModelName, config)
            } else {
                manager.initializeModel(engineModelName, config)
            }
        } finally {
            _isModelLoading.value = false
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // ViewModelがクリアされるときにモデルをアンロード
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
