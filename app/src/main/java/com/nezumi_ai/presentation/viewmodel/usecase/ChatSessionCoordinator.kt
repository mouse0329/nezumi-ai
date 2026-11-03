package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.presentation.viewmodel.DEFAULT_SESSION_TITLE
import com.nezumi_ai.presentation.viewmodel.buildSessionTitle

/**
 * クラスタ G (セッション/DB操作) のタイトル生成・更新を ViewModel から切り出す。
 *
 * DB アクセスは必ず [ChatSessionRepository] 越し。
 * UI 向け StateFlow 更新は呼び出し側 (ChatViewModel) が行う。
 */
class ChatSessionCoordinator(
    private val sessionRepository: ChatSessionRepository
) {
    /**
     * ユーザー発話と AI 応答からセッションタイトル候補を組み立てる。
     * 実際の組み立てルールは [buildSessionTitle] (SessionTitleUtils) に集約済み。
     */
    fun buildTitleFromExchange(userMessage: String, aiResponse: String): String =
        buildSessionTitle(userMessage, aiResponse)

    /** セッションの最終更新時刻を現在に進める (再生成・送信後の並び替え用)。 */
    suspend fun touchSession(sessionId: Long) {
        sessionRepository.updateSessionLastUpdated(sessionId)
    }

    /** DB 上のセッション名。存在しなければ null。 */
    suspend fun readSessionName(sessionId: Long): String? =
        sessionRepository.getSessionById(sessionId)?.name

    /**
     * タイトルがまだデフォルトのときだけ、ユーザー発話からタイトルを立てる。
     * @return 更新後のタイトル。更新しなければ null。
     */
    suspend fun maybeUpdateTitleFromUserMessage(
        sessionId: Long,
        userMessage: String,
        defaultTitle: String = DEFAULT_SESSION_TITLE
    ): String? {
        if (userMessage.isBlank()) return null
        val session = sessionRepository.getSessionById(sessionId) ?: return null
        if (session.name.trim() != defaultTitle) return null
        val title = buildSessionTitle(userMessage, "")
        if (title.isBlank() || title == defaultTitle) return null
        sessionRepository.updateSessionName(sessionId, title)
        return title
    }

    /**
     * タイトルがまだデフォルトのときだけ、ユーザー発話 + AI 応答からタイトルを立てる。
     * @return 更新後のタイトル。更新しなければ null。
     */
    suspend fun maybeGenerateTitle(
        sessionId: Long,
        userMessage: String,
        aiResponse: String,
        defaultTitle: String = DEFAULT_SESSION_TITLE
    ): String? {
        val session = sessionRepository.getSessionById(sessionId) ?: return null
        if (session.name.trim() != defaultTitle) return null
        val title = buildSessionTitle(userMessage, aiResponse)
        if (title.isBlank() || title == defaultTitle) return null
        sessionRepository.updateSessionName(sessionId, title)
        return title
    }
}
