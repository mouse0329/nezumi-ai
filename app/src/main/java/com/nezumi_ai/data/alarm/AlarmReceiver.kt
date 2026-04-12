package com.nezumi_ai.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * アラーム発火時に実行される BroadcastReceiver
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("AlarmReceiver", "Alarm received at ${System.currentTimeMillis()}")
        
        intent?.let {
            val alarmId = it.getLongExtra("alarm_id", -1)
            val label = it.getStringExtra("label") ?: "Alarm"
            
            Log.d("AlarmReceiver", "Alarm ID: $alarmId, Label: $label")
            
            // TODO: アラーム音を再生、通知を表示、アクティビティを起動など
            // ここで実際のアラーム通知処理を実装
        }
    }
}
