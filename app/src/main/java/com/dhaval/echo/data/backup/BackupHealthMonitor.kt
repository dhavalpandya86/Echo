package com.dhaval.echo.data.backup

import com.dhaval.echo.domain.backup.BackupCounts
import com.dhaval.echo.domain.backup.BackupHealth
import com.dhaval.echo.domain.backup.SpaceWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out what to tell the user about the state of their backups.
 *
 * Everything here is derived from recorded fact — the last run, the last size,
 * whether the folder answers — rather than from optimism. A health panel that
 * says "protected" because backups are *enabled* is worse than no panel at all.
 */
@Singleton
class BackupHealthMonitor @Inject constructor(
    private val localState: BackupLocalState,
    private val preferences: BackupPreferences,
    private val storage: StorageProvider,
    private val index: BackupIndex
) {
    suspend fun current(): BackupHealth = withContext(Dispatchers.IO) {
        val settings = preferences.current()
        val destination = storage.describe()

        if (!settings.hasDestination || !localState.hasEverBackedUp) {
            return@withContext BackupHealth.NotConfigured(
                automaticEnabled = settings.frequency.isAutomatic,
                hasDestination = settings.hasDestination
            )
        }

        // A destination that has gone away outranks everything else: it is the
        // reason nothing will work, and it has a specific fix. Reporting it as
        // "stale" would send the user looking in the wrong place.
        if (!storage.isWritable()) {
            return@withContext BackupHealth.Failed(
                reason = "The backup folder isn't available",
                atMillis = localState.lastRunAtMillis,
                destinationLabel = destination,
                isDestinationProblem = true
            )
        }

        if (localState.lastOutcome == BackupLocalState.OUTCOME_FAILED) {
            return@withContext BackupHealth.Failed(
                reason = localState.lastFailureMessage ?: "The last backup didn't finish",
                atMillis = localState.lastRunAtMillis,
                destinationLabel = destination,
                isDestinationProblem = false
            )
        }

        val lastSuccess = localState.lastSuccessAtMillis
        val elapsed = System.currentTimeMillis() - lastSuccess
        val intervalMillis = settings.frequency.intervalMinutes?.let { it * 60_000 }

        // Twice the interval, because one missed window is normal — a phone that
        // wasn't charging at 2 a.m. is not a broken backup system. Two in a row
        // means something is actually wrong.
        if (intervalMillis != null && elapsed > intervalMillis * 2) {
            return@withContext BackupHealth.Stale(
                lastBackupAtMillis = lastSuccess,
                daysSince = (elapsed / DAY_MILLIS).toInt(),
                destinationLabel = destination
            )
        }

        BackupHealth.Healthy(
            lastBackupAtMillis = lastSuccess,
            nextBackupAtMillis = intervalMillis?.let { localState.lastRunAtMillis + it },
            lastSizeBytes = localState.lastArchiveSizeBytes,
            protects = latestCounts(),
            destinationLabel = destination,
            spaceWarning = predictSpace()
        )
    }

    /** What the most recent archive actually contains, for the "protects" line. */
    private suspend fun latestCounts(): BackupCounts? =
        index.entries().firstOrNull { it.detailsKnown }?.let { entry ->
            BackupCounts(
                memories = entry.memories,
                recordings = entry.recordings,
                photos = entry.photos,
                mediaBytes = 0,
                databaseBytes = 0
            )
        }

    /**
     * Warns before the failure rather than after it.
     *
     * Best-effort by construction: the free-space figure comes from the
     * destination's own file descriptor, and providers that can't answer return
     * null. A silent skip is the right response there — a warning built on a
     * number we don't have would train the user to ignore warnings.
     */
    private suspend fun predictSpace(): SpaceWarning? {
        val required = localState.lastArchiveSizeBytes
        if (required <= 0) return null

        val available = runCatching {
            // Creating a probe file is the only way to obtain a descriptor on a
            // SAF tree, so it is made and immediately removed.
            val probe = storage.openWrite(PROBE_NAME)
            try {
                probe.freeBytes()
            } finally {
                probe.close()
                storage.delete(probe.uri)
            }
        }.getOrNull() ?: return null

        return if (available < required) SpaceWarning(required, available) else null
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
        const val PROBE_NAME = ".echo-space-probe"
    }
}
