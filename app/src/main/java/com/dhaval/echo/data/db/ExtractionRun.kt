package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import java.time.LocalDateTime

/**
 * One extractor's attempt at one memory.
 *
 * The understanding pipeline runs ~22 single-question extractors one at a time,
 * in the background, long after the user has stopped looking at the screen. That
 * only works if partial progress is durable, so this table is the pipeline's
 * memory of itself: which questions have been asked about this memory, which
 * succeeded, and which are still outstanding.
 *
 * It serves three jobs at once:
 *  - **Resumability.** The runner skips extractors already [Status.COMPLETED],
 *    so a process death mid-run costs one extractor, not the whole memory.
 *  - **Progress.** The detail screen counts these rows to show "Understanding…
 *    7/22". The database *is* the progress channel — no WorkManager plumbing,
 *    and the count survives the app being killed.
 *  - **Diagnosis.** [engine] and [latencyMs] make it answerable why a memory is
 *    thin: the extractor never ran, ran on the heuristic floor because no model
 *    was installed, or failed with [error].
 *
 * Keyed by (memoryId, extractorId): one row per question per memory, replaced
 * on re-run. Cascades with the memory.
 */
@Entity(
    tableName = "memory_extraction_runs",
    primaryKeys = ["memoryId", "extractorId"],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("memoryId"), Index("userId")]
)
data class ExtractionRun(
    val memoryId: String,
    /** Stable registry id — "people", "action_refinement". Never renamed. */
    val extractorId: String,
    val userId: String,
    /** One of [Status]. */
    val status: String,
    /**
     * Which engine actually served this run — the capability the extractor asked
     * for may not be the one it got, because a missing or failed model degrades
     * to the heuristic floor. Recording the actual engine is what makes a thin
     * result explainable rather than mysterious.
     */
    val engine: String,
    /** How many evidence items this extractor produced. 0 is a valid, honest answer. */
    val evidenceCount: Int = 0,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime? = null,
    val latencyMs: Long? = null,
    /** Failure message when [status] is [Status.FAILED]. */
    val error: String? = null
)

object ExtractionRunStatus {
    /** Claimed by the runner; in flight. A stale RUNNING row is retried. */
    const val RUNNING = "RUNNING"

    /** Asked and answered — including "nothing found", which is a real answer. */
    const val COMPLETED = "COMPLETED"

    /** Threw. The run moves on; other extractors are unaffected. */
    const val FAILED = "FAILED"

    /**
     * Deliberately not run: the memory has no content this extractor can speak
     * to, or the capability it needs isn't installed and it has no floor.
     */
    const val SKIPPED = "SKIPPED"
}
