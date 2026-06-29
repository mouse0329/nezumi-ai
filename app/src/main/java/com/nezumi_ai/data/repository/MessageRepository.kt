package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.MessageDao
import com.nezumi_ai.data.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

class MessageRepository(private val dao: MessageDao) {
    
    fun getMessagesForSession(sessionId: Long): Flow<List<MessageEntity>> =
        dao.getMessagesForSessionFlow(sessionId)

    suspend fun getMessagesForSessionOnce(sessionId: Long): List<MessageEntity> =
        dao.getMessagesForSession(sessionId)
    
    suspend fun addMessage(
        sessionId: Long,
        role: String,
        content: String,
        thinkingContent: String? = null,
        imageUri: String? = null,
        imageDescription: String? = null,  // Phase 12: 画像説明
        audioUri: String? = null,
        isStreaming: Boolean = false
    ): Long {
        val message = MessageEntity(
            sessionId = sessionId,
            role = role,
            content = content,
            thinkingContent = thinkingContent,
            imageUri = imageUri,
            imageDescription = imageDescription,  // Phase 12: 画像説明を保存
            audioUri = audioUri,
            timestamp = System.currentTimeMillis(),
            isStreaming = isStreaming
        )
        return dao.insert(message)
    }
    
    suspend fun getLastMessage(sessionId: Long): MessageEntity? =
        dao.getLastMessageInSession(sessionId)
    
    suspend fun deleteAllMessagesInSession(sessionId: Long) {
        dao.deleteBySessionId(sessionId)
    }

    suspend fun deleteMessageById(messageId: Long) {
        dao.deleteById(messageId)
    }

    /**
     * ★ Bug fix: 以前は thinkingContent を無条件代入していたため、以下の 2 つのバグが出ていた:
     *
     *   1. 生成完了後に Thinking 部分が消えるバグ
     *      - parser が最終パースで thinking=null を返したとき (重複ヒューリスティック等)、
     *        それまでストリーム中にためていた thinking が null 上書きされて消えてしまう。
     *   2. ストリーム中に一瞬 thinking が空になるフレームをパースしたところで DB がちらつく
     *
     * これを防ぐため「明示的に上書きしたい場合」と「既存を保持したい場合」を区別できるよう
     * sentinel オーバーロードを追加した。従来の呼び出し (thinkingContent を明示指定) は
     * そのまま上書き動作と互換。
     */
    suspend fun updateMessageContent(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        thinkingContent: String? = null,
        toolResultsJson: String? = null,
        generationTps: Float? = null,
        generationTimeMs: Long? = null
    ) {
        updateMessageContentInternal(
            messageId = messageId,
            content = content,
            isStreaming = isStreaming,
            thinkingProvided = true,
            thinkingContent = thinkingContent,
            toolResultsJson = toolResultsJson,
            generationTps = generationTps,
            generationTimeMs = generationTimeMs
        )
    }

    /**
     * ★ 新 API: thinkingContent に触れずに本文だけ更新したい場合をサポート。
     * ストリーム中に parser が一瞬 thinking=null を返しても DB 上の既存値を消さないようにする。
     */
    suspend fun updateMessageContentPreservingThinking(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        toolResultsJson: String? = null,
        generationTps: Float? = null,
        generationTimeMs: Long? = null
    ) {
        updateMessageContentInternal(
            messageId = messageId,
            content = content,
            isStreaming = isStreaming,
            thinkingProvided = false,
            thinkingContent = null,
            toolResultsJson = toolResultsJson,
            generationTps = generationTps,
            generationTimeMs = generationTimeMs
        )
    }

