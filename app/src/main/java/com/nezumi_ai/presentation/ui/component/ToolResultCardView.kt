package com.nezumi_ai.presentation.ui.component

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ToolResultCard
import java.util.Locale

/**
 * ツール実行結果を表示するカード View。
 * ToolResultCard データを受け取り、ツール種別に応じて表示を変化させる。
 */
class ToolResultCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private lateinit var toolResultIcon: ImageView
    private lateinit var toolResultTitle: TextView
    private lateinit var toolResultSubtext: TextView

    init {
        inflate(context, R.layout.item_tool_result_card, this)
        toolResultIcon = findViewById(R.id.toolResultIcon)
        toolResultTitle = findViewById(R.id.toolResultTitle)
        toolResultSubtext = findViewById(R.id.toolResultSubtext)
    }

    /**
     * ToolResultCard をバインドして表示する
     */
    fun bind(card: ToolResultCard) {
        val normalizedToolName = card.toolName.replace("_", "").lowercase(Locale.US)

        when (normalizedToolName) {
            "setalarm" -> bindSetAlarm(card)
            "dismissalarm" -> bindDismissAlarm(card)
            "listalarms" -> bindListAlarms(card)
            "starttimer" -> bindStartTimer(card)
            "stoptimer" -> bindStopTimer(card)
            "listtimers" -> bindListTimers(card)
            "getbatterylevel" -> bindGetBattery(card)
            "getcurrenttime" -> bindGetCurrentTime(card)
            "setflashlight", "flashlight" -> bindSetFlashlight(card)
            else -> bindUnknown(card)
        }

        // エラー状態でバックグラウンドを変更
        if (!card.success) {
            background = ContextCompat.getDrawable(context, R.drawable.bg_tool_result_card_error)
            // エラー時はテキスト色も調整
            toolResultTitle.setTextColor(ContextCompat.getColor(context, com.google.android.material.R.color.design_default_color_error))
        }
    }

    // ─────────────────────────────────────────────────────────────

    private fun bindSetAlarm(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_alarm)
        toolResultTitle.text = context.getString(R.string.tool_result_alarm)

        if (card.success) {
            val hour = card.getPayloadString("hour")?.toIntOrNull() ?: 0
            val minute = card.getPayloadString("minute")?.toIntOrNull() ?: 0
            val label = card.getPayloadString("label") ?: "nezumi-ai alarm"
            toolResultSubtext.text = "$hour:${minute.toString().padStart(2, '0')}　$label"
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_alarm_failed)
        }
    }

    private fun bindDismissAlarm(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_alarm_off)
        toolResultTitle.text = context.getString(R.string.tool_result_dismiss_alarm)

        if (card.success) {
            val hour = card.getPayloadString("hour")?.toIntOrNull() ?: 0
            val minute = card.getPayloadString("minute")?.toIntOrNull() ?: 0
            toolResultSubtext.text = "$hour:${minute.toString().padStart(2, '0')}"
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_dismiss_alarm_failed)
        }
    }

    private fun bindListAlarms(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_alarm)
        toolResultTitle.text = context.getString(R.string.tool_result_list_alarms)

        if (card.success) {
            val count = card.getPayloadString("count")?.toIntOrNull() ?: 0
            toolResultSubtext.text = context.getString(R.string.tool_result_alarms_count, count)
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_list_alarms_failed)
        }
    }

    private fun bindStartTimer(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_timer)
        toolResultTitle.text = context.getString(R.string.tool_result_timer)

        if (card.success) {
            val durationSeconds = card.getPayloadString("durationSeconds")?.toLongOrNull() ?: 0L
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            val label = card.getPayloadString("label")
            val durationStr = String.format("%d分%d秒", minutes, seconds)
            toolResultSubtext.text = if (label.isNullOrBlank()) {
                durationStr
            } else {
                durationStr + "　" + label
            }
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_timer_start_failed)
        }
    }

    private fun bindStopTimer(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_timer_off)
        toolResultTitle.text = context.getString(R.string.tool_result_stop_timer)

        toolResultSubtext.text = if (card.success) {
            context.getString(R.string.tool_result_timer_stopped)
        } else {
            context.getString(R.string.tool_result_timer_stop_failed)
        }
    }

    private fun bindListTimers(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_timer)
        toolResultTitle.text = context.getString(R.string.tool_result_list_timers)

        if (card.success) {
            val count = card.getPayloadString("count")?.toIntOrNull() ?: 0
            toolResultSubtext.text = context.getString(R.string.tool_result_timers_running, count)
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_list_timers_failed)
        }
    }

    private fun bindGetBattery(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_battery_std)
        toolResultTitle.text = context.getString(R.string.tool_result_battery)

        if (card.success) {
            val level = card.getPayloadString("level")?.toIntOrNull() ?: 0
            val statusRaw = card.getPayloadString("status") ?: "unknown"
            val statusDisplay = translateBatteryStatus(statusRaw)
            toolResultSubtext.text = "$level%　$statusDisplay"
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_battery_failed)
        }
    }

    private fun bindGetCurrentTime(card: ToolResultCard) {
        toolResultIcon.setImageResource(R.drawable.ic_access_time)
        toolResultTitle.text = context.getString(R.string.tool_result_current_time)

        if (card.success) {
            val datetime = card.getPayloadString("datetime") ?: "unknown"
            toolResultSubtext.text = datetime
        } else {
            toolResultSubtext.text = context.getString(R.string.tool_result_current_time_failed)
        }
    }

    private fun bindSetFlashlight(card: ToolResultCard) {
        val isOn = card.getPayloadString("flashlight") == "on"
        toolResultIcon.setImageResource(if (isOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off)
        toolResultTitle.text = context.getString(R.string.tool_result_flashlight)

        toolResultSubtext.text = if (card.success) {
            if (isOn) "ON" else "OFF"
        } else {
            context.getString(R.string.tool_result_flashlight_failed)
        }
    }

    private fun bindUnknown(card: ToolResultCard) {
        toolResultIcon.setImageResource(android.R.drawable.ic_menu_info_details)
        toolResultTitle.text = card.toolName
        toolResultSubtext.text = if (card.success) "実行成功" else "実行失敗"
    }

    /**
     * バッテリーステータスを日本語に翻訳
     */
    private fun translateBatteryStatus(status: String): String {
        return when (status.lowercase()) {
            "charging" -> "充電中"
            "discharging" -> "使用中"
            "full" -> "満充電"
            "not_charging" -> "充電停止"
            else -> status
        }
    }
}
