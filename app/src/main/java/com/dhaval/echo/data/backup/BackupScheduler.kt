package com.dhaval.echo.data.backup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.dhaval.echo.domain.backup.BackupFrequency
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import com.dhaval.echo.domain.backup.BackupSettings
import com.dhaval.echo.domain.backup.BackupTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the user's schedule into WorkManager requests.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: BackupPreferences,
    private val localState: BackupLocalState
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call after any change to frequency, conditions, or the destination. */
    suspend fun reschedule() {
        val settings = preferences.current()
        val interval = settings.frequency.intervalMinutes

        // No destination means nowhere to write. Scheduling work that is
        // guaranteed to fail would fill the health panel with failures the user
        // can only fix by doing the thing they haven't done yet.
        if (interval == null || !settings.hasDestination) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            Log.i(TAG, "Automatic backup off (${settings.frequency.name}, destination=${settings.hasDestination})")
            return
        }

        val minutes = interval.coerceAtLeast(BackupFrequency.WORK_MANAGER_FLOOR_MINUTES)
        val request = PeriodicWorkRequestBuilder<BackupWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(settings.constraints())
            .setInputData(
                dataFor(
                    profile = settings.scheduledProfile,
                    reason = BackupReason.SCHEDULED,
                    detail = null
                )
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()

        // UPDATE rather than REPLACE so changing a condition doesn't reset the
        // period and push the next backup a whole interval into the future.
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        Log.i(TAG, "Automatic backup every $minutes min as ${settings.scheduledProfile.name}")
    }

    /**
     * Queues a backup in response to something that happened.
     *
     * Opportunistic triggers ("after every recording") are **coalesced**: the
     * request is delayed by [BackupTrigger.COALESCE_WINDOW_MINUTES] under a
     * shared unique name with `KEEP`, so recording eight memories over an
     * evening produces one backup rather than eight. Protective triggers
     * ("before deleting memories") run without delay, because their value is
     * entirely in being earlier than the thing they protect against.
     */
    suspend fun requestEventBackup(trigger: BackupTrigger, detail: String? = null) {
        val settings = preferences.current()
        if (!settings.hasDestination || trigger !in settings.triggers) return

        val protective = trigger.isProtective
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInputData(
                dataFor(
                    // Always Quick. An event-driven full backup would rewrite
                    // gigabytes of unchanged recordings every time the user
                    // spoke into their phone.
                    profile = BackupProfile.QUICK,
                    reason = trigger.reason,
                    detail = detail
                )
            )
            .apply {
                if (!protective) {
                    setInitialDelay(BackupTrigger.COALESCE_WINDOW_MINUTES, TimeUnit.MINUTES)
                    // Opportunistic backups can afford to wait for good
                    // conditions; protective ones cannot.
                    setConstraints(settings.constraints())
                }
            }
            .build()

        workManager.enqueueUniqueWork(
            if (protective) "$EVENT_WORK_NAME:${trigger.name}" else EVENT_WORK_NAME,
            if (protective) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    /** Runs a backup now, outside the schedule. */
    fun requestImmediateBackup(profile: BackupProfile, reason: BackupReason, detail: String? = null) {
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(dataFor(profile, reason, detail))
                .build()
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
        workManager.cancelUniqueWork(EVENT_WORK_NAME)
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    private fun dataFor(profile: BackupProfile, reason: BackupReason, detail: String?) =
        Data.Builder()
            .putString(BackupWorker.KEY_PROFILE, profile.name)
            .putString(BackupWorker.KEY_REASON, reason.name)
            .putString(BackupWorker.KEY_REASON_DETAIL, detail)
            .build()

    private fun BackupSettings.constraints(): Constraints = Constraints.Builder()
        .setRequiresCharging(conditions.requireCharging)
        .setRequiresBatteryNotLow(conditions.requireBatteryNotLow)
        .setRequiresDeviceIdle(conditions.requireDeviceIdle)
        .setRequiredNetworkType(
            // Local backups need no network at all. The unmetered constraint is
            // meaningful only once a StorageProvider talks to a network, and is
            // honoured now so that turning one on later needs no change here.
            if (conditions.requireUnmetered) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED
        )
        .build()

    companion object {
        private const val TAG = "BackupScheduler"

        const val PERIODIC_WORK_NAME = "echo_backup_periodic"
        const val EVENT_WORK_NAME = "echo_backup_event"
        const val IMMEDIATE_WORK_NAME = "echo_backup_now"
    }
}

/**
 * True for triggers whose whole point is to happen *before* something
 * irreversible, and which therefore must not sit in a coalescing window.
 */
private val BackupTrigger.isProtective: Boolean
    get() = when (this) {
        BackupTrigger.BEFORE_DELETING_MEMORIES,
        BackupTrigger.BEFORE_APP_UPDATE,
        BackupTrigger.BEFORE_DB_MIGRATION,
        BackupTrigger.BEFORE_AI_REINDEX -> true

        BackupTrigger.AFTER_EVERY_RECORDING,
        BackupTrigger.AFTER_N_RECORDINGS,
        BackupTrigger.AFTER_MEDIA_IMPORT -> false
    }
