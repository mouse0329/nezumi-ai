package com.nezumi_ai.data.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolCall
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import com.nezumi_ai.data.database.dao.AlarmDao
import com.nezumi_ai.data.database.entity.AlarmEntity
import com.nezumi_ai.data.tools.ToolSystemController
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TOOL_TAG = "NezumiTools"

internal data class ToolExecutionResult(
    val success: Boolean,
    val payload: Map<String, Any?>
)

internal fun buildEnabledToolProviders(context: Context, alarmDao: AlarmDao): List<ToolProvider> {
    val enabled = ToolPreferences(context).getEnabledTools()
    return buildList {
        if (NezumiTool.GET_TIME in enabled) add(tool(GetTimeToolSet()))
        if (NezumiTool.GET_BATTERY in enabled) add(tool(GetBatteryToolSet(context)))
        if (NezumiTool.SET_ALARM in enabled) add(tool(SetAlarmToolSet(context, alarmDao)))
        if (NezumiTool.DISMISS_ALARM in enabled) add(tool(DismissAlarmToolSet(context, alarmDao)))
        if (NezumiTool.LIST_ALARMS in enabled && NezumiTool.SET_ALARM in enabled) {
            add(tool(ListAlarmsToolSet(alarmDao)))
        }
        if (NezumiTool.FLASHLIGHT in enabled) add(tool(FlashlightToolSet(context)))
    }
}

internal class NezumiLiteRtToolExecutor(
    private val context: Context,
    private val alarmDao: AlarmDao
) {
    suspend fun execute(toolCall: ToolCall): ToolExecutionResult {
        return when (normalizeToolName(toolCall.name)) {
            "getcurrenttime" -> executeGetCurrentTime(toolCall)
            "getbatterylevel" -> executeGetBatteryLevel()
            "setalarm" -> executeSetAlarm(toolCall)
            "dismissalarm" -> executeDismissAlarm(toolCall)
            "listalarms" -> executeListAlarms()
            "setflashlight", "flashlight" -> executeSetFlashlight(toolCall)
            else -> ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "unknown_tool:${toolCall.name}"
                )
            )
        }
    }

    private suspend fun executeGetCurrentTime(toolCall: ToolCall): ToolExecutionResult {
        val timezone = toolCall.arguments["timezone"]?.toString()?.takeIf { it.isNotBlank() }
        val zone = runCatching {
            if (timezone != null) ZoneId.of(timezone) else ZoneId.systemDefault()
        }.getOrElse {
            return ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "invalid_timezone"
                )
            )
        }
        val now = ZonedDateTime.now(zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "datetime" to now.format(fmt),
                "timezone" to zone.id,
                "timestamp_ms" to now.toInstant().toEpochMilli().toString()
            )
        )
    }

    private suspend fun executeGetBatteryLevel(): ToolExecutionResult {
        val result = ToolSystemController.getBatteryLevel(context)
        return if (result.isSuccess) {
            ToolExecutionResult(
                success = true,
                payload = result.getOrElse { emptyMap() }
            )
        } else {
            ToolExecutionResult(
                success = false,
                payload = mapOf("error" to "battery_status_failed:${result.exceptionOrNull()?.message.orEmpty()}")
            )
        }
    }

    private suspend fun executeSetAlarm(toolCall: ToolCall): ToolExecutionResult {
        if (!ToolSystemController.hasAlarmPermission(context)) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "permission_denied"))
        }
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_time"))
        }
        val label = toolCall.arguments["label"]?.toString()?.takeIf { it.isNotBlank() } ?: "nezumi-ai alarm"
        val intentResult = ToolSystemController.setAlarm(context, hour, minute, label)
        if (intentResult.isFailure) {
            return ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "set_alarm_failed:${intentResult.exceptionOrNull()?.message.orEmpty()}")
            )
        }
        withContext(Dispatchers.IO) {
            alarmDao.insert(AlarmEntity(hour = hour, minute = minute, label = label, enabled = true))
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf("success" to true, "hour" to hour, "minute" to minute, "label" to label)
        )
    }

    private suspend fun executeDismissAlarm(toolCall: ToolCall): ToolExecutionResult {
        if (!ToolSystemController.hasAlarmPermission(context)) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "permission_denied"))
        }
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_time"))
        }
        val intentResult = ToolSystemController.dismissAlarm(context, hour, minute)
        if (intentResult.isFailure) {
            return ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "dismiss_alarm_failed:${intentResult.exceptionOrNull()?.message.orEmpty()}")
            )
        }
        withContext(Dispatchers.IO) {
            alarmDao.delete(hour, minute)
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf("success" to true, "hour" to hour, "minute" to minute)
        )
    }

    private suspend fun executeListAlarms(): ToolExecutionResult {
        val alarms = withContext(Dispatchers.IO) { alarmDao.getAll() }
        val rows = alarms.map {
            mapOf(
                "id" to it.id,
                "hour" to it.hour,
                "minute" to it.minute,
                "label" to it.label,
                "enabled" to it.enabled
            )
        }
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "count" to rows.size,
                "alarms" to rows,
                "note" to "May differ if alarms were modified outside nezumi-ai"
            )
        )
    }

    private suspend fun executeSetFlashlight(toolCall: ToolCall): ToolExecutionResult {
        if (!ToolSystemController.hasFlashlightPermission(context)) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "permission_denied"))
        }
        val on = toolCall.arguments.readBoolean("on")
        val result = ToolSystemController.toggleFlashlight(context, on)
        return if (result.isSuccess) {
            ToolExecutionResult(true, mapOf("success" to true, "flashlight" to if (on) "on" else "off"))
        } else {
            ToolExecutionResult(
                false,
                mapOf("success" to false, "error" to "flashlight_failed:${result.exceptionOrNull()?.message.orEmpty()}")
            )
        }
    }

    private fun normalizeToolName(name: String): String {
        return name.replace("_", "").lowercase(Locale.US)
    }
}

