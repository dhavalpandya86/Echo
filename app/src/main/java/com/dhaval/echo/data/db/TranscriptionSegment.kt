package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcription_segments",
    foreignKeys = [
        ForeignKey(
            entity = DiaryEntry::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TranscriptionSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entryId: String,
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val languageCode: String
)
