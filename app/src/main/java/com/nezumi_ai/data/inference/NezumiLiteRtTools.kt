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
import kotlinx.coroutines.withContext

private const val TOOL_TAG = "NezumiTools"

// LiteRTに渡すツール名の正規化マップ（ホワイトリスト方式）
// LiteRTが返すツール名の揺れをここで吸収する
private val TOOL_NAME_MAP = mapOf(
    // get_current_time
    "get_current_time"   to "getcurrenttime",
    "getCurrentTime"     to "getcurrenttime",
    "getcurrenttime"     to "getcurrenttime",
    // get_battery_level
    "get_battery_level"  to "getbatterylevel",
    "getBatteryLevel"    to "getbatterylevel",
    "getbatterylevel"    to "getbatterylevel",
    // set_alarm
    "set_alarm"          to "setalarm",
    "setAlarm"           to "setalarm",
    "setalarm"           to "setalarm",
    // dismiss_alarm
    "dismiss_alarm"      to "dismissalarm",
    "dismissAlarm"       to "dismissalarm",
    "dismissalarm"       to "dismissalarm",
    // list_alarms
    "list_alarms"        to "listalarms",
    "listAlarms"         to "listalarms",
    "listalarms"         to "listalarms",
    // flashlight（snake_case / camelCase / 旧名 "flashlight" すべて吸収）
    "set_flashlight"     to "setflashlight",
    "setFlashlight"      to "setflashlight",
    "setflashlight"      to "setflashlight",
    "flashlight"         to "setflashlight",
    // start_timer
    "start_timer"        to "starttimer",
    "startTimer"         to "starttimer",
    "starttimer"         to "starttimer",
    // stop_timer
    "stop_timer"         to "stoptimer",
    "stopTimer"          to "stoptimer",
    "stoptimer"          to "stoptimer",
    // list_timers
    "list_timers"        to "listtimers",
    "listTimers"         to "listtimers",
    "listtimers"         to "listtimers",
)

// ─────────────────────────────────────────────
// スキーマ登録用 ToolProvider ビルダー
// ToolSet のメソッドはスキーマ定義のみ。実行ロジックは持たない。
// ─────────────────────────────────────────────

internal fun buildEnabledToolProviders(context: Context, alarmDao: AlarmDao): List<ToolProvider> {
    val enabled = ToolPreferences(context).getEnabledTools()
    return buildList {
        if (NezumiTool.GET_TIME in enabled)      add(tool(GetTimeSchema()))
        if (NezumiTool.GET_BATTERY in enabled)   add(tool(GetBatterySchema()))
        if (NezumiTool.SET_ALARM in enabled)     add(tool(SetAlarmSchema()))
        if (NezumiTool.DISMISS_ALARM in enabled) add(tool(DismissAlarmSchema()))
        // LIST は SET か DISMISS どちらかが有効なら表示できる
        if (NezumiTool.LIST_ALARMS in enabled &&
            (NezumiTool.SET_ALARM in enabled || NezumiTool.DISMISS_ALARM in enabled)
        ) {
            add(tool(ListAlarmsSchema()))
        }
        if (NezumiTool.FLASHLIGHT in enabled)    add(tool(FlashlightSchema()))
        if (NezumiTool.START_TIMER in enabled)   add(tool(StartTimerSchema()))
        if (NezumiTool.STOP_TIMER in enabled)    add(tool(StopTimerSchema()))
        // LIST は START か STOP どちらかが有効なら表示できる
        if (NezumiTool.LIST_TIMERS in enabled &&
            (NezumiTool.START_TIMER in enabled || NezumiTool.STOP_TIMER in enabled)
        ) {
            add(tool(ListTimersSchema()))
        }
    }
}

// ─────────────────────────────────────────────
// スキーマ専用 ToolSet（ボディはスタブ）
// LiteRT はここからツール名・引数定義を読み取る。
// automaticToolCalling = false のため実際には呼ばれない。
// ─────────────────────────────────────────────

private class GetTimeSchema : ToolSet {
    @Tool(description = "Returns current device datetime.")
    fun getCurrentTime(
        @ToolParam(description = "IANA timezone. e.g. Asia/Tokyo") timezone: String?
    ): Map<String, String> = emptyMap()
}

