package com.dhaval.echo.data.backup

import android.util.Log
import com.dhaval.echo.data.backup.pipeline.BackupContext
import com.dhaval.echo.data.backup.pipeline.BackupPipeline
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupOutcome
import com.dhaval.echo.domain.backup.BackupProfile
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What to back up, and why.
 *
 * @param password overrides the stored one. Null means "use whatever
 *   [BackupPasswordStore] holds", which is how unattended runs work.
 * @param force skips the unchanged-content check. Manual backups set it,
 *   because a user who taps *Back up now* and is told "nothing changed" has
 *   been handed a puzzle instead of a backup.
 */
data class BackupRequest(
    val profile: BackupProfile,
    val reason: BackupReason,
    val reasonDetail: String? = null,
    val name: String? = null,
    val note: String? = null,
    val password: CharArray? = null,
    val force: Boolean = false
) {
    // Data class with an array member: identity comparison is the honest
    // default here, since two requests holding different char arrays with the
    // same contents are not usefully "equal" to anything in this codebase.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The entry point for making a backup.
 *
 * Deliberately thin: it builds the context, hands it to [BackupPipeline], and
 * records the outcome. All the work — and all the ordering that makes failure
 * safe — lives in the stages, where each step can be tested on its own.
 */
@Singleton
class BackupEngine @Inject constructor(
    private val pipeline: BackupPipeline,
    private val localState: BackupLocalState
) {
    suspend fun run(
        request: BackupRequest,
        onProgress: (BackupProgress) -> Unit = {}
    ): BackupOutcome = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val context = BackupContext(request, startedAt, onProgress)

        try {
            pipeline.run(context).also { outcome ->
                when (outcome) {
                    is BackupOutcome.Failed ->
                        localState.recordFailure(startedAt, outcome.failure.describe())

                    // A skip is recorded as a successful run. It is the system
                    // working: the archive that would have been written already
                    // exists, byte for byte. Leaving it unrecorded would let the
                    // health panel age into "no backup in 15 days" on a device
                    // where nothing has changed for 15 days.
                    is BackupOutcome.Skipped -> localState.recordSkipped(startedAt)

                    else -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            localState.recordFailure(startedAt, e.message ?: "Unexpected error")
            BackupOutcome.Failed(BackupFailure.Unexpected(e))
        }
    }

    private companion object {
        const val TAG = "BackupEngine"
    }
}

/**
 * Turns a failure into a sentence that names the problem and implies the fix.
 * The health panel shows this verbatim, so it should never mention a class name
 * or an exception.
 */
fun BackupFailure.describe(): String = when (this) {
    is BackupFailure.DestinationUnavailable ->
        "The backup folder${detail?.let { " ($it)" }.orEmpty()} isn't available"

    is BackupFailure.OutOfSpace ->
        "Not enough space — needs ${requiredBytes / 1_048_576} MB, " +
            "${availableBytes / 1_048_576} MB free"

    BackupFailure.PasswordUnavailable -> "No backup password is set on this device"
    BackupFailure.WrongPassword -> "That password didn't open this backup"
    is BackupFailure.Corrupted -> detail ?: "The backup file appears damaged"
    is BackupFailure.TooNew -> "Made with Echo $backupVersion; this is Echo $thisVersion"
    is BackupFailure.Unexpected -> cause.message ?: "Unexpected error"
}
