package com.dhaval.echo.domain.backup

/**
 * What Echo is doing right now, in the user's words.
 *
 * Deliberately a named stage rather than a single percentage. A percentage over
 * a backup whose media size isn't known until it's enumerated, whose
 * compression ratio varies by file, and which then reads everything back again,
 * is a number invented to look reassuring. "Backing up recordings · 412 of 1,766"
 * is true, and people tolerate a long wait far better when they can see what
 * it's actually doing.
 *
 * There is no "Encrypting" stage because encryption isn't one: it's a filter on
 * the output stream, applied as bytes are written. Showing it as its own step
 * would be inventing a phase to fill a progress bar.
 */
enum class BackupStage(val label: String) {
    PREPARING("Preparing"),
    DATABASE("Backing up memories"),
    MEDIA("Backing up recordings"),
    VERIFYING("Verifying"),
    PRUNING("Cleaning up"),
    COMPLETE("Complete")
}

/**
 * @property bytesDone / [bytesTotal] Meaningful only inside [BackupStage.MEDIA]
 *   and [BackupStage.VERIFYING]; zero elsewhere, where the work is too short to
 *   be worth counting.
 */
data class BackupProgress(
    val stage: BackupStage,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0
)

/** How a backup run ended. */
sealed interface BackupOutcome {
    /** Written, read back, and verified. Only now is it safe to call it a backup. */
    data class Success(val summary: BackupSummary, val uri: String) : BackupOutcome

    /**
     * Nothing changed since the last successful backup, so nothing was written.
     * Recorded rather than silently dropped: a health panel that goes quiet is
     * indistinguishable from one that's broken.
     */
    data class Skipped(val reason: String) : BackupOutcome

    data class Failed(val failure: BackupFailure) : BackupOutcome

    /** Cancelled by the user. Leaves no archive, no history row, no pruning. */
    data object Cancelled : BackupOutcome
}

// ── Restore ──────────────────────────────────────────────────────────────────

/**
 * Restore happens in staging and commits at the end, so these stages run
 * *before* anything the user can lose is touched. [READY] is the point where
 * everything that could fail already has, and the user is asked to confirm.
 */
enum class RestoreStage(val label: String) {
    READING("Reading backup"),
    EXTRACTING("Unpacking"),
    MIGRATING("Upgrading database"),
    RELINKING("Reconnecting recordings"),
    CHECKING("Checking everything is there"),
    READY("Ready to restore"),
    COMMITTING("Restoring"),
    COMPLETE("Complete")
}

data class RestoreProgress(
    val stage: RestoreStage,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0
)

/**
 * How many of a kind of file the database expects, and how many are actually
 * there. The difference is the honest answer to "did everything come back?".
 *
 * A non-zero [missing] is normal and expected when restoring a
 * [BackupProfile.QUICK] archive — it carries no media by design. It is a
 * genuine warning only when the archive claimed to include media.
 */
data class MediaConsistency(
    val label: String,
    val referenced: Int,
    val present: Int
) {
    val missing: Int get() = (referenced - present).coerceAtLeast(0)
    val isComplete: Boolean get() = missing == 0
}

/**
 * The result of staging a restore in full — what *will* happen, established by
 * having already done it somewhere safe.
 *
 * This is not a simulation. The migration has run, the paths have been
 * rewritten, the userId has been re-keyed and the media has been checked, all
 * against the staged copy. Committing is a rename and a swap. Nothing can pass
 * this check and then fail the commit, which is the whole point of doing it
 * this way round.
 */
data class RestorePlan(
    val summary: BackupSummary,
    val consistency: List<MediaConsistency>,
    val willRekeyUserId: Boolean,
    val migratedFromVersion: Int?,
    val estimatedCommitMillis: Long,
    val canUndo: Boolean,
    val undoIncludesMedia: Boolean
)

/**
 * Shown once on the next launch, because the restore itself ends by restarting
 * the process — the user would otherwise be dropped onto a home screen with no
 * account of what just happened to their diary.
 */
data class RestoreReport(
    val completedAtMillis: Long,
    val summary: BackupSummary,
    val consistency: List<MediaConsistency>,
    val rekeyedUserId: Boolean,
    val migratedFromVersion: Int?,
    val undoAvailableUntilMillis: Long?
) {
    val undoAvailable: Boolean get() = undoAvailableUntilMillis != null

    /** True when the archive simply didn't carry media, rather than lost it. */
    val mediaWasNotIncluded: Boolean get() = !summary.includesMedia
}

sealed interface RestoreOutcome {
    data class Staged(val plan: RestorePlan) : RestoreOutcome
    data class Committed(val report: RestoreReport) : RestoreOutcome
    data class Failed(val failure: BackupFailure) : RestoreOutcome
    data object Cancelled : RestoreOutcome
}
