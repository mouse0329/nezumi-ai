package com.nezumi_ai.platform

/**
 * 画像生成の通知。
 * Android では NotificationManager、iOS では UNUserNotificationCenter 等で実装する。
 */
interface PlatformNotification {
    fun showProgress(step: Int, totalSteps: Int, promptPreview: String, indeterminate: Boolean = false)
    fun showQueueProgress(itemIndex: Int, totalItems: Int, step: Int, totalSteps: Int, promptPreview: String)
    fun showCompleted(title: String, text: String)
    fun showQueueCompleted(title: String, text: String)
    fun showError(title: String, text: String)
    fun showQueueError(title: String, text: String)
    fun cancel()
    fun cancelQueue()
}
