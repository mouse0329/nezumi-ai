package com.nezumi_ai.data.tools

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.nezumi_ai.data.alarm.AlarmReceiver
import java.util.Calendar

object ToolSystemController {
    private const val TAG = "ToolSystemController"

    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String,
        skipUi: Boolean = true
    ): Result<Unit> {
        return runCatching {
            Log.d(TAG, "setAlarm: hour=$hour, minute=$minute, label=$label")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // カレンダーを用意して、指定時刻に設定
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                
                // 既に過ぎた時刻なら次の日に設定
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarm_id", label.hashCode().toLong())
                putExtra("label", label)
                action = "com.nezumi_ai.ALARM_ACTION_${label.hashCode()}"
            }
            
            val requestCode = label.hashCode()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // SCHEDULE_EXACT_ALARM 権限があれば exactAndAllowWhileIdle を使用
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.SCHEDULE_EXACT_ALARM
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Using setExactAndAllowWhileIdle")
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                // 権限がない場合は通常のセット（精密性は低下）
                Log.d(TAG, "Using setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
            
            Log.d(TAG, "Alarm set successfully: $calendar")
        }
    }

    fun dismissAlarm(context: Context, hour: Int, minute: Int): Result<Unit> {
        return runCatching {
            Log.d(TAG, "dismissAlarm: hour=$hour, minute=$minute")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // label が必要だが、ここでは使用できないため、hour:minute の組み合わせをキーに
            val label = "$hour:$minute"
            val requestCode = label.hashCode()
            
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            
            Log.d(TAG, "Alarm dismissed: $label")
        }
    }

    fun toggleFlashlight(context: Context, on: Boolean): Result<Unit> {
        return runCatching {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: throw IllegalStateException("flashlight_unavailable")
            manager.setTorchMode(cameraId, on)
        }
    }

    fun hasAlarmPermission(context: Context): Boolean {
        android.util.Log.d("ToolPerm", "hasAlarmPermission called")
        // ACTION_SET_ALARM Intent はSET_ALARMパーミッションのみで動作
        val setAlarmGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SET_ALARM
        ) == PackageManager.PERMISSION_GRANTED
        
        if (setAlarmGranted) {
            android.util.Log.d("ToolPerm", "SET_ALARM permission granted")
        } else {
            android.util.Log.d("ToolPerm", "SET_ALARM permission not granted")
        }
        
        return setAlarmGranted
    }

    fun hasFlashlightPermission(context: Context): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val flashlightGranted = ContextCompat.checkSelfPermission(
            context,
            "android.permission.FLASHLIGHT"
        ) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && flashlightGranted
    }

    fun getBatteryLevel(context: Context): Result<Map<String, Any>> {
        return runCatching {
            Log.d(TAG, "getBatteryLevel called")
            
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            
            if (batteryStatus != null) {
                val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                val voltage = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                val health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                
                val percentage = (level * 100) / scale
                
                val statusStr = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                    BatteryManager.BATTERY_STATUS_FULL -> "full"
                    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                    else -> "unknown"
                }
                
                val healthStr = when (health) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                    else -> "unknown"
                }
                
                val isPlugged = plugged != 0
                
                Log.d(TAG, "Battery: $percentage%, Status: $statusStr, Health: $healthStr, Plugged: $isPlugged")
                
                mapOf(
                    "percentage" to percentage,
                    "temperature_celsius" to (temp / 10),
                    "voltage_mv" to voltage,
                    "status" to statusStr,
                    "health" to healthStr,
                    "is_plugged" to isPlugged
                )
            } else {
                mapOf("error" to "battery_status_unavailable")
            }
        }
    }
}


