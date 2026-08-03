package com.dhaval.echo.domain.backup

/**
 * Whether the user's memories are actually protected right now.
 *
 * This is the question a backup feature exists to answer, and the one most
 * implementations answer badly — by showing a settings screen and leaving the
 * user to infer from the absence of an error that everything is fine. Silence
 * is indistinguishable from a backup system that stopped working three weeks
 * ago.
 *
 * So the state is explicit, and every variant carries what the user would ask
 * next: when, how much, why not, and what to do about it.
 */
sealed interface BackupHealth {

    /** No destination chosen, or no backup ever completed. */
    data class NotConfigured(
        val automaticEnabled: Boolean,
        val hasDestination: Boolean
    ) : BackupHealth

    data class Healthy(
        val lastBackupAtMillis: Long,
        /** Approximate — derived from the interval, since WorkManager only promises "no sooner than". */
        val nextBackupAtMillis: Long?,
        val lastSizeBytes: Long,
        val protects: BackupCounts?,
        val destinationLabel: String?,
        /** Set when the next backup probably won't fit. */
        val spaceWarning: SpaceWarning? = null
    ) : BackupHealth

    data class Failed(
        val reason: String,
        val atMillis: Long,
        val destinationLabel: String?,
        /** True when the fix is to reconnect or re-choose the folder. */
        val isDestinationProblem: Boolean
    ) : BackupHealth

    /**
     * Backups are configured and not failing, but nothing has run for a while —
     * usually because the conditions (charging, idle) haven't been met, or the
     * scheduled work was killed by the system.
     */
    data class Stale(
        val lastBackupAtMillis: Long,
        val daysSince: Int,
        val destinationLabel: String?
    ) : BackupHealth
}

/** "Last backup was 2.8 GB, 2.4 GB free" — said before it fails, not after. */
data class SpaceWarning(
    val requiredBytes: Long,
    val availableBytes: Long
)
