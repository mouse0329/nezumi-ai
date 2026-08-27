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

    val pinnedSessions = sessions.filter { it.isPinned }
    val unpinnedSessions = sessions.filter { !it.isPinned }

    if (pinnedSessions.isNotEmpty()) {
        result.add(GroupedChatSessions(context.getString(R.string.session_group_pinned), pinnedSessions))
    }

    // ラベル付けは一般的なアプリ (Gmail / メッセージ系など) と同じ規則に揃える:
    //   今日 / 昨日 / 直近1週間は曜日 / それ以前は日付。
    //   セッションは lastUpdated 降順で渡ってくるため、LinkedHashMap の
    //   挿入順がそのまま「新しい順」のグループ順になる (固定の曜日リストは不要)。
    val grouped = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    for (session in unpinnedSessions) {
        val label = sessionDateLabel(context, session.lastUpdated)
        grouped.getOrPut(label) { mutableListOf() }.add(session)
    }
    for ((label, list) in grouped) {
        result.add(GroupedChatSessions(label, list))
    }
    return result
}

fun sessionDateLabel(context: Context, timestamp: Long): String {
    val sessionCal = Calendar.getInstance().apply { timeInMillis = timestamp }

    val todayStart = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val sessionStart = (sessionCal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val daysDiff = ((todayStart.timeInMillis - sessionStart.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()

    return when {
        daysDiff <= 0 -> context.getString(R.string.session_group_today)
        daysDiff == 1 -> context.getString(R.string.session_group_yesterday)
        daysDiff in 2..6 -> {
            // 直近1週間は曜日名 (月曜日 / Monday など)
            val names = context.resources.getStringArray(R.array.day_names_full)
            names.getOrElse(sessionCal.get(Calendar.DAY_OF_WEEK) - 1) { "" }
        }
        else -> {
            // それ以前はロケール標準の日付 (2026/8/26 / Aug 26, 2026 など)
            android.text.format.DateFormat.getDateFormat(context).format(sessionCal.time)
        }
    }
}
