package com.nezumi_ai.data.repository

/**
 * セッション作成後にチャット履歴の最大保存数を強制するためのフック。
 * Android の SettingsRepository が実装する。iOS 側は別実装を差し込む。
 */
interface ChatHistoryLimitEnforcer {
    suspend fun enforceChatHistoryLimit()
}
