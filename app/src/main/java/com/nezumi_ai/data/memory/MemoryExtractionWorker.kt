package com.nezumi_ai.data.memory

import android.util.Log
import com.nezumi_ai.data.database.entity.MessageEntity
import com.nezumi_ai.data.inference.InferenceConfig
import com.nezumi_ai.data.inference.InferenceStreamProtocol
import com.nezumi_ai.data.inference.ModelManager
import com.nezumi_ai.data.repository.ChatChunkRepository
import com.nezumi_ai.data.repository.MemoryRepository
import com.nezumi_ai.data.repository.MemorySessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase 3: LLMベースのメモリ抽出・矛盾解消・直列キュー
 *
 * - limitedParallelism(1) で直列化（同時に1セッションのみ抽出）
 * - Gemma 4 2B で事実を JSON 抽出
 * - 矛盾チェック → UPDATE or INSERT
 * - MemorySessionRepository で lastExtractedTurn / pendingExtraction を管理
 */
class MemoryExtractionWorker(
    private val memoryRepository: MemoryRepository,
    private val memorySessionRepository: MemorySessionRepository,
    private val chatChunkRepository: ChatChunkRepository
) {
    private val extractionScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    /**
     * 抽出をキューに積む（non-blocking）
     * @param sessionId  会話セッションID
     * @param messages   直近の全メッセージ（DB から取得済み）
     * @param manager    現在ロード中の ModelManager
     * @param config     推論設定（temperature等）
     */
    fun enqueue(
        sessionId: Long,
        messages: List<MessageEntity>,
        manager: ModelManager,
        config: InferenceConfig,
        saveMode: MemorySaveMode
    ) {
        extractionScope.launch {
            runExtraction(sessionId, messages, manager, config, saveMode, suppressContradictionDeletion = false)
        }
    }

    /**
     * 起動時に pending_extraction=true のセッションを処理する
     */
    fun processPending(
        manager: ModelManager,
        config: InferenceConfig,
        saveMode: MemorySaveMode,
        fetchMessages: suspend (Long) -> List<MessageEntity>,
        suppressContradictionDeletion: Boolean = false
    ) {
        extractionScope.launch {
            val pending = memorySessionRepository.getPendingSessions()
            Log.d(TAG, "MEMORY_PENDING: ${pending.size} sessions to process")
            for (session in pending) {
                val sessionId = session.id.toLongOrNull() ?: continue
                val messages = fetchMessages(sessionId)
                runExtraction(sessionId, messages, manager, config, saveMode, suppressContradictionDeletion)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // internal
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runExtraction(
        sessionId: Long,
        messages: List<MessageEntity>,
        manager: ModelManager,
        config: InferenceConfig,
        saveMode: MemorySaveMode,
        suppressContradictionDeletion: Boolean
    ) {
        if (messages.isEmpty()) return

        val session = memorySessionRepository.getOrCreate(sessionId.toString())
        val currentTurn = messages.size
        val newTurns = currentTurn - session.lastExtractedTurn

        // スキップ条件: 差分が2ターン未満 or 直近20ターンが100トークン未満
        if (newTurns < 2) {
            Log.d(TAG, "MEMORY_EXTRACT: skip session=$sessionId newTurns=$newTurns < 2")
            return
        }
        val recentMessages = messages.takeLast(RECENT_TURN_LIMIT)
        val estimatedTokens = recentMessages.sumOf { it.content.length } / 4
        if (estimatedTokens < MIN_TOKENS) {
            Log.d(TAG, "MEMORY_EXTRACT: skip session=$sessionId estimatedTokens=$estimatedTokens < $MIN_TOKENS")
            return
        }

        _isExtracting.value = true
        try {
            // アプリが落ちた場合に備えて pending=true をマーク
            memorySessionRepository.markPending(sessionId.toString(), true)

            val candidates = when (saveMode) {
                MemorySaveMode.LLM -> runLlmExtraction(sessionId, recentMessages, manager, config)
                MemorySaveMode.RULE_BASED -> runRuleBasedExtraction(recentMessages)
            }
            Log.d(TAG, "MEMORY_EXTRACT: session=$sessionId extracted ${candidates.size} candidates with mode=$saveMode")

            var lastRgaUid: String? = null
            for (candidate in candidates) {
                lastRgaUid = saveWithContradictionCheck(
                    sessionId, candidate, manager, config,
                    !suppressContradictionDeletion,
                    rgaPrevUid = lastRgaUid
                )
            }

            memorySessionRepository.markExtracted(sessionId.toString(), currentTurn)
            Log.d(TAG, "MEMORY_EXTRACT: session=$sessionId done, turn=$currentTurn")

            // チャット履歴インデックス化（メモリ抽出と同タイミング）
            for (msg in messages) {
                if (msg.content.isNotBlank()) {
                    chatChunkRepository.indexMessage(msg.id, sessionId, msg.content)
                }
            }
            Log.d(TAG, "CHUNK_INDEX: session=$sessionId indexed ${messages.size} messages")
        } catch (e: Exception) {
            Log.e(TAG, "MEMORY_EXTRACT: failed session=$sessionId", e)
            // pending=true のまま → 次回起動時に再処理
        } finally {
            _isExtracting.value = false
        }
    }

    /**
     * LLM に事実抽出させて MemoryCandidate リストを返す
     */
    private suspend fun runLlmExtraction(
        sessionId: Long,
        messages: List<MessageEntity>,
        manager: ModelManager,
        config: InferenceConfig
    ): List<MemoryCandidate> {
        // ユーザーの入力のみを抽出対象とする
        val transcript = messages.filter { msg ->
            msg.role == "user"
        }.map { msg ->
            msg.content.trim()
        }.filter { it.isNotBlank() }.joinToString("\n")

        val prompt = buildString {
            append("次のJSONのみ出力せよ。他の文字は一切出力禁止。\n\n")
            append("[{\"content\":\"事実\",\"importance\":0.8}]\n\n")
            append("ユーザーの発言から記憶すべき事実（ユーザーの名前・好み・設定・状況など）を抽出して上記形式で出力せよ。\n")
            append("事実がなければ [] のみ出力せよ。\n\n")
            append("ユーザーの発言:\n")
            append(transcript)
        }

        val extractionConfig = config.forModelLoad().copy(
            temperature = 0.1f,
            contextCompressionEnabled = false
        )

        val raw = withTimeoutOrNull(EXTRACTION_TIMEOUT_MS) {
            // Create a temporary isolated session for memory extraction to avoid
            // contaminating the active chat session state.
            val tempSessionId = manager.sessionManager.createSession()
            try {
                val flow = manager.runInference(
                    sessionId = tempSessionId,
                    prompt = prompt,
                    config = extractionConfig
                )
                val builder = StringBuilder()
                flow.collect { chunk ->
                    val final = InferenceStreamProtocol.decodeFinal(chunk)
                    if (final != null) {
                        builder.clear(); builder.append(final)
                    } else if (chunk.isNotEmpty()) {
                        builder.append(chunk)
                    }
                }
                builder.toString().trim()
            } finally {
                manager.sessionManager.endSession(tempSessionId)
            }
        }

        if (raw.isNullOrBlank()) {
            Log.w(TAG, "MEMORY_EXTRACT: LLM returned empty for session=$sessionId")
            return emptyList()
        }

        return parseMemories(raw)
    }

    /**
     * JSON パース（防衛処理）
     * "[{"content":"...","importance":0.8}]" 形式
     */
    private fun parseMemories(response: String): List<MemoryCandidate> {
        return try {
            // JSON 配列部分だけ抽出（前後のゴミを除去）
            val jsonStr = Regex("""\[.*?\]""", RegexOption.DOT_MATCHES_ALL)
                .find(response)?.value ?: return emptyList()
            val array = JSONArray(jsonStr)
            (0 until array.length()).mapNotNull { i ->
                val obj: JSONObject = array.optJSONObject(i) ?: return@mapNotNull null
                val content = obj.optString("content", "").trim()
                if (content.length < 4) return@mapNotNull null
                val importance = obj.optDouble("importance", 0.7).toFloat().coerceIn(0f, 1f)
                MemoryCandidate(content, importance)
            }.distinctBy { it.content }.take(5)
        } catch (e: Exception) {
            Log.w(TAG, "MEMORY_EXTRACT: parse failed: $response", e)
            emptyList()
        }
    }
    private fun runRuleBasedExtraction(messages: List<MessageEntity>): List<MemoryCandidate> {
        val keywordPatterns = listOf(
            "名前", "好き", "嫌い", "趣味", "仕事", "社", "学生", "住んで", "出身", "誕生日", "年齢",
            "家族", "ペット", "旅行", "予定", "欲しい", "ほしい", "得意", "苦手", "好きです", "嫌いです"
        )
        return messages.asSequence()
            .filter { it.role == "user" }
            .mapNotNull { message ->
                val trimmed = message.content.trim()
                if (trimmed.length < 12) return@mapNotNull null
                val matchesKeyword = keywordPatterns.any { trimmed.contains(it, ignoreCase = true) }
                if (!matchesKeyword) return@mapNotNull null
                val candidateText = trimmed.lines()
                    .firstOrNull { line -> keywordPatterns.any { line.contains(it, ignoreCase = true) } }
                    ?.trim()
                    ?: trimmed
                MemoryCandidate(
                    content = candidateText.take(260),
                    importance = 0.56f
                )
            }
            .distinctBy { it.content }
            .take(5)
            .toList()
    }
    /**
     * 矛盾チェックして INSERT または UPDATE
     *
     * - similarity ≥ 0.95 → 重複スキップ
     * - 矛盾プロンプトで "UPDATE:ID1,ID2" → 既存を論理削除して INSERT
     * - それ以外 → INSERT
     */
    private suspend fun saveWithContradictionCheck(
        sessionId: Long,
        candidate: MemoryCandidate,
        manager: ModelManager,
        config: InferenceConfig,
        allowContradictionDeletion: Boolean = true,
        rgaPrevUid: String? = null
    ): String? {
        val embedding = MemoryTextEmbedder.embed(candidate.content)

        // 重複チェック（similarity ≥ 0.95 はスキップ）
        val nearest = memoryRepository.search(
            queryEmbedding = embedding,
            topK = 5,
            threshold = 0f,
            markAccessed = false
        )
        val duplicate = nearest.firstOrNull { it.similarity >= DUPLICATE_THRESHOLD }
        if (duplicate != null) {
            Log.d(TAG, "MEMORY_SAVE: duplicate skipped similarity=${duplicate.similarity} content=${candidate.content.take(30)}")
            return null
        }

        if (allowContradictionDeletion) {
            // 矛盾チェック対象（similarity 0.3〜0.94）
            val contradictionCandidates = nearest.filter { it.similarity in 0.3f..0.94f }
            if (contradictionCandidates.isNotEmpty()) {
                val existingText = contradictionCandidates
                    .joinToString("\n") { "ID${it.memory.id}: ${it.memory.content}" }
                val idsToDelete = runContradictionCheck(existingText, candidate.content, manager, config)
                for (id in idsToDelete) {
                    memoryRepository.softDelete(id)
                    Log.d(TAG, "MEMORY_CONTRADICTION: soft-deleted id=$id")
                }
            }
        } else {
            Log.d(TAG, "MEMORY_SAVE: skipping contradiction deletion for startup pending extraction")
        }

        val savedId = memoryRepository.saveMemory(
            content = candidate.content,
            embedding = embedding,
            importance = candidate.importance,
            source = "extracted",
            sessionId = sessionId.toString(),
            rgaPrevUid = rgaPrevUid
        )
        val savedUid = memoryRepository.getById(savedId)?.rgaUid
        Log.d(TAG, "MEMORY_SAVE: saved session=$sessionId content=${candidate.content.take(40)} rgaUid=$savedUid")
        return savedUid
    }

    /**
     * 矛盾解消プロンプト → "UPDATE:ID1,ID2" または "NEW"
     */
    private suspend fun runContradictionCheck(
        existingMemories: String,
        newMemory: String,
        manager: ModelManager,
        config: InferenceConfig
    ): List<Long> {
        val prompt = buildString {
            append("既存の記憶リストと新しい記憶を比較せよ。\n")
            append("出力は以下のいずれかのみ。他の文字は一切出力禁止。\n\n")
            append("- 矛盾する記憶がある場合: \"UPDATE:ID1,ID2\"（複数可）\n")
            append("- 矛盾がない場合: \"NEW\"\n\n")
            append("既存:\n$existingMemories\n\n")
            append("新規: \"$newMemory\"")
        }

        val raw = withTimeoutOrNull(CONTRADICTION_TIMEOUT_MS) {
            val contradictionConfig = config.forModelLoad().copy(
                temperature = 0.1f,
                contextCompressionEnabled = false
            )
            val tempSessionId = manager.sessionManager.createSession()
            try {
                val flow = manager.runInference(
                    sessionId = tempSessionId,
                    prompt = prompt,
                    config = contradictionConfig
                )
                val builder = StringBuilder()
                flow.collect { chunk ->
                    val final = InferenceStreamProtocol.decodeFinal(chunk)
                    if (final != null) { builder.clear(); builder.append(final) }
                    else if (chunk.isNotEmpty()) builder.append(chunk)
                }
                builder.toString().trim()
            } finally {
                manager.sessionManager.endSession(tempSessionId)
            }
        } ?: return emptyList()

        // "UPDATE:123,456" をパース
        val match = Regex("""UPDATE:([0-9,]+)""").find(raw) ?: return emptyList()
        return match.groupValues[1]
            .split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .also { Log.d(TAG, "MEMORY_CONTRADICTION: ids_to_delete=$it raw=$raw") }
    }

    data class MemoryCandidate(
        val content: String,
        val importance: Float
    )

    companion object {
        private const val TAG = "MemoryExtractionWorker"
        private const val EXTRACTION_TIMEOUT_MS = 30_000L
        private const val CONTRADICTION_TIMEOUT_MS = 15_000L
        private const val RECENT_TURN_LIMIT = 20
        private const val MIN_TOKENS = 30
        private const val DUPLICATE_THRESHOLD = 0.95f
    }
}