    private suspend fun updateMessageContentInternal(
        messageId: Long,
        content: String,
        isStreaming: Boolean,
        thinkingProvided: Boolean,
        thinkingContent: String?,
        toolResultsJson: String?,
        generationTps: Float?,
        generationTimeMs: Long?
    ) {
        try {
            android.util.Log.d(
                "MessageRepository",
                "updateMessageContent: start messageId=$messageId isStreaming=$isStreaming contentLen=${content.length} thinkingProvided=$thinkingProvided"
            )
            val current = dao.getMessageById(messageId) ?: run {
                android.util.Log.w("MessageRepository", "updateMessageContent: message not found messageId=$messageId")
                return
            }
            // ★ Bug fix: 生成完了後に Thinking が消えるバグへのためのガード。
            //   - thinkingProvided=true かつ明示的に不ストリーム完了中 (最終 finalize) で
            //     thinkingContent が null だった場合も、既存の thinking があればそのまま保持する。
            //     これにより、parser が途中で thinking=null を返しても UI の Thinking ブロックが
            //     消えない (トグルで閉じられる仕様が生きる)。
            //   - 明示的にクリアしたい場合は updateMessageContentPreservingThinking を使わないで
            //     updateMessageContent(他パラメータ、thinkingContent = "") と空文字列を渡せばよい。
            val resolvedThinking: String? = when {
                !thinkingProvided -> current.thinkingContent
                thinkingContent.isNullOrBlank() -> current.thinkingContent
                else -> thinkingContent
            }
            dao.update(
                current.copy(
                    content = content,
                    thinkingContent = resolvedThinking,
                    isStreaming = isStreaming,
                    toolResultsJson = toolResultsJson ?: current.toolResultsJson,
                    generationTps = generationTps ?: current.generationTps,
                    generationTimeMs = generationTimeMs ?: current.generationTimeMs
                )
            )
            android.util.Log.d("MessageRepository", "updateMessageContent: complete messageId=$messageId")
        } catch (t: Throwable) {
            android.util.Log.e("MessageRepository", "updateMessageContent: failed for messageId=$messageId", t)
            throw t
        }
    }

    suspend fun updateMessageMedia(
        messageId: Long,
        imageUri: String? = null,
        audioUri: String? = null
    ) {
        val current = dao.getMessageById(messageId) ?: return
        dao.update(current.copy(
            imageUri = imageUri ?: current.imageUri,
            audioUri = audioUri ?: current.audioUri
        ))
    }
    
    /**
     * Phase 13: 画像と説明文の整合性を保つ更新
     * imageUri が null/空 になった場合、imageDescription も自動的に削除
     */
    suspend fun updateMessageImageWithDescription(
        messageId: Long,
        imageUri: String?,
        imageDescription: String? = null
    ) {
        val current = dao.getMessageById(messageId) ?: return
        val finalDescription = if (imageUri.isNullOrEmpty()) {
            null  // 画像が削除されたら説明文も削除
        } else {
            imageDescription ?: current.imageDescription  // 新しい説明文があれば更新、なければ既存を保持
        }
        dao.update(current.copy(
            imageUri = imageUri,
            imageDescription = finalDescription
        ))
    }

    suspend fun hasMediaContent(messageId: Long): Boolean {
        val message = dao.getMessageById(messageId) ?: return false
        return message.imageUri != null || message.audioUri != null
    }

    suspend fun getMessageById(messageId: Long): MessageEntity? =
        dao.getMessageById(messageId)
    
    /**
     * Phase 13: アプリ起動時に isStreaming フラグをクリーニング
     * 前回のアプリ実行時に isStreaming = true のまま終了した場合、
     * それらを false に修正する（ゾンビストリーミング状態を防止）
     */
    suspend fun cleanupStreamingFlags(): Int {
        val allMessages = dao.getAllMessages()
        var fixedCount = 0
        
        for (msg in allMessages) {
            if (msg.isStreaming) {
                dao.update(msg.copy(isStreaming = false))
                fixedCount++
            }
        }
        
        if (fixedCount > 0) {
            android.util.Log.w("MessageRepository", "STARTUP_CLEANUP: Fixed $fixedCount messages with isStreaming=true -> false")
        }
        
        return fixedCount
    }
}
