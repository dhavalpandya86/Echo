package com.dhaval.echo.data.backup.pipeline

import android.util.Log
import com.dhaval.echo.data.backup.ArchiveSink
import com.dhaval.echo.data.backup.BackupManifest
import com.dhaval.echo.data.backup.BackupRequest
import com.dhaval.echo.data.backup.MediaFile
import com.dhaval.echo.data.backup.OperationJournal
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.domain.backup.BackupFailure
import com.dhaval.echo.domain.backup.BackupOutcome
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupSettings
import com.dhaval.echo.domain.backup.BackupStage
import com.dhaval.echo.domain.backup.BackupSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How a stage ended.
 *
 * Three outcomes rather than a boolean, because "nothing needed doing" is not a
 * failure and must not be reported as one — a scheduled run on an unchanged
 * device is the system working correctly.
 */
sealed interface StageResult {
    data object Continue : StageResult
    data class Stop(val reason: String) : StageResult
    data class Failed(val failure: BackupFailure) : StageResult
}

/**
 * One step of a backup.
 *
 * Each stage owns one concern, names its own failures, and is an implicit
 * cancellation point. Splitting them out means a stage can be tested by handing
 * it a context and inspecting what it did, rather than by running a whole
 * backup and inferring.
 */
interface BackupStageStep {
    val stage: BackupStage
    suspend fun run(context: BackupContext): StageResult
}

/**
 * The state a backup accumulates as it runs.
 *
 * Deliberately mutable and shared. The alternative — each stage returning a
 * progressively larger immutable record — would thread a dozen fields through
 * five signatures to model a strictly linear pipeline that never branches.
 */
class BackupContext(
    val request: BackupRequest,
    val startedAt: Long,
    val onProgress: (BackupProgress) -> Unit
) {
    var settings: BackupSettings = BackupSettings()

    /** Null when the archive is unencrypted. */
    var password: CharArray? = null

    /** True when the password came from the store and is ours to wipe. */
    var ownsPassword: Boolean = false

    var databaseFile: File? = null
    var snapshotHeld: Boolean = false
    var fingerprint: String = ""

    var media: List<MediaFile> = emptyList()
    var mediaBytes: Long = 0

    var manifest: BackupManifest? = null
    var fileName: String = ""
    var sink: ArchiveSink? = null
    var archiveSizeBytes: Long = 0

    /** Set by the finalize stage once the archive is written and verified. */
    var summary: BackupSummary? = null

    /** Until this is true, everything the run produced is disposable. */
    var committed: Boolean = false
}

/**
 * Runs the stages in order and guarantees the cleanup.
 *
 * The ordering is the design: everything that can refuse to happen — no
 * destination, no password, nothing changed, not enough space — does so in
 * [SnapshotStage] before a single byte is written. That is why a failed backup
 * leaves nothing behind to clean up, and why the cleanup below is a safety net
 * rather than the main mechanism.
 *
 * There is no separate encryption stage. Encryption is a filter on the output
 * stream applied while the archive is written, not a pass over it; giving it a
 * stage would be inventing a step to make the progress display look busier.
 */
@Singleton
class BackupPipeline @Inject constructor(
    private val snapshotStage: SnapshotStage,
    private val archiveStage: ArchiveStage,
    private val verifyStage: VerifyStage,
    private val retentionStage: RetentionStage,
    private val finalizeStage: FinalizeStage,
    private val storage: StorageProvider,
    private val journal: OperationJournal,
    private val databaseSnapshot: com.dhaval.echo.data.backup.DatabaseSnapshot
) {
    suspend fun run(context: BackupContext): BackupOutcome {
        val stages = listOf(
            snapshotStage, archiveStage, verifyStage, retentionStage, finalizeStage
        )
        try {
            for (step in stages) {
                currentCoroutineContext().ensureActive()
                when (val result = step.run(context)) {
                    StageResult.Continue -> Unit
                    is StageResult.Stop -> return BackupOutcome.Skipped(result.reason)
                    is StageResult.Failed -> return BackupOutcome.Failed(result.failure)
                }
            }
            val summary = context.summary
                ?: return BackupOutcome.Failed(
                    BackupFailure.Unexpected(IllegalStateException("Backup produced no summary"))
                )
            context.onProgress(BackupProgress(BackupStage.COMPLETE))
            return BackupOutcome.Success(summary, context.sink?.uri.orEmpty())
        } catch (e: CancellationException) {
            // Cancellation gets the same treatment as failure: the finally below
            // removes the partial archive, and no history, health or retention
            // side effect ever happened because those live in the last stages.
            Log.i(TAG, "Backup cancelled")
            throw e
        } finally {
            cleanUp(context)
        }
    }

    private suspend fun cleanUp(context: BackupContext) {
        if (context.snapshotHeld) {
            databaseSnapshot.end()
            context.snapshotHeld = false
        }
        if (context.ownsPassword) {
            context.password?.fill(' ')
            context.password = null
        }
        if (!context.committed) {
            runCatching { context.sink?.close() }
            context.sink?.uri?.let { uri ->
                Log.i(TAG, "Removing unfinished archive $uri")
                runCatching { storage.delete(uri) }
            }
            journal.clear()
        }
    }

    private companion object {
        const val TAG = "BackupPipeline"
    }
}
