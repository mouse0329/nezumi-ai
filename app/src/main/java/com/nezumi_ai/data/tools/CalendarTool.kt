package com.nezumi_ai.data.tools

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.TimeZone

object CalendarTool {
    private const val TAG = "CalendarTool"

    fun hasCalendarPermission(context: Context): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        val writeGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        return readGranted && writeGranted
    }

    fun addEvent(
        context: Context,
        title: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        description: String? = null,
        location: String? = null
    ): Result<Long> {
        return runCatching {
            if (!hasCalendarPermission(context)) {
                throw SecurityException("Calendar permission not granted")
            }

            val calendarId = getPrimaryCalendarId(context)
                ?: throw IllegalStateException("No calendar found")

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startTimeMillis)
                put(CalendarContract.Events.DTEND, endTimeMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }

            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to insert event")

            val eventId = uri.lastPathSegment?.toLongOrNull()
                ?: throw IllegalStateException("Invalid event ID")

            Log.d(TAG, "Event added: $title at $startTimeMillis")
            eventId
        }
    }

    fun listEvents(
        context: Context,
        startTimeMillis: Long,
        endTimeMillis: Long
    ): Result<List<Map<String, Any?>>> {
        return runCatching {
            if (!hasCalendarPermission(context)) {
                throw SecurityException("Calendar permission not granted")
            }

            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION
            )

            val selection = "(${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = arrayOf(startTimeMillis.toString(), endTimeMillis.toString())

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            val events = mutableListOf<Map<String, Any?>>()
            cursor?.use {
                while (it.moveToNext()) {
                    events.add(mapOf(
                        "id" to it.getLong(0),
                        "title" to it.getString(1),
                        "start" to it.getLong(2),
                        "end" to it.getLong(3),
                        "description" to it.getString(4),
                        "location" to it.getString(5)
                    ))
                }
            }

            Log.d(TAG, "Found ${events.size} events")
            events
        }
    }

    private fun getPrimaryCalendarId(context: Context): Long? {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null
        )
        return cursor?.use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }
}
