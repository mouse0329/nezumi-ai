package com.nezumi_ai.data.repository

import com.nezumi_ai.data.database.dao.ChatSessionDao
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

class ChatSessionRepository(
    private val dao: ChatSessionDao,
    private val settingsRepository: SettingsRepository? = null,
    private val messageRepository: MessageRepository? = null
) {
    
    fun getAllSessions(): Flow<List<ChatSessionEntity>> = dao.getAllSessionsFlow()
    
    suspend fun getSessionById(sessionId: Long): ChatSessionEntity? =
        dao.getSessionById(sessionId)
    
    suspend fun createSession(name: String, isIncognito: Boolean = false): Long {
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            name = name,
            createDate = now,
            lastUpdated = now,
            selectedModel = "E2B",
            isIncognito = isIncognito
        )
        val newId = dao.insert(session)
        // Bug fix: 新規セッション作成時に最大保存数を強制して古いセッションを古い順に削除させる。
        // 作成したばかりのセッションを誤って消さないよう、 incognito 以外のときだけ実行する。
        if (!isIncognito) {
            runCatching { settingsRepository?.enforceChatHistoryLimit() }
        }
        return newId
    }
    
    suspend fun updateSessionLastUpdated(sessionId: Long) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(lastUpdated = System.currentTimeMillis()))
    }
    
    suspend fun deleteSession(sessionId: Long) {
        dao.deleteById(sessionId)
    }

    /**
     * セッション削除前に添付ファイル (画像 / 音声 / 動画) を掃除してから削除する。
     * Room の CASCADE で DB レコードは消えるが、ファイル本体 (特に message_media に
     * コピーした動画) は手で消さないとストレージに残り続けることへの対応。
     *
     * cleanupAttachments は MessageEntity.imageUri / audioUri を受け取り、
     * MessageMediaStore.deleteMessageAttachments を呼ぶ想定。
     */
    suspend fun deleteSessionWithAttachments(
        sessionId: Long,
        cleanupAttachments: (imageUri: String?, audioUri: String?) -> Unit
    ) {
        messageRepository?.let { repo ->
            runCatching {
                val msgs = repo.getMessagesForSessionOnce(sessionId)
                msgs.forEach { m -> cleanupAttachments(m.imageUri, m.audioUri) }
            }.onFailure {
                android.util.Log.w("ChatSessionRepository", "cleanupAttachments failed for session=$sessionId", it)
            }
        }
        dao.deleteById(sessionId)
    }
    
    suspend fun updateSessionModel(sessionId: Long, model: String) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(selectedModel = model))
    }

    suspend fun updateSessionName(sessionId: Long, name: String) {
        val session = dao.getSessionById(sessionId) ?: return
        dao.update(session.copy(name = name, lastUpdated = System.currentTimeMillis()))
    }

    suspend fun togglePinSession(sessionId: Long) {
        val session = dao.getSessionById(sessionId) ?: return
        val newPinState = !session.isPinned
        dao.update(session.copy(isPinned = newPinState))
        android.util.Log.d("ChatSessionRepository", "togglePinSession: sessionId=$sessionId isPinned=$newPinState")
    }

    suspend fun getLatestSession(): ChatSessionEntity? {
        val sessions = dao.getAllSessions()
        return sessions.maxByOrNull { it.lastUpdated }
    }


    suspend fun getAllSessionsOnce(): List<ChatSessionEntity> = dao.getAllSessions()

    suspend fun deleteAllIncognitoSessions() {
        // Delete sessions marked as incognito
        val allSessions = dao.getAllSessionsIncludingIncognito()
        allSessions.filter { it.isIncognito == true }.forEach {
            messageRepository?.deleteAllMessagesInSession(it.id)
            deleteSession(it.id)
        }
    }

    /**
     * インコグニトセッションを一括削除するときにも添付ファイルを掃除するバージョン。
     */
    suspend fun deleteAllIncognitoSessionsWithAttachments(
        cleanupAttachments: (imageUri: String?, audioUri: String?) -> Unit
    ) {
        val allSessions = dao.getAllSessionsIncludingIncognito()
        allSessions.filter { it.isIncognito == true }.forEach { session ->
            messageRepository?.let { repo ->
                runCatching {
                    val msgs = repo.getMessagesForSessionOnce(session.id)
                    msgs.forEach { m -> cleanupAttachments(m.imageUri, m.audioUri) }
                }
                repo.deleteAllMessagesInSession(session.id)
            }
            deleteSession(session.id)
        }
    }
}
