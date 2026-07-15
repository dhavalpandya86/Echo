package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memory_classifications",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("entryId")]
)
data class MemoryClassificationEntity(
    @PrimaryKey val entryId: String,
    val userId: String = "legacy_user",
    val categories: List<String>,
    val keywords: List<String>,
    val entities: Map<String, List<String>>, // Type to list of values
    val confidence: Float
)
