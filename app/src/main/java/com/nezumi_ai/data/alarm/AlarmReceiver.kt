package com.nezumi_ai.data.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for handling alarm events triggered by AlarmManager
 */
class AlarmReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Alarm received: ${intent.action}")
        
        val alarmId = intent.getLongExtra("alarm_id", -1L)
        val label = intent.getStringExtra("label") ?: "Alarm"
        
        when (intent.action) {
            // Handle alarm action
            else -> {
                Log.d(TAG, "Unhandled alarm action: ${intent.action}")
            }
        }
    }
    
    companion object {
        private const val TAG = "AlarmReceiver"
    }
}
