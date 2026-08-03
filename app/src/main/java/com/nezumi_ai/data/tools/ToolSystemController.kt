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
import java.util.TimerTask

object ToolSystemController {
    private const val TAG = "ToolSystemController"

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

                val timerInstance = Timer()
                timerInstance.schedule(object : TimerTask() {
                    override fun run() {
                        Log.d(TAG, "Timer $timerId ($name) finished")
                        synchronized(timers) { timers.remove(timerId) }
                    }
                }, durationSeconds * 1000)

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
        label: String
    ): Result<Unit> {
        return runCatching {
            Log.d(TAG, "setAlarm: hour=$hour, minute=$minute, label=$label")

            // ACTION_SET_ALARM でシステム時計アプリに直接登録
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_VIBRATE, true)
                // SKIP_UI=true にすることで、UIを表示せずに直接アラームを設定
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 時計アプリが存在するか確認（複数の方法で試行）
            val packageManager = context.packageManager
            
            // 方法1: resolveActivity() で確認
            val resolvedActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            
            Log.d(TAG, "resolveActivity result: $resolvedActivity")
            
            // 方法2: queryIntentActivities() で確認
            val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            
            Log.d(TAG, "queryIntentActivities found ${activities.size} handlers")
            activities.forEach { resolveInfo ->
                Log.d(TAG, "  - ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
            }

            if (resolvedActivity != null || activities.isNotEmpty()) {
                try {
                    context.startActivity(intent)
                    Log.d(TAG, "System alarm registered via ACTION_SET_ALARM: $hour:${minute.toString().padStart(2, '0')} label=$label")
                } catch (e: SecurityException) {
                    Log.e(TAG, "SecurityException when starting alarm activity", e)
                    throw IllegalStateException("Permission denied: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Exception when starting alarm activity", e)
                    throw IllegalStateException("Failed to start alarm activity: ${e.message}")
                }
            } else {
                val errorMsg = "No clock app found to handle ACTION_SET_ALARM"
                Log.e(TAG, errorMsg)
                throw IllegalStateException(errorMsg)
            }
        }
    }



    fun dismissAlarm(context: Context, hour: Int, minute: Int): Result<Unit> {
        return runCatching {
            Log.d(TAG, "dismissAlarm: hour=$hour, minute=$minute")
            
            // ACTION_DISMISS_ALARM でシステム時計アプリのアラームを削除
            val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            val packageManager = context.packageManager
            val resolvedActivity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }
            
            if (resolvedActivity != null) {
                context.startActivity(intent)
                Log.d(TAG, "System alarm dismissed via ACTION_DISMISS_ALARM: $hour:${minute.toString().padStart(2, '0')}")
            } else {
                Log.w(TAG, "No clock app found to handle ACTION_DISMISS_ALARM")
                // フォールバック: SHOW_ALARMS でアラーム一覧を表示
                val showAlarmsIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val showAlarmsResolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.resolveActivity(
                        showAlarmsIntent,
                        PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.resolveActivity(showAlarmsIntent, PackageManager.MATCH_DEFAULT_ONLY)
                }
                
                if (showAlarmsResolved != null) {
                    context.startActivity(showAlarmsIntent)
                    Log.d(TAG, "Opened alarm list via ACTION_SHOW_ALARMS for manual deletion")
                } else {
                    throw IllegalStateException("No clock app found")
                }
            }
        }
    }

    /**
     * カメラ権限の有無をチェックするヘルパー。
     * フラッシュライトの setTorchMode は実際にはカメラ権限を必要とするため、
     * UI 側で事前にリクエストしてもらいやすくする。
     */
    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun toggleFlashlight(context: Context, on: Boolean): Result<Unit> {
        return runCatching {
            Log.d(TAG, "toggleFlashlight: on=$on")
            if (!hasCameraPermission(context)) {
                throw SecurityException("CAMERA permission is required to use flashlight")
            }
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


