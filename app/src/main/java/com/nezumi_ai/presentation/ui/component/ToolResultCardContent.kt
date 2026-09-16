package com.nezumi_ai.presentation.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nezumi_ai.R
import com.nezumi_ai.data.inference.ToolResultCard
import java.util.Locale

/**
 * ツール実行結果カード (旧 item_tool_result_card.xml + ToolResultCardView の Compose 置き換え)。
 * ToolResultCard データを受け取り、ツール種別に応じて表示を変化させる。
 */
@Composable
fun ToolResultCardContent(
    card: ToolResultCard,
    modifier: Modifier = Modifier
) {
    val normalizedToolName = card.toolName.replace("_", "").lowercase(Locale.US)
    val iconRes: Int
    val title: String
    val subtext: String

    when (normalizedToolName) {
        "setalarm" -> {
            iconRes = R.drawable.ic_alarm
            title = stringResource(R.string.tool_result_alarm)
            subtext = if (card.success) {
                val hour = card.getPayloadString("hour")?.toIntOrNull() ?: 0
                val minute = card.getPayloadString("minute")?.toIntOrNull() ?: 0
                val label = card.getPayloadString("label") ?: "nezumi-ai alarm"
                "$hour:${minute.toString().padStart(2, '0')}　$label"
            } else {
                stringResource(R.string.tool_result_alarm_failed)
            }
        }
        "dismissalarm" -> {
            iconRes = R.drawable.ic_alarm_off
            title = stringResource(R.string.tool_result_dismiss_alarm)
            subtext = if (card.success) {
                val hour = card.getPayloadString("hour")?.toIntOrNull() ?: 0
                val minute = card.getPayloadString("minute")?.toIntOrNull() ?: 0
                "$hour:${minute.toString().padStart(2, '0')}"
            } else {
                stringResource(R.string.tool_result_dismiss_alarm_failed)
            }
        }
        "listalarms" -> {
            iconRes = R.drawable.ic_alarm
            title = stringResource(R.string.tool_result_list_alarms)
            subtext = if (card.success) {
                val count = card.getPayloadString("count")?.toIntOrNull() ?: 0
                stringResource(R.string.tool_result_alarms_count, count)
            } else {
                stringResource(R.string.tool_result_list_alarms_failed)
            }
        }
        "starttimer" -> {
            iconRes = R.drawable.ic_timer
            title = stringResource(R.string.tool_result_timer)
            subtext = if (card.success) {
                val durationSeconds = card.getPayloadString("durationSeconds")?.toLongOrNull() ?: 0L
                val minutes = durationSeconds / 60
                val seconds = durationSeconds % 60
                val label = card.getPayloadString("label")
                val durationStr = "%d分%d秒".format(minutes, seconds)
                if (label.isNullOrBlank()) durationStr else "$durationStr　$label"
            } else {
                stringResource(R.string.tool_result_timer_start_failed)
            }
        }
        "stoptimer" -> {
            iconRes = R.drawable.ic_timer_off
            title = stringResource(R.string.tool_result_stop_timer)
            subtext = if (card.success) {
                stringResource(R.string.tool_result_timer_stopped)
            } else {
                stringResource(R.string.tool_result_timer_stop_failed)
            }
        }
        "listtimers" -> {
            iconRes = R.drawable.ic_timer
            title = stringResource(R.string.tool_result_list_timers)
            subtext = if (card.success) {
                val count = card.getPayloadString("count")?.toIntOrNull() ?: 0
                stringResource(R.string.tool_result_timers_running, count)
            } else {
                stringResource(R.string.tool_result_list_timers_failed)
            }
        }
        "getbatterylevel" -> {
            iconRes = R.drawable.ic_battery_std
            title = stringResource(R.string.tool_result_battery)
            subtext = if (card.success) {
                val level = card.getPayloadString("level")?.toIntOrNull() ?: 0
                val statusDisplay = translateBatteryStatus(card.getPayloadString("status") ?: "unknown")
                "$level%　$statusDisplay"
            } else {
                stringResource(R.string.tool_result_battery_failed)
            }
        }
        "getcurrenttime" -> {
            iconRes = R.drawable.ic_access_time
            title = stringResource(R.string.tool_result_current_time)
            subtext = if (card.success) {
                card.getPayloadString("datetime") ?: "unknown"
            } else {
                stringResource(R.string.tool_result_current_time_failed)
            }
        }
        "setflashlight", "flashlight" -> {
            val isOn = card.getPayloadString("flashlight") == "on"
            iconRes = if (isOn) R.drawable.ic_flashlight_on else R.drawable.ic_flashlight_off
            title = stringResource(R.string.tool_result_flashlight)
            subtext = if (card.success) {
                if (isOn) "ON" else "OFF"
            } else {
                stringResource(R.string.tool_result_flashlight_failed)
            }
        }
        else -> {
            iconRes = android.R.drawable.ic_menu_info_details
            title = card.toolName
            subtext = if (card.success) "実行成功" else "実行失敗"
        }
    }

    // bg_tool_result_card.xml / bg_tool_result_card_error.xml の再現
    val shape = RoundedCornerShape(12.dp)
    val backgroundColor = colorResource(
        if (card.success) R.color.tool_card_success_bg else R.color.tool_card_error_bg
    )
    val titleColor = if (card.success) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.error
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(min = dimensionResource(R.dimen.tool_result_card_min_width))
            .background(backgroundColor, shape)
            .border(1.dp, colorResource(R.color.border), shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = stringResource(R.string.tool_result_icon),
            modifier = Modifier.size(24.dp),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtext,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** バッテリーステータスを日本語に翻訳 (旧 ToolResultCardView と同じマッピング)。 */
private fun translateBatteryStatus(status: String): String {
    return when (status.lowercase()) {
        "charging" -> "充電中"
        "discharging" -> "使用中"
        "full" -> "満充電"
        "not_charging" -> "充電停止"
        else -> status
    }
}
