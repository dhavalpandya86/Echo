package com.dhaval.echo.domain.backup

/**
 * What a backup contains.
 *
 * Both profiles produce the same `.echo` file and restore through the same
 * path; only the presence of media differs. The split exists because Echo backs
 * up on events ("after every recording"), and a ~2.8 GB archive per recording is
 * not a feature, it's a way to wear out a user's storage. A [QUICK] backup still
 * protects every memory, transcript, entity, relationship, collection, task and
 * setting — it omits only the recorded bytes, which are the part that doesn't
 * change once written.
 */
enum class BackupProfile(val label: String) {
    /** Database + settings + every recording, photo, video and thumbnail. */
    FULL("Full backup"),

    /** Database + settings. Typically ~40 MB against a full backup's gigabytes. */
    QUICK("Quick backup");

    val includesMedia: Boolean get() = this == FULL
}

/**
 * Why a backup exists.
 *
 * Recorded in the manifest and shown in history, because "Automatic · 3 Aug" is
 * useless six months later and "Before deleting 24 memories · 3 Aug" is exactly
 * what someone hunting for a specific safety net needs to see.
 */
enum class BackupReason(val label: String) {
    MANUAL("Manual"),
    SCHEDULED("Scheduled"),
    EVENT_RECORDING("After recording"),
    EVENT_DELETE("Before deleting memories"),
    EVENT_IMPORT("After importing media"),
    EVENT_DB_MIGRATION("Before database upgrade"),
    EVENT_AI_REINDEX("Before rebuilding memory"),
    PRE_UPDATE("Before app update"),

    /**
     * The automatic checkpoint taken before a restore commits. This is the
     * archive behind "Undo Restore", and it is never auto-pruned while the
     * restore it guards is still undoable.
     */
    PRE_RESTORE("Before restoring")
}

/**
 * What Echo can say about an archive.
 *
 * Split deliberately in two, because an encrypted archive reveals its
 * [BackupHeaderInfo] to anyone holding the file and its [BackupSummary] only to
 * someone holding the password.
 */
data class BackupHeaderInfo(
    val fileName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
    val encrypted: Boolean,
    val backupFormat: Int
)

/**
 * The full description of an archive, read from its manifest.
 *
 * Everything here lives inside the encrypted payload, so a stolen archive
 * doesn't disclose how many memories someone keeps or what their device is.
 */
data class BackupSummary(
    val header: BackupHeaderInfo,
    val backupId: String,
    val profile: BackupProfile,
    val reason: BackupReason,
    val reasonDetail: String?,
    val name: String?,
    val note: String?,
    val appVersionName: String,
    val databaseVersion: Int,
    val deviceModel: String,
    val passwordEpoch: Int,
    val counts: BackupCounts
) {
    val includesMedia: Boolean get() = profile.includesMedia
}

/**
 * What's in the backup, in the user's units rather than table names.
 *
 * Shown before a restore so someone can tell one archive from another without
 * restoring it to find out.
 */
data class BackupCounts(
    val memories: Int = 0,
    val recordings: Int = 0,
    val photos: Int = 0,
    val videos: Int = 0,
    val collections: Int = 0,
    val people: Int = 0,
    val tasks: Int = 0,
    val calendarEvents: Int = 0,
    val hasMemoryGraph: Boolean = false,
    val mediaBytes: Long = 0,
    val databaseBytes: Long = 0
)

/**
 * Why an operation stopped.
 *
 * A sealed type rather than a message string: the UI needs to offer a different
 * action for each of these ("Choose another folder", "Free up space", "Update
 * Echo"), and a formatted sentence can't be branched on.
 */
sealed interface BackupFailure {
    /** The chosen folder is gone, unmounted, or the grant was revoked. */
    data class DestinationUnavailable(val detail: String?) : BackupFailure

    /** Not enough room to write the archive. */
    data class OutOfSpace(val requiredBytes: Long, val availableBytes: Long) : BackupFailure

    /** Encryption is on but no password is available to an unattended run. */
    data object PasswordUnavailable : BackupFailure

    /** The password did not open this archive. */
    data object WrongPassword : BackupFailure

    /** The archive is truncated, altered, or a checksum did not match. */
    data class Corrupted(val detail: String?) : BackupFailure

    /** The archive comes from a newer Echo than this one can read. */
    data class TooNew(val backupVersion: String, val thisVersion: String) : BackupFailure

    /** Anything unforeseen, kept as-is rather than flattened into a string. */
    data class Unexpected(val cause: Throwable) : BackupFailure
}
