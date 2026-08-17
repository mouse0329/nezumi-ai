package com.nezumi_ai.presentation.viewmodel.usecase

import com.nezumi_ai.data.repository.ChatSessionRepository
import com.nezumi_ai.presentation.viewmodel.buildSessionTitle

/**
 * クラスタ G (セッション/DB操作) のうち、タイトル生成まわりの純粋ロジックを切り出す。
 *
 * DB アクセスは必ず [ChatSessionRepository] 越しに行い、ViewModel が
 * `NezumiAiDatabase.getInstance(...)` を直接叩かないようにするための受け皿。
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
}
