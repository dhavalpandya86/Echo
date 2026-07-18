package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * An edge in the Memory Graph: this memory (an event) involves that entity,
 * in a specific way, with stated evidence and confidence.
 *
 * `inferred = false` means the user's own words support the link (Stage 5).
 * `inferred = true` means Echo derived it by graph traversal (Stage 6) — e.g.
 * the memory says "the logo" and the graph already knows Logo belongs to
 * Oceanis. Inferred links carry capped confidence and are visually
 * distinguished in the UI; that is what keeps enrichment from being
 * hallucination.
 */
@Entity(
    tableName = "memory_entity_links",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["memoryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EntityNode::class,
            parentColumns = ["id"],
            childColumns = ["entityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("memoryId"), Index("entityId")]
)
data class MemoryEntityLink(
    @PrimaryKey val id: String,
    val memoryId: String,
    val entityId: String,
    /** One of [LinkRelation]. */
    val relation: String,
    val confidence: Float,
    /** Supporting quote from the memory, when available. */
    val evidence: String?,
    val inferred: Boolean = false,
    val createdAt: LocalDateTime
)

object LinkRelation {
    const val MENTIONS = "MENTIONS"       // people
    const val BELONGS_TO = "BELONGS_TO"   // projects
    const val DISCUSSES = "DISCUSSES"     // topics
    const val LOCATED_AT = "LOCATED_AT"   // places
    const val INVOLVES = "INVOLVES"       // orgs / products / other
}
