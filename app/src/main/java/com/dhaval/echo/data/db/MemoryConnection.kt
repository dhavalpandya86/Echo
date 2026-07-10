package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

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
    val similarity: Float,
    val connectionReason: String? = null
)
