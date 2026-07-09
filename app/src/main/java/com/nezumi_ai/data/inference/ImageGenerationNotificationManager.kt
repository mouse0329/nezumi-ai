package com.nezumi_ai.data.inference

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nezumi_ai.R
import kotlin.math.abs

object ImageGenerationNotificationManager {
    private const val CHANNEL_ID = "image_generation_channel"
    private const val CHANNEL_NAME = "Image Generation"
    private const val CHANNEL_DESCRIPTION = "Background image generation progress"

    private const val SINGLE_NOTIFICATION_ID = 9101
    private const val QUEUE_NOTIFICATION_ID = 9102
    private const val CHAT_TOOL_NOTIFICATION_ID = 9103

    fun showSingleProgress(
        context: Context,
        step: Int,
        totalSteps: Int,
        promptPreview: String,
        indeterminate: Boolean = false
    ) {
        val safeTotal = totalSteps.coerceAtLeast(1)
        val safeStep = step.coerceIn(0, safeTotal)
        notify(
            context = context,
            notificationId = SINGLE_NOTIFICATION_ID,
            title = "画像生成中",
            text = if (indeterminate) promptPreview else "$safeStep / $safeTotal steps ・ $promptPreview",
            progress = if (indeterminate) null else safeStep to safeTotal,
            ongoing = true,
            autoCancel = false
        )
    }

    fun showQueueProgress(
        context: Context,
        itemIndex: Int,
        totalItems: Int,
        step: Int,
        totalSteps: Int,
        promptPreview: String
    ) {
        val safeItems = totalItems.coerceAtLeast(1)
        val safeIndex = itemIndex.coerceIn(1, safeItems)
        val safeStep = step.coerceAtLeast(0)
        val safeTotalSteps = totalSteps.coerceAtLeast(1)
        notify(
            context = context,
            notificationId = QUEUE_NOTIFICATION_ID,
            title = "画像生成キュー実行中",
            text = "$safeIndex / $safeItems 枚目 ・ $safeStep / $safeTotalSteps steps ・ $promptPreview",
            progress = safeStep.coerceAtMost(safeTotalSteps) to safeTotalSteps,
            ongoing = true,
            autoCancel = false
        )
    }

    fun showChatToolProgress(
        context: Context,
        step: Int,
        totalSteps: Int,
        promptPreview: String
    ) {
        val safeTotal = totalSteps.coerceAtLeast(1)
        val safeStep = step.coerceIn(0, safeTotal)
        notify(
            context = context,
            notificationId = CHAT_TOOL_NOTIFICATION_ID,
            title = "チャット画像生成中",
            text = "$safeStep / $safeTotal steps ・ $promptPreview",
            progress = safeStep to safeTotal,
            ongoing = true,
            autoCancel = false
        )
    }

    fun showCompleted(context: Context, notificationId: Int, title: String, text: String) {
        notify(
            context = context,
            notificationId = notificationId,
            title = title,
            text = text,
            progress = null,
            ongoing = false,
            autoCancel = true
        )
    }

    fun showError(context: Context, notificationId: Int, title: String, text: String) {
        notify(
            context = context,
            notificationId = notificationId,
            title = title,
            text = text,
            progress = null,
            ongoing = false,
            autoCancel = true,
            highPriority = true
        )
    }

    fun cancelSingle(context: Context) = cancel(context, SINGLE_NOTIFICATION_ID)
    fun cancelQueue(context: Context) = cancel(context, QUEUE_NOTIFICATION_ID)
    fun cancelChatTool(context: Context) = cancel(context, CHAT_TOOL_NOTIFICATION_ID)

    fun singleNotificationId(): Int = SINGLE_NOTIFICATION_ID
    fun queueNotificationId(): Int = QUEUE_NOTIFICATION_ID
    fun chatToolNotificationId(): Int = CHAT_TOOL_NOTIFICATION_ID

    private fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun notify(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        progress: Pair<Int, Int>?,
        ongoing: Boolean,
        autoCancel: Boolean,
        highPriority: Boolean = false
    ) {
        ensureChannel(context)
        if (!canPostNotifications(context)) return

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setContentIntent(createLaunchIntent(context))
            .setPriority(
                if (highPriority) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW
            )

        if (progress != null) {
            builder.setProgress(progress.second, progress.first, false)
        } else {
            builder.setProgress(0, 0, false)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = CHANNEL_DESCRIPTION
        }
        manager.createNotificationChannel(channel)
    }

    private fun createLaunchIntent(context: Context): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ?: return null
        val requestCode = abs(launchIntent.filterHashCode())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getActivity(context, requestCode, launchIntent, flags)
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }
}
