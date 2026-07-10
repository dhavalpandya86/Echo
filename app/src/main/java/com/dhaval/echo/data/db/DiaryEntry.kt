package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

enum class IntelligenceStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

/**
 * Room Entity representing a recording in the database.
 */
@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey val id: String,
    val title: String,
    val audioPath: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val duration: Long,
    val favorite: Boolean = false,
    val deleted: Boolean = false,
    val language: String? = null,
    
    // AI Content
    val transcript: String? = null,
    val summary: String? = null,
    
    // Status
    val transcriptionStatus: IntelligenceStatus = IntelligenceStatus.PENDING,
    val analysisStatus: IntelligenceStatus = IntelligenceStatus.PENDING
)
