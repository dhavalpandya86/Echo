package com.dhaval.echo.data.backup

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The half of backup state that belongs to *this phone*, not to the user.
 *
 * This is deliberately SharedPreferences rather than the app's DataStore, and
 * the reason is the whole point of the class. `ai_preferences` is backed up and
 * restored — that's correct for the user's *choices* (how often to back up, what
 * conditions, whether to encrypt), because those are their intent and should
 * follow them to a new phone.
 *
 * But the values below are facts about one device:
 *
 *  - the destination URI is a permission grant this device holds; restoring one
 *    from an archive would point Echo at a folder it has no access to, possibly
 *    on storage that has never existed here,
 *  - "last backup succeeded at…" from an archive would claim a backup that this
 *    phone never made,
 *  - the password epoch tracks which password this device is currently sealing
 *    with.
 *
 * Keeping them in a separate file means a restore physically cannot overwrite
 * them, instead of relying on restore code to remember to preserve them.
 */
@Singleton
class BackupLocalState @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ── Identity ─────────────────────────────────────────────────────────────

    /**
     * A stable id for this installation, generated on first use.
     *
     * Written into every manifest and unused today. It exists now because it
     * cannot be added retroactively: the day Echo supports two devices, or
     * wants to show "made on your old phone", the archives that matter will be
     * the ones written years earlier. A UUID costs 36 bytes per backup.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: java.util.UUID.randomUUID().toString()
            .also { prefs.edit { putString(KEY_DEVICE_ID, it) } }

    // ── Destination ──────────────────────────────────────────────────────────

    /** The SAF tree the user picked, or null before they've chosen one. */
    var destinationUri: String?
        get() = prefs.getString(KEY_DESTINATION_URI, null)
        set(value) = prefs.edit { putString(KEY_DESTINATION_URI, value) }

    /** A human name for that folder, for the settings row. */
    var destinationLabel: String?
        get() = prefs.getString(KEY_DESTINATION_LABEL, null)
        set(value) = prefs.edit { putString(KEY_DESTINATION_LABEL, value) }

    // ── Password lifecycle ───────────────────────────────────────────────────

    /**
     * Incremented whenever the backup password changes. Archives carry the epoch
     * they were sealed under, so history can say "made with your previous
     * password" instead of leaving the user to conclude an old file is corrupt.
     */
    var passwordEpoch: Int
        get() = prefs.getInt(KEY_PASSWORD_EPOCH, 0)
        set(value) = prefs.edit { putInt(KEY_PASSWORD_EPOCH, value) }

    // ── Last run ─────────────────────────────────────────────────────────────

    var lastRunAtMillis: Long
        get() = prefs.getLong(KEY_LAST_RUN_AT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_RUN_AT, value) }

    var lastSuccessAtMillis: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS_AT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SUCCESS_AT, value) }

    /** One of [com.dhaval.echo.domain.backup.BackupOutcome]'s shapes, as a tag. */
    var lastOutcome: String?
        get() = prefs.getString(KEY_LAST_OUTCOME, null)
        set(value) = prefs.edit { putString(KEY_LAST_OUTCOME, value) }

    /** Why the last run failed, in words the health panel can show verbatim. */
    var lastFailureMessage: String?
        get() = prefs.getString(KEY_LAST_FAILURE, null)
        set(value) = prefs.edit { putString(KEY_LAST_FAILURE, value) }

    var lastArchiveUri: String?
        get() = prefs.getString(KEY_LAST_ARCHIVE_URI, null)
        set(value) = prefs.edit { putString(KEY_LAST_ARCHIVE_URI, value) }

    /** Used to predict whether the *next* backup will fit before starting it. */
    var lastArchiveSizeBytes: Long
        get() = prefs.getLong(KEY_LAST_ARCHIVE_SIZE, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_ARCHIVE_SIZE, value) }

    /**
     * Content fingerprint of the last successful backup. A scheduled run that
     * computes the same fingerprint has nothing new to save and skips writing.
     */
    var lastFingerprint: String?
        get() = prefs.getString(KEY_LAST_FINGERPRINT, null)
        set(value) = prefs.edit { putString(KEY_LAST_FINGERPRINT, value) }

    /** Retires the "your memories are not protected yet" banner, permanently. */
    var hasEverBackedUp: Boolean
        get() = prefs.getBoolean(KEY_HAS_EVER_BACKED_UP, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_EVER_BACKED_UP, value) }

    // ── Event triggers ───────────────────────────────────────────────────────

    /** Counts toward [com.dhaval.echo.domain.backup.BackupTrigger.AFTER_N_RECORDINGS]. */
    var recordingsSinceBackup: Int
        get() = prefs.getInt(KEY_RECORDINGS_SINCE_BACKUP, 0)
        set(value) = prefs.edit { putInt(KEY_RECORDINGS_SINCE_BACKUP, value) }

    // ── Restore hand-off ─────────────────────────────────────────────────────

    /**
     * A restore ends by restarting the process, so its summary has to survive
     * that gap on disk. Read and cleared by the screen that shows it.
     */
    var pendingRestoreReport: String?
        get() = prefs.getString(KEY_PENDING_REPORT, null)
        set(value) = prefs.edit { putString(KEY_PENDING_REPORT, value) }

    /** Until when Undo Restore can still put things back. */
    var undoAvailableUntilMillis: Long
        get() = prefs.getLong(KEY_UNDO_UNTIL, 0)
        set(value) = prefs.edit { putLong(KEY_UNDO_UNTIL, value) }

    /**
     * Set when an archive was restored before anyone had signed in.
     *
     * Restoring from the Welcome screen is the normal path after a reinstall,
     * and at that moment there is no account to re-key the rows to. Every row is
     * scoped by `userId`, so without this the user would sign in and find an
     * empty app holding a perfectly restored diary belonging to nobody. The
     * session layer consumes this on the next sign-in.
     */
    var pendingRekeyFromUserId: String?
        get() = prefs.getString(KEY_PENDING_REKEY, null)
        set(value) = prefs.edit { putString(KEY_PENDING_REKEY, value) }

    fun recordSuccess(atMillis: Long, uri: String, sizeBytes: Long, fingerprint: String) {
        prefs.edit {
            putLong(KEY_LAST_RUN_AT, atMillis)
            putLong(KEY_LAST_SUCCESS_AT, atMillis)
            putString(KEY_LAST_OUTCOME, OUTCOME_SUCCESS)
            putString(KEY_LAST_FAILURE, null)
            putString(KEY_LAST_ARCHIVE_URI, uri)
            putLong(KEY_LAST_ARCHIVE_SIZE, sizeBytes)
            putString(KEY_LAST_FINGERPRINT, fingerprint)
            putBoolean(KEY_HAS_EVER_BACKED_UP, true)
            putInt(KEY_RECORDINGS_SINCE_BACKUP, 0)
        }
    }

    fun recordFailure(atMillis: Long, message: String) {
        prefs.edit {
            putLong(KEY_LAST_RUN_AT, atMillis)
            putString(KEY_LAST_OUTCOME, OUTCOME_FAILED)
            putString(KEY_LAST_FAILURE, message)
        }
    }

    /**
     * A skipped run still counts as the system working. Recording it keeps the
     * health panel from ageing into "no backup in 15 days" on a device where
     * nothing has changed for 15 days.
     */
    fun recordSkipped(atMillis: Long) {
        prefs.edit {
            putLong(KEY_LAST_RUN_AT, atMillis)
            putLong(KEY_LAST_SUCCESS_AT, atMillis)
            putString(KEY_LAST_OUTCOME, OUTCOME_SKIPPED)
            putString(KEY_LAST_FAILURE, null)
        }
    }

    companion object {
        /** Also excluded from Android cloud backup; see res/xml/backup_rules.xml. */
        const val FILE_NAME = "echo_backup_local"

        const val OUTCOME_SUCCESS = "SUCCESS"
        const val OUTCOME_FAILED = "FAILED"
        const val OUTCOME_SKIPPED = "SKIPPED"

        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_DESTINATION_URI = "destination_uri"
        private const val KEY_DESTINATION_LABEL = "destination_label"
        private const val KEY_PASSWORD_EPOCH = "password_epoch"
        private const val KEY_LAST_RUN_AT = "last_run_at"
        private const val KEY_LAST_SUCCESS_AT = "last_success_at"
        private const val KEY_LAST_OUTCOME = "last_outcome"
        private const val KEY_LAST_FAILURE = "last_failure"
        private const val KEY_LAST_ARCHIVE_URI = "last_archive_uri"
        private const val KEY_LAST_ARCHIVE_SIZE = "last_archive_size"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
        private const val KEY_HAS_EVER_BACKED_UP = "has_ever_backed_up"
        private const val KEY_RECORDINGS_SINCE_BACKUP = "recordings_since_backup"
        private const val KEY_PENDING_REPORT = "pending_restore_report"
        private const val KEY_UNDO_UNTIL = "undo_until"
        private const val KEY_PENDING_REKEY = "pending_rekey_from"
    }
}
