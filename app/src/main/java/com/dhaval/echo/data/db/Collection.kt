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
    val isAiGenerated: Boolean = false // Future AI support
)
