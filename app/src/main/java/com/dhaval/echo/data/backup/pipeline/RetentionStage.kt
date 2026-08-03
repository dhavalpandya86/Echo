package com.dhaval.echo.data.backup.pipeline

import android.util.Log
import com.dhaval.echo.data.backup.ArchiveNaming
import com.dhaval.echo.data.backup.StorageProvider
import com.dhaval.echo.domain.backup.BackupProgress
import com.dhaval.echo.domain.backup.BackupStage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes archives the user no longer needs — and nothing else.
 *
 * Three rules, each protecting against a specific way retention destroys data:
 *
 *  - **Runs only after verification.** Otherwise a run that writes a corrupt
 *    archive would delete the good ones to make room for it.
 *  - **Separate pools.** Quick backups can fire many times a day from events.
 *    Under a shared "keep last 5" they would evict every Full backup within
 *    hours, leaving the user with five database snapshots and no recordings.
 *  - **Named backups are never touched.** Someone who typed "Before Europe
 *    Trip" has said what they want kept. Retention is for the archives Echo
 *    made on its own initiative.
 *
 * A failure to delete is logged and ignored: not reclaiming space is a far
 * smaller problem than failing a backup that has already succeeded.
 */
@Singleton
class RetentionStage @Inject constructor(
    private val storage: StorageProvider
) : BackupStageStep {

    override val stage = BackupStage.PRUNING

    override suspend fun run(context: BackupContext): StageResult {
        context.onProgress(BackupProgress(stage))

        val keepUri = context.sink?.uri
        val archives = storage.list()
        val limitForOrdinary = context.settings.retentionCount.coerceAtLeast(1)

        ArchiveNaming.Pool.entries.forEach { pool ->
            val limit = if (pool == ArchiveNaming.Pool.CHECKPOINT) {
                ArchiveNaming.CHECKPOINT_RETENTION
            } else {
                limitForOrdinary
            }

            archives
                .mapNotNull { archive -> ArchiveNaming.parse(archive.name)?.let { archive to it } }
                .filter { (_, parsed) -> parsed.pool == pool && !parsed.isNamed }
                .sortedByDescending { (archive, _) -> archive.lastModifiedMillis }
                // Keep the newest `limit`, counting the archive just written if
                // it belongs to this pool. Subtracting one up front looks
                // equivalent and isn't: when the new archive is a Quick backup,
                // every *other* pool would lose one more than the user asked to
                // keep.
                .drop(limit)
                .forEach { (archive, _) ->
                    if (archive.uri == keepUri) return@forEach
                    Log.i(TAG, "Retention: removing ${archive.name}")
                    runCatching { storage.delete(archive.uri) }
                        .onFailure { Log.w(TAG, "Could not remove ${archive.name}", it) }
                }
        }
        return StageResult.Continue
    }

    private companion object {
        const val TAG = "BackupRetention"
    }
}
