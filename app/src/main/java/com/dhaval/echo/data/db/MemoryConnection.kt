package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

import com.dhaval.echo.domain.ai.RelationshipType
import java.time.LocalDateTime

/**
 * Represents a semantic connection between two memories.
 */
@Entity(
    tableName = "memory_connections",
    primaryKeys = ["fromEntryId", "toEntryId"],
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["fromEntryId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["toEntryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("fromEntryId"),
        Index("toEntryId")
    ]
)
data class MemoryConnection(
    val fromEntryId: String,
    val toEntryId: String,
    val userId: String = "legacy_user",
    val similarity: Float,
    val type: RelationshipType = RelationshipType.RELATED,
    val connectionReason: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now()
)
