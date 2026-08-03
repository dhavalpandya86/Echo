package com.dhaval.echo.data.backup

import kotlinx.serialization.Serializable

/**
 * The restore summary, in a form that survives the process restart.
 *
 * A restore ends by relaunching the app — Room and DataStore are holding files
 * that have just been replaced — so the account of what happened cannot be held
 * in memory or in the database it just swapped. It is written to
 * [BackupLocalState] as JSON and shown once on the next launch.
 *
 * A flattened record rather than the domain [com.dhaval.echo.domain.backup.RestoreReport]
 * because only what the screen renders needs to persist, and a serialization
 * format is a compatibility promise: the fewer fields it carries, the fewer
 * ways a report written by one version can fail to be read by the next.
 */
@Serializable
data class RestoreReportRecord(
    val completedAtMillis: Long,
    val archiveName: String,
    val createdAtMillis: Long,
    val includesMedia: Boolean,
    val memories: Int = 0,
    val collections: Int = 0,
    val people: Int = 0,
    val hasMemoryGraph: Boolean = false,
    val consistency: List<ConsistencyRecord> = emptyList(),
    val rekeyedUserId: Boolean = false,
    val migratedFromVersion: Int? = null,
    val undoAvailableUntilMillis: Long? = null
) {
    val undoAvailable: Boolean
        get() = (undoAvailableUntilMillis ?: 0) > System.currentTimeMillis()
}

/** One "referenced vs present" row of the post-restore consistency scan. */
@Serializable
data class ConsistencyRecord(
    val label: String,
    val referenced: Int,
    val present: Int
) {
    val missing: Int get() = (referenced - present).coerceAtLeast(0)
    val isComplete: Boolean get() = missing == 0
}
