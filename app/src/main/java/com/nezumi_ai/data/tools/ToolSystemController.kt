package com.nezumi_ai.data.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.provider.AlarmClock
import androidx.core.content.ContextCompat
import com.nezumi_ai.data.alarm.AlarmReceiver
import java.util.Calendar
import java.util.Timer
import kotlin.concurrent.timer

object ToolSystemController {
    private const val TAG = "ToolSystemController"
    private fun alarmRequestCode(hour: Int, minute: Int): Int = hour * 100 + minute

    // ===== Timer Management (Session-only, In-Memory) =====
    object TimerManager {
        private data class TimerEntry(
            val id: String,
            val name: String,
            val durationSeconds: Long,
            val startTimeMs: Long,
            val timerInstance: Timer
        )

        private val timers = mutableMapOf<String, TimerEntry>()
        private var nextTimerId = 1

        fun startTimer(context: Context, durationSeconds: Long, label: String = ""): Map<String, Any> {
            return synchronized(timers) {
                val timerId = "timer_${nextTimerId++}"
                val name = label.takeIf { it.isNotBlank() } ?: "Timer ${timers.size + 1}"
                val startTime = System.currentTimeMillis()

                // システムのタイマーアプリに登録（ACTION_SET_TIMER）
                try {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, durationSeconds.toInt())
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, name)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val resolved = context.packageManager.resolveActivity(
                        intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                    )
                    if (resolved != null) {
                        context.startActivity(intent)
                        Log.d(TAG, "Timer registered via ACTION_SET_TIMER: ${durationSeconds}s label=$name")
                    } else {
                        Log.w(TAG, "No clock app for ACTION_SET_TIMER, using in-memory only")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to register system timer", e)
                }

                val timerInstance = kotlin.concurrent.timer(
                    initialDelay = durationSeconds * 1000,
                    period = 0
                ) {
                    Log.d(TAG, "Timer $timerId ($name) finished")
                    synchronized(timers) { timers.remove(timerId) }
                }

                timers[timerId] = TimerEntry(timerId, name, durationSeconds, startTime, timerInstance)
                Log.d(TAG, "Timer started: $timerId, duration: $durationSeconds seconds")

                mapOf(
                    "success" to true,
                    "timerId" to timerId,
                    "label" to name,
                    "durationSeconds" to durationSeconds,
                    "timer_id" to timerId,
                    "name" to name,
                    "duration_seconds" to durationSeconds
                )
            }
        }

        fun stopTimer(timerId: String): Map<String, Any> {
            return synchronized(timers) {
                val timer = timers.remove(timerId)
                if (timer != null) {
                    timer.timerInstance.cancel()  // Timer インスタンス自体をキャンセル
                    Log.d(TAG, "Timer stopped: $timerId")
                    val elapsedSeconds = ((System.currentTimeMillis() - timer.startTimeMs) / 1000)
                    mapOf(
                        "success" to true,
                        // canonical (UI/Schema)
                        "timerId" to timerId,
                        "elapsedSeconds" to elapsedSeconds,
                        // backward compatible keys
                        "timer_id" to timerId,
                        "elapsed_seconds" to elapsedSeconds
                    )
                } else {
                    mapOf(
                        "success" to false,
                        "error" to "timer_not_found",
                        // canonical
                        "timerId" to timerId,
                        // backward compatible
                        "timer_id" to timerId
                    )
                }
            }
        }

