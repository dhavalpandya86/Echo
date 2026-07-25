package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

/**
 * Entity representing a manually curated group of memories.
 */
@Entity(tableName = "collections")
data class EchoCollection(
    @PrimaryKey val id: String,
    val userId: String = "legacy_user",
    val name: String,
    val description: String? = null,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val isAiGenerated: Boolean = false,
    /**
     * For auto-suggested collections: the entity (topic/project) this collection
     * was built around. Gives an auto-collection a stable identity so the curator
     * updates the same one on each run instead of creating duplicates. Null for
     * manually-created collections.
     */
    val sourceEntityId: String? = null
)
