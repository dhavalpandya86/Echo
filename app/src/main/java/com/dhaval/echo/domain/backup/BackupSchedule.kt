package com.dhaval.echo.domain.backup

/**
 * How often Echo protects itself without being asked.
 *
 * The short intervals are deliberate and safe because of [defaultProfile]: at
 * 15 minutes Echo writes a ~40 MB database archive, not a 2.8 GB media one.
 * Offering "every 15 minutes" alongside a full media backup would be offering
 * users a way to destroy their storage.
 */
enum class BackupFrequency(val label: String, val intervalMinutes: Long?) {
    MANUAL("Only when I ask", null),
    EVERY_15_MINUTES("Every 15 minutes", 15),
    HOURLY("Every hour", 60),
    EVERY_6_HOURS("Every 6 hours", 6 * 60),
    EVERY_12_HOURS("Every 12 hours", 12 * 60),
    DAILY("Every day", 24 * 60),
    EVERY_2_DAYS("Every 2 days", 2 * 24 * 60),
    EVERY_5_DAYS("Every 5 days", 5 * 24 * 60),
    WEEKLY("Every week", 7 * 24 * 60),
    EVERY_10_DAYS("Every 10 days", 10 * 24 * 60),
    MONTHLY("Every month", 30 * 24 * 60),
    EVERY_3_MONTHS("Every 3 months", 90 * 24 * 60);

    val isAutomatic: Boolean get() = intervalMinutes != null

    /**
     * Anything running more than twice a day carries no media unless the user
     * overrides it. Media is the expensive, slow-changing part; the database is
     * the part that actually changes between two runs an hour apart.
     */
    val defaultProfile: BackupProfile
        get() = if (intervalMinutes != null && intervalMinutes < 12 * 60) {
            BackupProfile.QUICK
        } else {
            BackupProfile.FULL
        }

    companion object {
        /** WorkManager will not schedule a period shorter than this. */
        const val WORK_MANAGER_FLOOR_MINUTES = 15L

        fun from(raw: String?): BackupFrequency =
            entries.firstOrNull { it.name == raw } ?: DAILY
    }
}

/**
 * When an automatic backup is allowed to run.
 *
 * Maps directly onto WorkManager `Constraints`. Defaults protect the battery
 * and the user's data plan without being so strict that backups never happen:
 * a phone on a charger overnight satisfies all of them.
 */
data class BackupConditions(
    val requireCharging: Boolean = true,
    val requireUnmetered: Boolean = false,
    val requireBatteryNotLow: Boolean = true,
    val requireDeviceIdle: Boolean = false
)

/**
 * Backups fired by something happening rather than by the clock.
 *
 * Every one of these produces a [BackupProfile.QUICK] archive and is coalesced
 * inside [BackupTriggers.COALESCE_WINDOW_MINUTES], so recording ten memories in
 * an afternoon yields one backup rather than ten.
 */
enum class BackupTrigger(val label: String, val reason: BackupReason) {
    AFTER_EVERY_RECORDING("After every recording", BackupReason.EVENT_RECORDING),
    AFTER_N_RECORDINGS("After every 10 recordings", BackupReason.EVENT_RECORDING),
    AFTER_MEDIA_IMPORT("After importing photos or video", BackupReason.EVENT_IMPORT),
    BEFORE_DELETING_MEMORIES("Before deleting memories", BackupReason.EVENT_DELETE),
    BEFORE_APP_UPDATE("Before an app update", BackupReason.PRE_UPDATE),
    BEFORE_DB_MIGRATION("Before a database upgrade", BackupReason.EVENT_DB_MIGRATION),
    BEFORE_AI_REINDEX("Before rebuilding memory", BackupReason.EVENT_AI_REINDEX);

    companion object {
        /**
         * Enabled out of the box: the two moments where losing the last few
         * minutes of work would hurt most, and where the user is least likely
         * to have thought about backups at all.
         */
        val DEFAULTS = setOf(BEFORE_DELETING_MEMORIES, BEFORE_DB_MIGRATION)

        const val COALESCE_WINDOW_MINUTES = 15L

        /** How many recordings [AFTER_N_RECORDINGS] waits for. */
        const val DEFAULT_RECORDING_BATCH = 10
    }
}

/**
 * Everything the user has chosen about backups.
 *
 * Deliberately split into two halves by [restorable] / device-local. The whole
 * object is persisted to the same DataStore that a restore overwrites, so the
 * device-local half — which folder we may write to, when we last succeeded,
 * which password era we're in — must survive a restore untouched. Restoring a
 * stale destination URI from an archive would point Echo at a folder it has no
 * permission to, on a phone that may never have had it.
 */
data class BackupSettings(
    // ── Restored from a backup: these are the user's intent, not this device's state
    val frequency: BackupFrequency = BackupFrequency.DAILY,
    val conditions: BackupConditions = BackupConditions(),
    val retentionCount: Int = DEFAULT_RETENTION,
    val includeMedia: Boolean = true,
    val encryptionEnabled: Boolean = true,
    val triggers: Set<BackupTrigger> = BackupTrigger.DEFAULTS,
    val recordingBatchSize: Int = BackupTrigger.DEFAULT_RECORDING_BATCH,

    // ── Device-local: never overwritten by a restore
    val destinationUri: String? = null,
    val passwordEpoch: Int = 0
) {
    /** True once the user has picked somewhere to write to. */
    val hasDestination: Boolean get() = !destinationUri.isNullOrBlank()

    /**
     * The profile a scheduled run should use: the frequency's default, unless
     * the user has explicitly turned media off.
     */
    val scheduledProfile: BackupProfile
        get() = when {
            !includeMedia -> BackupProfile.QUICK
            else -> frequency.defaultProfile
        }

    companion object {
        const val DEFAULT_RETENTION = 5

        /** Keeping "the last 5" of each kind separately needs a floor of 1. */
        val RETENTION_CHOICES = listOf(1, 3, 5, 10, 20)
    }
}