private class GetBatterySchema : ToolSet {
    @Tool(description = "Returns current device battery level and status.")
    fun getBatteryLevel(): Map<String, Any?> = emptyMap()
}

private class SetAlarmSchema : ToolSet {
    @Tool(description = "Sets a system alarm at the given hour and minute.")
    fun setAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int,
        @ToolParam(description = "Optional alarm label") label: String?
    ): Map<String, Any?> = emptyMap()
}

private class DismissAlarmSchema : ToolSet {
    @Tool(description = "Dismisses a system alarm by hour and minute.")
    fun dismissAlarm(
        @ToolParam(description = "Hour in 24h format, 0-23") hour: Int,
        @ToolParam(description = "Minute, 0-59") minute: Int
    ): Map<String, Any?> = emptyMap()
}

private class ListAlarmsSchema : ToolSet {
    @Tool(description = "Returns alarms managed by nezumi-ai.")
    fun listAlarms(): Map<String, Any?> = emptyMap()
}

private class FlashlightSchema : ToolSet {
    @Tool(description = "Turns flashlight on or off.")
    fun setFlashlight(
        @ToolParam(description = "true turns on, false turns off") on: Boolean
    ): Map<String, Any?> = emptyMap()
}

private class StartTimerSchema : ToolSet {
    @Tool(description = "Starts a timer for the specified duration in seconds.")
    fun startTimer(
        @ToolParam(description = "Duration in seconds") durationSeconds: Int,
        @ToolParam(description = "Optional label for the timer") label: String?
    ): Map<String, Any?> = emptyMap()
}

private class StopTimerSchema : ToolSet {
    @Tool(description = "Stops a running timer by its ID.")
    fun stopTimer(
        @ToolParam(description = "Timer ID to stop") timerId: String
    ): Map<String, Any?> = emptyMap()
}

private class ListTimersSchema : ToolSet {
    @Tool(description = "Lists all currently running timers.")
    fun listTimers(): Map<String, Any?> = emptyMap()
}

// ─────────────────────────────────────────────
// 実行エンジン（単一責任・全ロジックここに集約）
// ─────────────────────────────────────────────

internal data class ToolExecutionResult(
    val success: Boolean,
    val payload: Map<String, Any?>
)

