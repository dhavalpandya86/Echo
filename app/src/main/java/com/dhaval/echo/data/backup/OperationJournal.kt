package com.dhaval.echo.data.backup

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A durable note of what Echo was in the middle of.
 *
 * Backups and restores move real files around and can be killed at any instant
 * — low memory, a reboot, a user swiping the app away mid-restore. Without a
 * record of the operation, the next launch would find staging directories,
 * holding directories and half-written archives with no way to tell a crash
 * from a deliberate state, and no safe action to take.
 *
 * The journal is a single small JSON file, written before the operation starts
 * and deleted when it finishes. Finding one at launch means the app died
 * mid-flight, and [recover] knows what to do about each case.
 *
 * It lives in `filesDir` rather than the database on purpose: a restore
 * *replaces* the database, so a journal stored there would be swapped out
 * exactly when it is most needed.
 */
@Singleton
class OperationJournal @Inject constructor(
    private val roots: EchoStorageRoots,
    private val storage: StorageProvider
) {
    private val file: File get() = File(roots.internalFiles, FILE_NAME)

    fun beginBackup(archiveUri: String) = write(
        JournalRecord(
            operation = OP_BACKUP,
            startedAtMillis = System.currentTimeMillis(),
            archiveUri = archiveUri
        )
    )

    fun beginRestore(archiveUri: String?) = write(
        JournalRecord(
            operation = OP_RESTORE,
            startedAtMillis = System.currentTimeMillis(),
            archiveUri = archiveUri,
            phase = PHASE_STAGING
        )
    )

    /**
     * Marks the point of no easy return. Between this call and [clear], the
     * real directories are mid-swap and only [recover] can finish the job.
     */
    fun markCommitting() {
        read()?.let { write(it.copy(phase = PHASE_COMMITTING)) }
    }

    fun markCommitted() {
        read()?.let { write(it.copy(phase = PHASE_COMMITTED)) }
    }

    fun clear() {
        runCatching { file.delete() }
    }

    fun pending(): JournalRecord? = read()

    /**
     * Resolves whatever the last run left behind. Safe to call at every launch;
     * does nothing when there is no journal.
     */
    suspend fun recover() {
        val record = read() ?: return
        Log.w(TAG, "Recovering interrupted ${record.operation} (phase=${record.phase})")

        when (record.operation) {
            // A backup writes only to the destination, never over live data, so
            // rolling back is simply removing the partial archive. Nothing was
            // recorded as successful, so retention and health are untouched.
            OP_BACKUP -> record.archiveUri?.let { storage.delete(it) }

            OP_RESTORE -> when (record.phase) {
                // Staging happens entirely in scratch directories. Deleting them
                // leaves the user exactly where they started.
                PHASE_STAGING -> clearStaging()

                // The dangerous window: some directories were swapped and some
                // were not. Rolling *forward* is the only consistent option —
                // the pre-restore checkpoint still exists, so the user can still
                // undo afterwards, whereas a half-rolled-back state is
                // recoverable by nobody.
                PHASE_COMMITTING -> completeCommit()

                // Everything swapped; only the scratch copies remain. Holding
                // directories are deliberately kept, because Undo still applies.
                PHASE_COMMITTED -> clearStaging()

                else -> clearStaging()
            }
        }
        clear()
    }

    /** Removes staging only; holding directories belong to Undo. */
    private fun clearStaging() {
        roots.mediaRoots.values.forEach { root ->
            runCatching { roots.stagingFor(root).deleteRecursively() }
        }
        runCatching { roots.stagedDatabase.delete() }
    }

    /**
     * Finishes an interrupted swap: for anything still staged, park the live
     * copy and move the staged one into place. Directories already swapped have
     * no staging left and are skipped, which makes this idempotent.
     */
    private fun completeCommit() {
        roots.mediaRoots.values.forEach { root ->
            val staged = roots.stagingFor(root)
            if (!staged.exists()) return@forEach
            val holding = roots.holdingFor(root)
            if (root.exists() && !holding.exists()) {
                runCatching { root.renameTo(holding) }
            }
            runCatching { staged.renameTo(root) }
        }

        val stagedDb = roots.stagedDatabase
        if (stagedDb.exists()) {
            if (roots.database.exists() && !roots.heldDatabase.exists()) {
                runCatching { roots.database.renameTo(roots.heldDatabase) }
            }
            runCatching { stagedDb.renameTo(roots.database) }
            // The restored database brings its own contents; any log left from
            // the replaced one describes a file that no longer exists and would
            // corrupt the new one if SQLite tried to replay it.
            runCatching { File("${roots.database.path}-wal").delete() }
            runCatching { File("${roots.database.path}-shm").delete() }
        }
    }

    private fun read(): JournalRecord? = runCatching {
        if (!file.exists()) return null
        BackupManifest.json.decodeFromString<JournalRecord>(file.readText())
    }.getOrElse {
        Log.w(TAG, "Unreadable journal; discarding", it)
        clear()
        null
    }

    private fun write(record: JournalRecord) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(BackupManifest.json.encodeToString(record))
        }.onFailure { Log.e(TAG, "Could not write operation journal", it) }
    }

    companion object {
        private const val TAG = "BackupJournal"
        private const val FILE_NAME = "backup_journal.json"

        const val OP_BACKUP = "BACKUP"
        const val OP_RESTORE = "RESTORE"

        const val PHASE_STAGING = "STAGING"
        const val PHASE_COMMITTING = "COMMITTING"
        const val PHASE_COMMITTED = "COMMITTED"
    }
}

@Serializable
data class JournalRecord(
    val operation: String,
    val startedAtMillis: Long,
    val archiveUri: String? = null,
    val phase: String? = null
)
