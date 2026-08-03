package com.dhaval.echo.data.backup

import android.util.Log
import com.dhaval.echo.domain.backup.BackupTrigger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The events worth protecting against, wired to the moments they happen.
 *
 * Every one of these produces a **Quick** backup — database and settings only,
 * around 40 MB. That is what makes them affordable at all: a full media archive
 * per recording would rewrite gigabytes of unchanged audio every time someone
 * spoke into their phone, and the feature would have to be either off or
 * ruinous.
 *
 * Each method is a no-op unless the user has enabled that trigger and chosen a
 * destination, so call sites can invoke them unconditionally without knowing
 * anything about backup settings.
 */
@Singleton
class BackupTriggers @Inject constructor(
    private val preferences: BackupPreferences,
    private val localState: BackupLocalState,
    private val scheduler: BackupScheduler,
    private val maintenanceMode: MaintenanceMode
) {
    /**
     * Handles both "after every recording" and "after every N recordings",
     * because they are the same event counted differently.
     */
    suspend fun onRecordingSaved() = guard {
        val settings = preferences.current()

        if (BackupTrigger.AFTER_EVERY_RECORDING in settings.triggers) {
            scheduler.requestEventBackup(BackupTrigger.AFTER_EVERY_RECORDING)
            return@guard
        }

        if (BackupTrigger.AFTER_N_RECORDINGS in settings.triggers) {
            val count = localState.recordingsSinceBackup + 1
            localState.recordingsSinceBackup = count
            if (count >= settings.recordingBatchSize) {
                scheduler.requestEventBackup(
                    BackupTrigger.AFTER_N_RECORDINGS,
                    detail = "After $count new recordings"
                )
            }
        }
    }

    suspend fun onMediaImported(count: Int) = guard {
        scheduler.requestEventBackup(
            BackupTrigger.AFTER_MEDIA_IMPORT,
            detail = if (count == 1) "After adding a photo" else "After adding $count photos"
        )
    }

    /**
     * Note that Echo soft-deletes: [com.dhaval.echo.data.diary.RealDiaryRepository]
     * marks a memory `deleted` rather than removing it, so this is a second line
     * of defence rather than the only one. It earns its place for the case the
     * soft delete cannot cover — a user who deletes, then later purges, then
     * changes their mind.
     */
    suspend fun onBeforeDeletingMemories(count: Int) = guard {
        scheduler.requestEventBackup(
            BackupTrigger.BEFORE_DELETING_MEMORIES,
            detail = if (count == 1) "Before deleting a memory" else "Before deleting $count memories"
        )
    }

    /**
     * Rebuilding memory re-derives every entity, link and inference from the
     * raw text. It is idempotent in principle, but it rewrites a large part of
     * the graph, and having the previous one on disk is cheap insurance.
     */
    suspend fun onBeforeMemoryRebuild() = guard {
        scheduler.requestEventBackup(
            BackupTrigger.BEFORE_AI_REINDEX,
            detail = "Before rebuilding what Echo knows"
        )
    }

    suspend fun onBeforeDatabaseUpgrade(fromVersion: Int, toVersion: Int) = guard {
        scheduler.requestEventBackup(
            BackupTrigger.BEFORE_DB_MIGRATION,
            detail = "Before upgrading the database (v$fromVersion → v$toVersion)"
        )
    }

    suspend fun onAppUpdated(previousVersion: Long, currentVersion: Long) = guard {
        scheduler.requestEventBackup(
            BackupTrigger.BEFORE_APP_UPDATE,
            detail = "After updating Echo ($previousVersion → $currentVersion)"
        )
    }

    /**
     * A restore is replacing the database right now. Archiving a diary caught
     * between two states would produce a backup of nothing coherent, and would
     * then occupy a retention slot that a real backup needs.
     */
    private inline fun guard(block: () -> Unit) {
        if (maintenanceMode.isActive) return
        runCatching { block() }.onFailure { Log.w(TAG, "Backup trigger failed", it) }
    }

    private companion object {
        const val TAG = "BackupTriggers"
    }
}
