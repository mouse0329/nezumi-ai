package com.nezumi_ai.data.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nezumi_ai.MainActivity
import com.nezumi_ai.R

/**
 * BroadcastReceiver for handling alarm events triggered by AlarmManager
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm received: ${intent.action}")
        
        val alarmId = intent.getLongExtra("alarm_id", -1L)
        val label = intent.getStringExtra("label") ?: "Alarm"
        val hour = intent.getIntExtra("hour", 0)
        val minute = intent.getIntExtra("minute", 0)
        
        Log.d(TAG, "Alarm triggered: $hour:${minute.toString().padStart(2, '0')} - $label")
        
        // 通知を表示
        showAlarmNotification(context, alarmId, label, hour, minute)
    }
    
    private fun showAlarmNotification(
        context: Context,
        alarmId: Long,
        label: String,
        hour: Int,
        minute: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        // 通知チャンネルの作成（Android 8.0以降）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alarm notifications"
                enableVibration(true)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    null
                )
            }
            notificationManager.createNotificationChannel(channel)
        }
        
        // アプリを開くIntent
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            alarmId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // 通知を作成
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(label)
            .setContentText("${hour}:${minute.toString().padStart(2, '0')}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            .setVibrate(longArrayOf(0, 1000, 500, 1000))
            .build()
        
        notificationManager.notify(alarmId.toInt(), notification)
        Log.d(TAG, "Alarm notification displayed: $label")
    }
    
    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "nezumi_ai_alarms"
    }
}
