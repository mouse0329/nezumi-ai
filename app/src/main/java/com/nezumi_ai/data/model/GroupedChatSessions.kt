package com.nezumi_ai.data.model

import com.nezumi_ai.data.database.entity.ChatSessionEntity
import java.util.Calendar

data class GroupedChatSessions(
    val dateLabel: String,
    val sessions: List<ChatSessionEntity>
)

fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<GroupedChatSessions> {
    val result = mutableListOf<GroupedChatSessions>()
    val calendar = Calendar.getInstance()
    val today = calendar.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val todayTime = today.timeInMillis

    val grouped = mutableMapOf<String, MutableList<ChatSessionEntity>>()

    for (session in sessions) {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = session.lastUpdated }
        sessionCal.set(Calendar.HOUR_OF_DAY, 0)
        sessionCal.set(Calendar.MINUTE, 0)
        sessionCal.set(Calendar.SECOND, 0)
        val sessionTime = sessionCal.timeInMillis
        val daysDiff = ((todayTime - sessionTime) / (1000 * 60 * 60 * 24)).toInt()

        val label = when {
            daysDiff == 0 -> "今日"
            daysDiff == 1 -> "昨日"
            daysDiff == 2 -> "一昨日"
            daysDiff in 3..6 -> {
                val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
                val dayName = when (dayOfWeek) {
                    Calendar.MONDAY -> "月"
                    Calendar.TUESDAY -> "火"
                    Calendar.WEDNESDAY -> "水"
                    Calendar.THURSDAY -> "木"
                    Calendar.FRIDAY -> "金"
                    Calendar.SATURDAY -> "土"
                    Calendar.SUNDAY -> "日"
                    else -> ""
                }
                if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) "今週 ($dayName)" else "今週"
            }
            daysDiff in 7..13 -> {
                val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
                val dayName = when (dayOfWeek) {
                    Calendar.MONDAY -> "月"
                    Calendar.TUESDAY -> "火"
                    Calendar.WEDNESDAY -> "水"
                    Calendar.THURSDAY -> "木"
                    Calendar.FRIDAY -> "金"
                    Calendar.SATURDAY -> "土"
                    Calendar.SUNDAY -> "日"
                    else -> ""
                }
                if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) "先週 ($dayName)" else "先週"
            }
            else -> {
                val monthsDiff = daysDiff / 30
                if (monthsDiff == 1) "1ヶ月前"
                else "${monthsDiff}ヶ月前"
            }
        }

        grouped.getOrPut(label) { mutableListOf() }.add(session)
    }

    // 順序を定義
    val labelOrder = listOf("今日", "昨日", "一昨日", "今週 (月)", "今週 (火)", "今週 (水)", "今週 (木)", "今週 (金)", "今週", "先週 (月)", "先週 (火)", "先週 (水)", "先週 (木)", "先週 (金)", "先週")
    val otherLabels = grouped.keys.filter { !labelOrder.contains(it) }.sorted().reversed()

    for (label in labelOrder + otherLabels) {
        grouped[label]?.let { sessionList ->
            result.add(GroupedChatSessions(label, sessionList))
        }
    }

    return result
}
