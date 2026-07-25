package com.dhaval.echo.data.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One event read from the device's calendars (phone, Google, Samsung…). */
data class PhoneEvent(
    val id: Long,
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val calendarName: String?
) {
    /** The local date this instance starts on — for grouping onto a calendar day. */
    val date: LocalDate
        get() = Instant.ofEpochMilli(beginMillis).atZone(ZoneId.systemDefault()).toLocalDate()
}

/**
 * Read-only access to the device Calendar Provider, so Echo can show the user's
 * existing events and reminders (from every account they've synced — Google,
 * Samsung, phone) alongside their memories. We never write to their calendars.
 */
class PhoneCalendarRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Every event instance overlapping [start, end], across all synced calendars.
     * Uses the Instances table so recurring events are already expanded. Returns
     * empty (never throws) when permission is absent or a query fails.
     */
    suspend fun eventsBetween(startMillis: Long, endMillis: Long): List<PhoneEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()

            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
                ContentUris.appendId(this, startMillis)
                ContentUris.appendId(this, endMillis)
            }.build()

            val projection = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME
            )

            runCatching {
                context.contentResolver.query(
                    uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    buildList {
                        val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                        val titleIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                        val beginIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                        val endIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                        val allDayIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                        val calIdx = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                        while (cursor.moveToNext()) {
                            add(
                                PhoneEvent(
                                    id = cursor.getLong(idIdx),
                                    title = cursor.getString(titleIdx)?.takeIf { it.isNotBlank() }
                                        ?: "(untitled event)",
                                    beginMillis = cursor.getLong(beginIdx),
                                    endMillis = cursor.getLong(endIdx),
                                    allDay = cursor.getInt(allDayIdx) == 1,
                                    calendarName = cursor.getString(calIdx)
                                )
                            )
                        }
                    }
                } ?: emptyList()
            }.getOrElse { emptyList() }
        }
}
