package com.dhaval.echo.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.video.VideoAttachment
import java.time.LocalDateTime

@Entity(tableName = "diary_entries")
data class DiaryEntry(
    @PrimaryKey val id: String,
    val userId: String = "legacy_user",
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
    val analysisStatus: IntelligenceStatus = IntelligenceStatus.PENDING,

    // Rich content (DB version 10)
    val textContent: String? = null,
    val imagePaths: List<String>? = null,
    val entryType: String = EntryType.VOICE,

    // Video attachments (DB version 11).
    // Photos are bare paths; videos carry metadata (duration/size/thumbnail),
    // so they are stored as serialized records. See VideoAttachment.
    val videos: List<VideoAttachment>? = null
)

object EntryType {
    const val VOICE = "VOICE"
    const val TEXT = "TEXT"
    const val MIXED = "MIXED"
}