internal class NezumiLiteRtToolExecutor(
    private val context: Context,
    private val alarmDao: AlarmDao
) {
    suspend fun execute(toolCall: ToolCall): ToolExecutionResult {
        val normalized = normalizeToolName(toolCall.name)
        val toolPrefs = ToolPreferences(context)
        val gate = gateTool(normalized)
        if (gate != null && !toolPrefs.isEnabled(gate)) {
            Log.w(TOOL_TAG, "Tool disabled by preferences: name=${toolCall.name} normalized=$normalized tool=$gate")
            return ToolExecutionResult(
                success = false,
                payload = mapOf(
                    "success" to false,
                    "error" to "tool_disabled",
                    "tool" to toolCall.name
                )
            )
        }

        return when (normalized) {
            "getcurrenttime"  -> executeGetCurrentTime(toolCall)
            "getbatterylevel" -> executeGetBatteryLevel()
            "setalarm"        -> executeSetAlarm(toolCall)
            "dismissalarm"    -> executeDismissAlarm(toolCall)
            "listalarms"      -> executeListAlarms()
            "setflashlight"   -> executeSetFlashlight(toolCall)
            "starttimer"      -> executeStartTimer(toolCall)
            "stoptimer"       -> executeStopTimer(toolCall)
            "listtimers"      -> executeListTimers()
            else -> {
                Log.w(TOOL_TAG, "Unknown tool: ${toolCall.name}")
                ToolExecutionResult(
                    success = false,
                    payload = mapOf("success" to false, "error" to "unknown_tool:${toolCall.name}")
                )
            }
        }
    }

    private fun gateTool(normalizedToolName: String): NezumiTool? {
        return when (normalizedToolName) {
            "getcurrenttime" -> NezumiTool.GET_TIME
            "getbatterylevel" -> NezumiTool.GET_BATTERY
            "setalarm" -> NezumiTool.SET_ALARM
            "dismissalarm" -> NezumiTool.DISMISS_ALARM
            "listalarms" -> NezumiTool.LIST_ALARMS
            "setflashlight" -> NezumiTool.FLASHLIGHT
            "starttimer" -> NezumiTool.START_TIMER
            "stoptimer" -> NezumiTool.STOP_TIMER
            "listtimers" -> NezumiTool.LIST_TIMERS
            else -> null
        }
    }

    private fun normalizeToolName(name: String): String {
        TOOL_NAME_MAP[name]?.let { return it }
        
        // フォールバック: マップに未登録のツール名
        val normalized = name.replace("_", "").lowercase(Locale.US)
        Log.w(TOOL_TAG, "Tool name not in whitelist: original=$name normalized=$normalized")
        recordUnknownToolNameMetric(original = name, normalized = normalized)
        return normalized
    }

    private fun recordUnknownToolNameMetric(original: String, normalized: String) {
        // メトリクスを記録（未知のツール名の候補を追跡可能にする）
        // 本番環境：Firebase Analytics, Crashlytics などに送信可能
        Log.d(TOOL_TAG, "METRIC_UNKNOWN_TOOL | original=$original | normalized=$normalized")
    }

    // ── 各ツール実装 ──────────────────────────────

    private fun executeGetCurrentTime(toolCall: ToolCall): ToolExecutionResult {
        val timezone = toolCall.arguments["timezone"]?.toString()?.takeIf { it.isNotBlank() }
        val zone = runCatching {
            if (timezone != null) ZoneId.of(timezone) else ZoneId.systemDefault()
        }.getOrElse {
            return ToolExecutionResult(
                success = false,
                payload = mapOf("success" to false, "error" to "invalid_timezone")
            )
        }
        val now = ZonedDateTime.now(zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
        return ToolExecutionResult(
            success = true,
            payload = mapOf(
                "datetime"     to now.format(fmt),
                "timezone"     to zone.id,
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
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
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
        val hour = toolCall.arguments.readInt("hour")
        val minute = toolCall.arguments.readInt("minute")
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
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

    private suspend fun executeStartTimer(toolCall: ToolCall): ToolExecutionResult {
        // LiteRT が snake_case で渡す場合（duration_seconds）と、camelCase の両方に対応
        val durationSeconds = (toolCall.arguments["durationSeconds"]
            ?: toolCall.arguments["duration_seconds"])?.let {
            when (it) {
                is Number -> it.toLong()
                is String -> it.toLongOrNull()
                else      -> null
            }
        }
        if (durationSeconds == null || durationSeconds <= 0) {
            return ToolExecutionResult(false, mapOf("success" to false, "error" to "invalid_duration"))
        }
        val label = toolCall.arguments["label"]?.toString()?.takeIf { it.isNotBlank() } ?: ""
        val result = ToolSystemController.TimerManager.startTimer(durationSeconds, label)
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }

    private suspend fun executeStopTimer(toolCall: ToolCall): ToolExecutionResult {
        // LiteRT が snake_case で渡す場合（timer_id）と、camelCase の両方に対応
        val timerId = (toolCall.arguments["timerId"] ?: toolCall.arguments["timer_id"])?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return ToolExecutionResult(false, mapOf("success" to false, "error" to "missing_timer_id"))
        val result = ToolSystemController.TimerManager.stopTimer(timerId)
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }

    private suspend fun executeListTimers(): ToolExecutionResult {
        val result = ToolSystemController.TimerManager.listTimers()
        return ToolExecutionResult(
            success = result["success"] as? Boolean ?: false,
            payload = result
        )
    }
}

// ─────────────────────────────────────────────
// ユーティリティ拡張
// ─────────────────────────────────────────────

private fun Map<String, Any?>.readInt(key: String): Int? {
    return when (val v = this[key] ?: return null) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else      -> null
    }
}

private fun Map<String, Any?>.readLong(key: String): Long? {
    return when (val v = this[key] ?: return null) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull()
        else      -> null
    }
}

private fun Map<String, Any?>.readBoolean(key: String): Boolean {
    return when (val v = this[key] ?: return false) {
        is Boolean -> v
        is String  -> v.equals("true", ignoreCase = true)
        is Number  -> v.toInt() != 0
        else       -> false
    }
}
