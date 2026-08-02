package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * The evidence board for claims that are not entities: things this memory
 * asserts happened or should happen — tasks, reminders, mood, decisions.
 *
 * These are per-memory facts (an entity is a long-lived node; "call Raj
 * tomorrow" belongs to one moment). Tasks and reminders carry a lifecycle
 * status so they can later surface as an actionable list.
 */
@Entity(
    tableName = "extracted_items",
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
data class ExtractedItem(
    @PrimaryKey val id: String,
    val memoryId: String,
    val userId: String,
    /** One of [ItemKind]. */
    val kind: String,
    /** The claim: "Call Raj about the Oceanis logo", "Inspired". */
    val value: String,
    /** Resolved date for TASK/REMINDER ("tomorrow" → an actual date). */
    val dueAtMillis: Long? = null,
    val confidence: Float,
    /** Supporting quote from the memory. */
    val evidence: String?,
    /** One of [ItemStatus]; meaningful for TASK/REMINDER. */
    val status: String = ItemStatus.OPEN,
    val createdAt: LocalDateTime,
    /**
     * Which registry extractor produced this row. Lets the runner replace one
     * extractor's output in isolation when it re-runs, leaving the other 21
     * untouched. Null for rows written before the staged pipeline existed.
     */
    val extractorId: String? = null
)

object ItemKind {
    const val TASK = "TASK"
    const val REMINDER = "REMINDER"
    const val MOOD = "MOOD"
    const val DECISION = "DECISION"

    // ── Facets ────────────────────────────────────────────────────────
    // One interpretive verdict about the memory as a whole. Unlike TASK and
    // REMINDER these carry no lifecycle — [ItemStatus] stays OPEN and is not
    // meaningful. They are stored here rather than as columns on the memory so
    // the set can grow additively (`kind` is TEXT) and so each one keeps the
    // confidence + evidence quote that every other conclusion carries.
    //
    // Safe to add: every actionable query filters `kind IN ('TASK','REMINDER')`,
    // so facets cannot leak into the Tasks screen or the reminder scheduler.

    /** What the user means to bring about: "Buy equipment". */
    const val INTENT = "INTENT"

    /** What sort of memory this is: "Commitment", "Experience", "Decision". */
    const val MEMORY_TYPE = "MEMORY_TYPE"

    /** Which area of life: "Family", "Work", "Health". */
    const val CATEGORY = "CATEGORY"

    /** How much it matters: "High", "Medium", "Low". */
    const val PRIORITY = "PRIORITY"

    /** The facet kinds, for querying and for the detail screen's facet row. */
    val FACETS = setOf(INTENT, MEMORY_TYPE, CATEGORY, PRIORITY)
}

object ItemStatus {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val DISMISSED = "DISMISSED"
}
