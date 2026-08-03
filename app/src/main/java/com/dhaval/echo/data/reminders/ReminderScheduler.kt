package com.dhaval.echo.data.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.dhaval.echo.data.db.ExtractedItem
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.UnderstandingDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules a real, time-precise notification for a commitment at its due time via
 * [AlarmManager]. Alarms are keyed by the item id, so scheduling again replaces the
 * old one; cancelling removes it. The receiver re-checks the DB before notifying,
 * so a stale alarm (item completed/deleted) simply does nothing.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: UnderstandingDao
) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Schedule (or reschedule) a notification for [item] if it's an open, future, dated commitment. */
    fun schedule(item: ExtractedItem) {
        val due = item.dueAtMillis ?: return
        if (item.status != ItemStatus.OPEN) return
        if (item.kind != ItemKind.TASK && item.kind != ItemKind.REMINDER) return
        if (due <= System.currentTimeMillis()) return // don't fire for the past

        val pi = pendingIntent(item.id, item.value)
        try {
            if (canExact()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            } else {
                // Exact alarms not permitted — fall back to a best-effort window.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pi)
            }
            Log.d(TAG, "Scheduled reminder ${item.id} for $due")
        } catch (e: SecurityException) {
            Log.w(TAG, "Couldn't schedule exact alarm for ${item.id}", e)
        }
    }

    fun cancel(itemId: String) {
        alarmManager.cancel(pendingIntent(itemId, ""))
    }

    /** Schedule any dated reminders a memory produced (called after it's understood). */
    suspend fun scheduleForMemory(memoryId: String) {
        dao.getItemsForMemoryOnce(memoryId).forEach { schedule(it) }
    }

    /** Re-arm every open, dated reminder — used after a reboot (alarms don't survive it). */
    suspend fun rescheduleAll() {
        dao.getSchedulableReminders().forEach { schedule(it) }
    }

    private fun pendingIntent(itemId: String, label: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_LABEL, label)
            data = android.net.Uri.parse("echo://reminder/$itemId") // make the intent unique per item
        }
        return PendingIntent.getBroadcast(
            context, itemId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    companion object {
        const val ACTION_FIRE = "com.dhaval.echo.REMINDER_FIRE"
        const val EXTRA_ITEM_ID = "item_id"
        const val EXTRA_LABEL = "label"
        private const val TAG = "ReminderScheduler"
    }
}
