package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * A node in the Entity Graph — the stable, long-lived things a life is made of
 * (people, projects, places, organisations, topics, products).
 *
 * Entities exist ONCE per user: "Raj" mentioned in a hundred memories is one
 * row here, referenced by a hundred [MemoryEntityLink]s. That identity — as
 * opposed to per-memory tag strings — is what makes the graph a graph.
 */
@Entity(
    tableName = "entities",
    indices = [
        Index("userId", "type", "normalizedName"),
        Index("userId")
    ]
)
data class EntityNode(
    @PrimaryKey val id: String,
    val userId: String,
    /** One of [EntityType]. Stored as TEXT for additive evolution. */
    val type: String,
    /** Display name as first seen: "Raj". */
    val name: String,
    /** Match key: lowercased/trimmed — "raj". Resolution happens on this. */
    val normalizedName: String,
    /** Alternative surface forms learned over time: ["Rajesh", "Raj bhai"]. */
    val aliases: List<String> = emptyList(),
    val firstSeenAt: LocalDateTime,
    val lastSeenAt: LocalDateTime,
    /** Denormalized count of distinct memories linking here. */
    val memoryCount: Int = 0,
    /** e5 vector of the name for fuzzy resolution (MU-4). */
    val embedding: FloatArray? = null,
    /**
     * User archived this entity (corrections loop). Archived entities are hidden
     * from browsing and the graph but still resolve, so re-mentioning one doesn't
     * silently spawn a duplicate. The user, not Echo, decides an entity is noise.
     */
    val archived: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntityNode) return false
        return id == other.id && userId == other.userId && type == other.type &&
            name == other.name && normalizedName == other.normalizedName &&
            aliases == other.aliases && firstSeenAt == other.firstSeenAt &&
            lastSeenAt == other.lastSeenAt && memoryCount == other.memoryCount &&
            (embedding?.contentEquals(other.embedding ?: FloatArray(0)) ?: (other.embedding == null))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + normalizedName.hashCode()
        return result
    }
}

object EntityType {
    const val PERSON = "PERSON"
    const val PROJECT = "PROJECT"
    const val PLACE = "PLACE"
    const val ORG = "ORG"
    const val TOPIC = "TOPIC"
    const val PRODUCT = "PRODUCT"
}
