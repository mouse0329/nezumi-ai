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
import com.nezumi_ai.data.inference.ModelFileManager
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import java.io.File
import java.io.ByteArrayOutputStream
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

    private val _selectedModel = MutableStateFlow("E2B")
    val selectedModel: StateFlow<String> = _selectedModel

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading

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
                                    val merged = mergeStreamingChunk(responseBuilder.toString(), chunk)
                                    if (merged != responseBuilder.toString()) {
                                        responseBuilder.clear()
                                        responseBuilder.append(merged)
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

        val overlap = suffixPrefixOverlap(current, chunk)
        if (overlap > 0) {
            return current + chunk.substring(overlap)
        }

        // deltaとして連結（最終的にはFINALで確定全文に置換される）
        return current + chunk
    }

    private fun suffixPrefixOverlap(left: String, right: String): Int {
        val max = minOf(left.length, right.length)
        for (size in max downTo 1) {
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
                    val merged = mergeStreamingChunk(builder.toString(), chunk)
                    if (merged != builder.toString()) {
                        builder.clear()
                        builder.append(merged)
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

    /**
     * プロンプトにメディア情報を統合
     */
    private fun augmentPromptWithMediaInfo(
        prompt: String,
        images: List<Bitmap>,
        audioClips: List<ByteArray>
    ): String {
        val mediaInfo = StringBuilder()

        if (images.isNotEmpty()) {
            mediaInfo.append("\n【添付画像】\n")
            images.forEachIndexed { index, bitmap ->
                mediaInfo.append("- 画像 ${index + 1}: ${bitmap.width}x${bitmap.height}px\n")
            }
        }

        if (audioClips.isNotEmpty()) {
            mediaInfo.append("\n【添付音声】\n")
            audioClips.forEachIndexed { index, audioBytes ->
                mediaInfo.append("- 音声 ${index + 1}: ${audioBytes.size / 1024}KB\n")
            }
        }

        return if (mediaInfo.isEmpty()) {
            prompt
        } else {
            prompt + mediaInfo.toString()
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
     * BitmapをPNG ByteArrayに変換
     */
    private fun bitmapToPngByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /**
     * 音声をmono/16kHz/16-bit WAVに正規化
     * 注：実装例。実際のWAVエンコーディングが必要な場合はより詳細な実装が必要
     */
    private fun normalizeAudioToMono16Bit16kHz(audioBytes: ByteArray): ByteArray {
        return try {
            // WAVファイルの場合、すでに適切なフォーマットかチェック
            // ここでは簡略化して、与えられたバイトアレイをそのまま返す
            // 実装ガイドラインに従って、より詳細な正規化ロジックを追加してください
            audioBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error normalizing audio", e)
            audioBytes
        }
    }

    /**
     * URIからBitmapをロード
     */
    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeStream(appContext.contentResolver.openInputStream(uri))
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
                appContext.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                }
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

        generationJob = viewModelScope.launch {
            try {
                // メディア付きユーザーメッセージを保存
                messageRepository.addMessage(
                    sessionId = sessionId,
                    role = "user",
                    content = userMessage,
                    imageUri = imageUri,
                    audioUri = audioUri
                )

                // セッションの lastUpdated を更新
                sessionRepository.updateSessionLastUpdated(sessionId)

                // 入力フィールドをクリア
                _inputText.value = ""

                // URI から Bitmap・ByteArray に変換
                val images = mutableListOf<Bitmap>()
                val audioClips = mutableListOf<ByteArray>()

                imageUri?.let { uriStr ->
                    val bitmap = loadBitmapFromUri(Uri.parse(uriStr))
                    if (bitmap != null) {
                        images.add(bitmap)
                        Log.d(TAG, "Loaded image from URI: $uriStr")
                    }
                }

                audioUri?.let { uriStr ->
                    val audioBytes = loadAudioBytesFromUri(Uri.parse(uriStr))
                    if (audioBytes != null) {
                        audioClips.add(audioBytes)
                        Log.d(TAG, "Loaded audio from URI: $uriStr")
                    }
                }

                // AI応答を生成（画像・音声データを含む）
                _isLoading.value = true
                generateAIResponse(sessionId, userMessage, images, audioClips)
            } catch (t: Throwable) {
                val e = if (t is Exception) t else RuntimeException(t)
                Log.e(TAG, "Error sending message with media", e)
                _uiMessage.emit("メディア付きメッセージの送信に失敗しました: ${e.message}")
            } finally {
                _isLoading.value = false
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

                messageRepository.updateMessageMedia(
                    messageId = messageId,
                    imageUri = imageUri,
                    audioUri = audioUri
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
