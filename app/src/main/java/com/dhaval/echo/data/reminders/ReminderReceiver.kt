package com.dhaval.echo.data.reminders

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dhaval.echo.MainActivity
import com.dhaval.echo.R
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.UnderstandingDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Fires a commitment's notification at its due time, and re-arms all reminders
 * after a reboot (alarms don't survive one). Before notifying it re-reads the item
 * from the DB, so a commitment that was completed/dismissed/deleted stays silent.
 */
@AndroidEntryPoint
class ReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var dao: UnderstandingDao
    @Inject lateinit var scheduler: ReminderScheduler

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        scheduler.rescheduleAll()
                    } finally {
                        pending.finish()
                    }
                }
            }
            ReminderScheduler.ACTION_FIRE -> {
                val itemId = intent.getStringExtra(ReminderScheduler.EXTRA_ITEM_ID) ?: return
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val item = dao.getItemById(itemId)
                        // Only notify a commitment that still wants doing.
                        if (item != null && item.status == ItemStatus.OPEN) {
                            notify(context, itemId, item.value)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    private fun notify(context: Context, itemId: String, text: String) {
        ensureChannel(context)

        // Notifications need runtime permission on Android 13+.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_COMMITMENTS, true)
        }
        val tap = PendingIntent.getActivity(
            context, itemId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_echo_mark)
            .setContentTitle("A commitment is due")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()

        NotificationManagerCompat.from(context).notify(itemId.hashCode(), notification)
    }

    private fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID, "Commitments", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Reminders for commitments Echo noticed" }
            )
        }
    }

    companion object {
        const val CHANNEL_ID = "echo_commitments"
        const val EXTRA_OPEN_COMMITMENTS = "open_commitments"
    }
}