private class GetTimeToolSet : ToolSet {
    @Tool(description = "Returns current device datetime.")
    fun getCurrentTime(@ToolParam(description = "IANA timezone. e.g. Asia/Tokyo") timezone: String?): Map<String, String> {
        val zone = runCatching { if (timezone.isNullOrBlank()) ZoneId.systemDefault() else ZoneId.of(timezone) }
            .getOrDefault(ZoneId.systemDefault())
        val now = ZonedDateTime.ofInstant(Instant.now(), zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
        return mapOf(
            "datetime" to now.format(fmt),
            "timezone" to zone.id,
            "timestamp_ms" to now.toInstant().toEpochMilli().toString()
        )
    }
}

private class GetBatteryToolSet(
    private val context: Context
) : ToolSet {
    @Tool(description = "Returns current device battery level and status.")
    fun getBatteryLevel(): Map<String, Any?> {
        val result = ToolSystemController.getBatteryLevel(context)
        return result.getOrElse { mapOf("error" to "Failed to get battery status") }
    }
}

private class SetAlarmToolSet(
    private val context: Context,
    private val alarmDao: AlarmDao
) : ToolSet {
    @Tool(description = "Sets a system alarm at the given hour and minute.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int,
        @ToolParam(description = "Optional alarm label") label: String?
    ): Map<String, Any?> {
        if (!ToolSystemController.hasAlarmPermission(context)) {
            return mapOf("success" to false, "error" to "permission_denied")
        }
        if (hour !in 0..23 || minute !in 0..59) {
            return mapOf("success" to false, "error" to "invalid_time")
        }
        val resolvedLabel = label?.takeIf { it.isNotBlank() } ?: "nezumi-ai alarm"
        val result = ToolSystemController.setAlarm(context, hour, minute, resolvedLabel)
        if (result.isFailure) {
            return mapOf("success" to false, "error" to "set_alarm_failed")
        }
        runBlocking(Dispatchers.IO) {
            alarmDao.insert(AlarmEntity(hour = hour, minute = minute, label = resolvedLabel, enabled = true))
        }
        return mapOf("success" to true, "hour" to hour, "minute" to minute, "label" to resolvedLabel)
    }
}

private class DismissAlarmToolSet(
    private val context: Context,
    private val alarmDao: AlarmDao
) : ToolSet {
    @Tool(description = "Dismisses a system alarm by hour and minute.")
    fun dismissAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int
    ): Map<String, Any?> {
        if (!ToolSystemController.hasAlarmPermission(context)) {
            return mapOf("success" to false, "error" to "permission_denied")
        }
        if (hour !in 0..23 || minute !in 0..59) {
            return mapOf("success" to false, "error" to "invalid_time")
        }
        val result = ToolSystemController.dismissAlarm(context, hour, minute)
        if (result.isFailure) {
            return mapOf("success" to false, "error" to "dismiss_alarm_failed")
        }
        runBlocking(Dispatchers.IO) {
            alarmDao.delete(hour, minute)
        }
        return mapOf("success" to true, "hour" to hour, "minute" to minute)
    }
}

private class ListAlarmsToolSet(
    private val alarmDao: AlarmDao
) : ToolSet {
    @Tool(description = "Returns alarms managed by nezumi-ai.")
    fun listAlarms(): Map<String, Any?> {
        val alarms = runBlocking(Dispatchers.IO) {
            alarmDao.getAll()
        }
        val rows = alarms.map {
            mapOf(
                "id" to it.id,
                "hour" to it.hour,
                "minute" to it.minute,
                "label" to it.label,
                "enabled" to it.enabled
            )
        }
        return mapOf(
            "count" to rows.size,
            "alarms" to rows,
            "note" to "May differ if alarms were modified outside nezumi-ai"
        )
    }
}

private class FlashlightToolSet(
    private val context: Context
) : ToolSet {
    @Tool(description = "Turns flashlight on or off.")
    fun setFlashlight(@ToolParam(description = "true turns on, false turns off") on: Boolean): Map<String, Any?> {
        if (!ToolSystemController.hasFlashlightPermission(context)) {
            return mapOf("success" to false, "error" to "permission_denied")
        }
        val result = ToolSystemController.toggleFlashlight(context, on)
        return if (result.isSuccess) {
            mapOf("success" to true, "flashlight" to if (on) "on" else "off")
        } else {
            Log.w(TOOL_TAG, "Flashlight tool failed", result.exceptionOrNull())
            mapOf("success" to false, "error" to "flashlight_failed")
        }
    }
}

private fun Map<String, Any?>.readInt(key: String): Int {
    val value = this[key] ?: return Int.MIN_VALUE
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: Int.MIN_VALUE
        else -> Int.MIN_VALUE
    }
}

private fun Map<String, Any?>.readBoolean(key: String): Boolean {
    val value = this[key] ?: return false
    return when (value) {
        is Boolean -> value
        is String -> value.equals("true", ignoreCase = true)
        is Number -> value.toInt() != 0
        else -> false
    }
}
