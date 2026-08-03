package com.dhaval.echo.data.backup

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupOutcome
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupReason
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs a backup in the background, on a schedule or in response to an event.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: BackupEngine,
    private val maintenanceMode: MaintenanceMode
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // A restore is mid-flight. Backing up now would archive a diary caught
        // between two states — and the restore cancels this work anyway, so
        // this is the belt to that braces.
        if (maintenanceMode.isActive) {
            Log.i(TAG, "Skipping backup: a restore is in progress")
            return Result.success()
        }

        val request = BackupRequest(
            profile = inputData.getString(KEY_PROFILE)
                ?.let { runCatching { BackupProfile.valueOf(it) }.getOrNull() }
                ?: BackupProfile.QUICK,
            reason = inputData.getString(KEY_REASON)
                ?.let { runCatching { BackupReason.valueOf(it) }.getOrNull() }
                ?: BackupReason.SCHEDULED,
            reasonDetail = inputData.getString(KEY_REASON_DETAIL)
        )

        return when (val outcome = engine.run(request)) {
            is BackupOutcome.Success -> Result.success()

            // A skip is a success: the archive that would have been written
            // already exists, byte for byte.
            is BackupOutcome.Skipped -> Result.success()

            BackupOutcome.Cancelled -> Result.failure()

            is BackupOutcome.Failed -> {
                Log.w(TAG, "Backup failed: ${outcome.failure.describe()}")
                if (outcome.failure.isWorthRetrying) Result.retry() else Result.failure()
            }
        }
    }

    /**
     * Retry only helps when the obstacle might clear on its own. A missing
     * folder, a full disk or an absent password all need a person; retrying
     * them on a backoff just burns battery and fills the log with the same
     * failure, while the health panel is already telling the user what to fix.
     */
    private val BackupFailure.isWorthRetrying: Boolean
        get() = when (this) {
            is BackupFailure.Unexpected, is BackupFailure.Corrupted -> true
            else -> false
        }

    companion object {
        private const val TAG = "BackupWorker"

        const val KEY_PROFILE = "profile"
        const val KEY_REASON = "reason"
        const val KEY_REASON_DETAIL = "reason_detail"
    }
}
