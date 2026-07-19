package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * A weighted edge in the Entity Graph (spec 07): entity ↔ entity, derived from
 * how often two entities co-occur across the user's memories. This is the
 * keystone that turns a bag of memory→entity links into a traversable graph —
 * powering related-entities, World clustering, multi-hop discovery, hybrid
 * search, and graph-driven reflection.
 *
 * Edges are directed (source→target) but maintained symmetrically: both
 * endpoints are rebuilt whenever a memory that mentions them is resolved.
 * `weight` is the number of memories in which the two entities co-occur
 * (stated links only — inferred links never form entity edges). Everything
 * remains explainable: `evidence` records why the edge exists.
 */
@Entity(
    tableName = "entity_relationships",
    foreignKeys = [
        ForeignKey(
            entity = EntityNode::class,
            parentColumns = ["id"],
            childColumns = ["sourceEntityId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EntityNode::class,
            parentColumns = ["id"],
            childColumns = ["targetEntityId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sourceEntityId", "targetEntityId"], unique = true),
        Index("targetEntityId"),
        Index("userId")
    ]
)
data class EntityRelationship(
    @PrimaryKey val id: String,
    val userId: String,
    val sourceEntityId: String,
    val targetEntityId: String,
    /** Relationship kind — RELATED_TO for co-occurrence (typed edges come later). */
    val relation: String,
    /** How many memories the two entities share. Grows as the graph grows. */
    val weight: Int,
    val confidence: Float,
    val evidence: String?,
    val firstSeenAt: LocalDateTime,
    val lastSeenAt: LocalDateTime
)

/** Relationship kinds for the entity graph. */
object EntityRelation {
    const val RELATED_TO = "RELATED_TO"
}
