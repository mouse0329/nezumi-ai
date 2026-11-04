package com.nezumi_ai.platform

import android.app.Application
import com.nezumi_ai.data.inference.ImageGenerationNotificationManager

class AndroidPlatformNotification(private val app: Application) : PlatformNotification {
    override fun showProgress(step: Int, totalSteps: Int, promptPreview: String, indeterminate: Boolean) {
        ImageGenerationNotificationManager.showSingleProgress(
            app, step, totalSteps, promptPreview, indeterminate
        )
    }

    override fun showQueueProgress(
        itemIndex: Int,
        totalItems: Int,
        step: Int,
        totalSteps: Int,
        promptPreview: String
    ) {
        ImageGenerationNotificationManager.showQueueProgress(
            app, itemIndex, totalItems, step, totalSteps, promptPreview
        )
    }

    override fun showCompleted(title: String, text: String) {
        ImageGenerationNotificationManager.showCompleted(
            app,
            ImageGenerationNotificationManager.singleNotificationId(),
            title,
            text
        )
    }

    override fun showQueueCompleted(title: String, text: String) {
        ImageGenerationNotificationManager.showCompleted(
            app,
            ImageGenerationNotificationManager.queueNotificationId(),
            title,
            text
        )
    }

    override fun showError(title: String, text: String) {
        ImageGenerationNotificationManager.showError(
            app,
            ImageGenerationNotificationManager.singleNotificationId(),
            title,
            text
        )
    }

    override fun showQueueError(title: String, text: String) {
        ImageGenerationNotificationManager.showError(
            app,
            ImageGenerationNotificationManager.queueNotificationId(),
            title,
            text
        )
    }

    override fun cancel() {
        ImageGenerationNotificationManager.cancelSingle(app)
    }

    override fun cancelQueue() {
        ImageGenerationNotificationManager.cancelQueue(app)
    }
}
