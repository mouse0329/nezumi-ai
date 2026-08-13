package com.nezumi_ai.data.model

import android.content.Context
import com.nezumi_ai.R
import com.nezumi_ai.data.database.entity.ChatSessionEntity
import java.util.Calendar

data class GroupedChatSessions(
    val dateLabel: String,
    val sessions: List<ChatSessionEntity>
)

fun groupSessionsByDate(
    sessions: List<ChatSessionEntity>,
    context: Context
): List<GroupedChatSessions> {
    android.util.Log.d("GroupedChatSessions", "groupSessionsByDate: total sessions=${sessions.size}")
    val result = mutableListOf<GroupedChatSessions>()
    val calendar = Calendar.getInstance()
    val today = calendar.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
    val todayTime = today.timeInMillis

    val pinnedSessions = sessions.filter { it.isPinned }
    val unpinnedSessions = sessions.filter { !it.isPinned }

    if (pinnedSessions.isNotEmpty()) {
        result.add(GroupedChatSessions(context.getString(R.string.session_group_pinned), pinnedSessions))
    }

    val grouped = mutableMapOf<String, MutableList<ChatSessionEntity>>()
    for (session in unpinnedSessions) {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = session.lastUpdated }
        sessionCal.set(Calendar.HOUR_OF_DAY, 0)
        sessionCal.set(Calendar.MINUTE, 0)
        sessionCal.set(Calendar.SECOND, 0)
        val sessionTime = sessionCal.timeInMillis
        val daysDiff = ((todayTime - sessionTime) / (1000 * 60 * 60 * 24)).toInt()
        val label = sessionDateLabel(context, daysDiff, sessionCal)
        grouped.getOrPut(label) { mutableListOf() }.add(session)
    }

    val mon = context.getString(R.string.day_mon_short)
    val tue = context.getString(R.string.day_tue_short)
    val wed = context.getString(R.string.day_wed_short)
    val thu = context.getString(R.string.day_thu_short)
    val fri = context.getString(R.string.day_fri_short)
    val labelOrder = listOf(
        context.getString(R.string.session_group_today),
        context.getString(R.string.session_group_yesterday),
        context.getString(R.string.session_group_day_before_yesterday),
        context.getString(R.string.session_group_this_week_day, mon),
        context.getString(R.string.session_group_this_week_day, tue),
        context.getString(R.string.session_group_this_week_day, wed),
        context.getString(R.string.session_group_this_week_day, thu),
        context.getString(R.string.session_group_this_week_day, fri),
        context.getString(R.string.session_group_this_week),
        context.getString(R.string.session_group_last_week_day, mon),
        context.getString(R.string.session_group_last_week_day, tue),
        context.getString(R.string.session_group_last_week_day, wed),
        context.getString(R.string.session_group_last_week_day, thu),
        context.getString(R.string.session_group_last_week_day, fri),
        context.getString(R.string.session_group_last_week)
    )
    val otherLabels = grouped.keys.filter { !labelOrder.contains(it) }.sorted().reversed()
    for (label in labelOrder + otherLabels) {
        grouped[label]?.let { result.add(GroupedChatSessions(label, it)) }
    }
    return result
}

fun sessionDateLabel(context: Context, daysDiff: Int, sessionCal: Calendar): String {
    return when {
        daysDiff == 0 -> context.getString(R.string.session_group_today)
        daysDiff == 1 -> context.getString(R.string.session_group_yesterday)
        daysDiff == 2 -> context.getString(R.string.session_group_day_before_yesterday)
        daysDiff in 3..6 -> {
            val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
            val dayName = dayNameShort(context, dayOfWeek)
            if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY)
                context.getString(R.string.session_group_this_week_day, dayName)
            else context.getString(R.string.session_group_this_week)
        }
        daysDiff in 7..13 -> {
            val dayOfWeek = sessionCal.get(Calendar.DAY_OF_WEEK)
            val dayName = dayNameShort(context, dayOfWeek)
            if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY)
                context.getString(R.string.session_group_last_week_day, dayName)
            else context.getString(R.string.session_group_last_week)
        }
        else -> {
            val monthsDiff = daysDiff / 30
            if (monthsDiff <= 1) context.getString(R.string.session_group_months_ago_one)
            else context.getString(R.string.session_group_months_ago, monthsDiff)
        }
    }
}

fun dayNameShort(context: Context, dayOfWeek: Int): String {
    return when (dayOfWeek) {
        Calendar.MONDAY -> context.getString(R.string.day_mon_short)
        Calendar.TUESDAY -> context.getString(R.string.day_tue_short)
        Calendar.WEDNESDAY -> context.getString(R.string.day_wed_short)
        Calendar.THURSDAY -> context.getString(R.string.day_thu_short)
        Calendar.FRIDAY -> context.getString(R.string.day_fri_short)
        Calendar.SATURDAY -> context.getString(R.string.day_sat_short)
        Calendar.SUNDAY -> context.getString(R.string.day_sun_short)
        else -> ""
    }
}
