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
    val createdAt: LocalDateTime
)

object ItemKind {
    const val TASK = "TASK"
    const val REMINDER = "REMINDER"
    const val MOOD = "MOOD"
    const val DECISION = "DECISION"
}

object ItemStatus {
    const val OPEN = "OPEN"
    const val DONE = "DONE"
    const val DISMISSED = "DISMISSED"
}