        fun listTimers(): Map<String, Any> {
            return synchronized(timers) {
                val timerList = timers.values.map { t ->
                    val elapsedSeconds = (System.currentTimeMillis() - t.startTimeMs) / 1000
                    val remainingSeconds = maxOf(0, t.durationSeconds - elapsedSeconds)
                    mapOf(
                        // canonical (UI/Schema)
                        "timerId" to t.id,
                        "label" to t.name,
                        "durationSeconds" to t.durationSeconds,
                        "elapsedSeconds" to elapsedSeconds,
                        "remainingSeconds" to remainingSeconds,
                        // backward compatible
                        "timer_id" to t.id,
                        "name" to t.name,
                        "duration_seconds" to t.durationSeconds,
                        "elapsed_seconds" to elapsedSeconds,
                        "remaining_seconds" to remainingSeconds
                    )
                }
                mapOf(
                    "success" to true,
                    "count" to timerList.size,
                    "timers" to timerList
                )
            }
        }
    }
    // ===== End Timer Management =====

    fun setAlarm(
        context: Context,
        hour: Int,
        minute: Int,
        label: String,
        skipUi: Boolean = true
    ): Result<Unit> {
        return runCatching {
            Log.d(TAG, "setAlarm: hour=$hour, minute=$minute, label=$label")

            // ACTION_SET_ALARM でシステム時計アプリに直接登録（UIなし）
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val resolved = context.packageManager.resolveActivity(
                intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (resolved != null) {
                context.startActivity(intent)
                Log.d(TAG, "Alarm registered via ACTION_SET_ALARM: $hour:${minute.toString().padStart(2, '0')}")
            } else {
                Log.w(TAG, "No clock app found, falling back to AlarmManager")
                setAlarmViaAlarmManager(context, hour, minute, label)
            }
        }
    }

    private fun setAlarmViaAlarmManager(
        context: Context,
        hour: Int,
        minute: Int,
        label: String
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", label.hashCode().toLong())
            putExtra("label", label)
            action = "com.nezumi_ai.ALARM_ACTION_${alarmRequestCode(hour, minute)}"
        }
        val requestCode = alarmRequestCode(hour, minute)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            alarmManager.canScheduleExactAlarms()
        ) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            Log.d(TAG, "Alarm set via setExactAndAllowWhileIdle: $calendar")
        } else {
            alarmManager.set(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
            Log.d(TAG, "Alarm set via set: $calendar")
        }
    }

    fun dismissAlarm(context: Context, hour: Int, minute: Int): Result<Unit> {
        return runCatching {
            Log.d(TAG, "dismissAlarm: hour=$hour, minute=$minute")
            
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            val requestCode = alarmRequestCode(hour, minute)
            
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            
            Log.d(TAG, "Alarm dismissed: ${hour}:${minute.toString().padStart(2, '0')} rc=$requestCode")
        }
    }

    fun toggleFlashlight(context: Context, on: Boolean): Result<Unit> {
        return runCatching {
            Log.d(TAG, "toggleFlashlight: on=$on")
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraIdList = manager.cameraIdList
            
            if (cameraIdList.isEmpty()) {
                throw IllegalStateException("No cameras available on this device")
            }
            
            // フラッシュライト機能を持つカメラを探す
            var flashCameraId: String? = null
            for (id in cameraIdList) {
                try {
                    val chars = manager.getCameraCharacteristics(id)
                    val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
                    
                    Log.d(TAG, "Camera $id: facing=$facing, hasFlash=$hasFlash")
                    
                    // バックカメラでフラッシュを持つものを優先
                    if (hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        flashCameraId = id
                        break
                    }
                    // フロントカメラも候補にする
                    if (hasFlash && flashCameraId == null) {
                        flashCameraId = id
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking camera $id: ${e.message}")
                }
            }
            
            if (flashCameraId == null) {
                throw IllegalStateException("No camera with flashlight capability found")
            }
            
            Log.d(TAG, "Using flashlight on camera: $flashCameraId, turning ${if (on) "ON" else "OFF"}")
            try {
                manager.setTorchMode(flashCameraId, on)
                Log.d(TAG, "Flashlight ${if (on) "turned on" else "turned off"} successfully")
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Camera access exception: ${e.message}")
                throw IllegalStateException("Camera access denied or in use by another app")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set torch mode: ${e.message}", e)
                throw e
            }
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
        
        Log.d(TAG, "hasFlashlightPermission: CAMERA=$cameraGranted, FLASHLIGHT=$flashlightGranted")
        
        // FLASHLIGHT権限は自動付与されることもあるため、CAMERAのみをチェック
        // （実際のデバイスではFLASHLIGHTは単なるハードウェア機能）
        return cameraGranted
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
                    // canonical (UI expects "level")
                    "level" to percentage,
                    // richer fields
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


