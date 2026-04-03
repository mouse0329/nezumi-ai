package com.nezumi_ai.presentation.viewmodel

import android.content.Context
import android.os.SystemClock
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
import java.io.File
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
    
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedModel = MutableStateFlow("E2B")
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
    
    private var modelManager: ModelManager? = null
    private var generationJob: Job? = null
    private val compressedContextCache = mutableMapOf<Long, CompressedContextCache>()
    
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

    fun compressContextManually() {
        val sessionId = _currentSessionId.value ?: return
        if (_isLoading.value || _isModelLoading.value || _isCompressing.value) {
            viewModelScope.launch {
                _uiMessage.emit("生成中または処理中のため圧縮できません")
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
                    _uiMessage.emit("圧縮用モデルのロードに失敗しました")
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
                val cached = compressedContextCache[sessionId]
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
                compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
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
            val config = settingsRepository.getInferenceConfigForModel(selectedModel)
            val loadResult = loadModelWithOverlay(selectedModel, config, onlyIfAvailable = false)
            if (loadResult.isFailure) {
                throw (loadResult.exceptionOrNull()
                    ?: IllegalStateException("モデルのロードに失敗しました"))
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

            // ストリーミング推論を実行
            val aiResponseFlow = withContext(Dispatchers.IO) {
                manager.runInference(
                    sessionId = sessionId,
                    prompt = promptForModel,
                    temperature = config.temperature
                )
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
                                    // セーフガード: マージ結果が元のコンテンツより短くならないことを確認
                                    // （バグで文字が削除される場合を防止）
                                    if (merged != currentContent && merged.length >= currentContent.length) {
                                        responseBuilder.clear()
                                        responseBuilder.append(merged)
                                        Log.d(TAG, "Chunk merged: ${currentContent.length} -> ${merged.length} chars")
                                    } else if (merged.length < currentContent.length) {
                                        Log.w(TAG, "Chunk merge would shrink content: ${currentContent.length} -> ${merged.length}, skipping merge")
                                        // マージで短くなる場合は、元のコンテンツを保持
                                    }
                                }
                            }
// streamingMessageId（または activeStreamingMessageId）の存在を確認
                            val messageIdToUpdate = streamingMessageId ?: activeStreamingMessageId
                            messageIdToUpdate?.let { id ->
                                val contentForUi = responseBuilder.toString()
                                val now = SystemClock.elapsedRealtime()

                                // Markdownテーブルの場合は更新間隔を広げるなど、描画負荷を考慮したインターバル設定
                                val persistInterval = if (isLikelyMarkdownTable(contentForUi)) {
                                    STREAM_PERSIST_INTERVAL_TABLE_MS
                                } else {
                                    STREAM_PERSIST_INTERVAL_MS
                                }

                                // 1. 前回の保存内容と異なる
                                // 2. 最終的な応答（finalFromModel != null）である、または一定時間が経過した
                                val shouldPersist = contentForUi != lastPersistedContent &&
                                    (finalFromModel != null || now - lastPersistAt >= persistInterval)

                                if (shouldPersist) {
                                    messageRepository.updateMessageContent(
                                        messageId = id,
                                        content = contentForUi,
                                        isStreaming = true
                                    )
                                    // 保存状態を更新
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

            val completeResponse = finalizeResponseForCommit(responseBuilder.toString())

            if (completeResponse.isNotEmpty()) {
                messageRepository.updateMessageContent(
                    messageId = activeStreamingMessageId,
                    content = completeResponse,
                    isStreaming = false
                )
                maybeGenerateSessionTitle(sessionId, userMessage, completeResponse)
                _uiMessage.emit("生成が完了しました")
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
            trimmed.equals("E4B", ignoreCase = true) -> "E4B"
            isLocalTaskPath -> trimmed
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
        val cached = compressedContextCache[sessionId]
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
                    compressedContextCache[sessionId] = CompressedContextCache(signature, summary)
                }
            } finally {
                _isCompressing.value = false
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
            append("以下の形式で出力して。挨拶は不要。\n")
            append("{\"summary\": \"（ここに要約）\", \"keywords\": [\"A\", \"B\", \"C\"]}\n")
            append("keywords は重要語を3-8個の配列で出力してください。\n")
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

        val parsed = raw?.let { parseCompressionJson(it) }
        return if (parsed != null) {
            buildString {
                append("要約: ")
                append(parsed.first)
                append("\nキーワード: ")
                append(parsed.second.joinToString(", "))
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
            val displayModel = if (model == "E2B" || model == "E4B") model else "カスタム"
            _modelLoadingStatus.value = "[$displayModel] エンジンを初期化中..."
            val result = if (onlyIfAvailable) {
                manager.initializeModelIfAvailable(engineModelName, config)
            } else {
                manager.initializeModel(engineModelName, config)
            }
            if (result.isSuccess) {
                _modelLoadingStatus.value = "[$displayModel] ロード完了"
            }
            result
        } finally {
            _isModelLoading.value = false
            _modelLoadingStatus.value = ""
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
