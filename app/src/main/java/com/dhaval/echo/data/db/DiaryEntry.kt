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
    val videos: List<VideoAttachment>? = null,

    // Embeddings (DB version 12)
    val embedding: FloatArray? = null,
    val embeddingDimensions: Int? = null,
    val embeddingModelVersion: String? = null,
    val embeddingCreatedAt: Long? = null,

    // Photo understanding (DB version 16): the "Echo sees…" summary derived on-device
    // from image labels + EXIF-GPS place. Null when there are no photos / nothing seen.
    val visualSummary: String? = null,

    // Per-photo captions (DB version 17): the user's own words about a photo, keyed
    // by its path. A side map rather than a PhotoAttachment record so imagePaths —
    // wired through the whole app — stays the source of truth for which photos exist.
    val photoCaptions: Map<String, String>? = null,

    // Sentence-corrected text (DB version 19): the transcript with punctuation
    // restored and disfluencies removed — what the user reads, and what every
    // extractor downstream reasons about.
    //
    // Deliberately a separate column: `transcript` stays the verbatim STT output
    // because playback position, TranscriptionSegment timings, and the user's
    // right to see what they actually said all depend on it being untouched.
    // Null until the cleanup extractor runs, or when it has nothing to fix.
    val cleanedText: String? = null
) {
    /** The text to reason about and display: corrected when we have it, raw otherwise. */
    val readableText: String?
        get() = cleanedText?.takeIf { it.isNotBlank() } ?: transcript

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DiaryEntry
        if (id != other.id) return false
        if (userId != other.userId) return false
        if (title != other.title) return false
        if (audioPath != other.audioPath) return false
        if (createdAt != other.createdAt) return false
        if (updatedAt != other.updatedAt) return false
        if (duration != other.duration) return false
        if (favorite != other.favorite) return false
        if (deleted != other.deleted) return false
        if (language != other.language) return false
        if (transcript != other.transcript) return false
        if (summary != other.summary) return false
        if (transcriptionStatus != other.transcriptionStatus) return false
        if (analysisStatus != other.analysisStatus) return false
        if (textContent != other.textContent) return false
        if (imagePaths != other.imagePaths) return false
        if (entryType != other.entryType) return false
        if (videos != other.videos) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        if (embeddingDimensions != other.embeddingDimensions) return false
        if (embeddingModelVersion != other.embeddingModelVersion) return false
        if (embeddingCreatedAt != other.embeddingCreatedAt) return false
        if (visualSummary != other.visualSummary) return false
        if (photoCaptions != other.photoCaptions) return false
        if (cleanedText != other.cleanedText) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + audioPath.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        result = 31 * result + duration.hashCode()
        result = 31 * result + favorite.hashCode()
        result = 31 * result + deleted.hashCode()
        result = 31 * result + (language?.hashCode() ?: 0)
        result = 31 * result + (transcript?.hashCode() ?: 0)
        result = 31 * result + (summary?.hashCode() ?: 0)
        result = 31 * result + transcriptionStatus.hashCode()
        result = 31 * result + analysisStatus.hashCode()
        result = 31 * result + (textContent?.hashCode() ?: 0)
        result = 31 * result + (imagePaths?.hashCode() ?: 0)
        result = 31 * result + entryType.hashCode()
        result = 31 * result + (videos?.hashCode() ?: 0)
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        result = 31 * result + (embeddingDimensions ?: 0)
        result = 31 * result + (embeddingModelVersion?.hashCode() ?: 0)
        result = 31 * result + (embeddingCreatedAt?.hashCode() ?: 0)
        result = 31 * result + (visualSummary?.hashCode() ?: 0)
        result = 31 * result + (photoCaptions?.hashCode() ?: 0)
        result = 31 * result + (cleanedText?.hashCode() ?: 0)
        return result
    }
}

object EntryType {
    const val VOICE = "VOICE"
    const val TEXT = "TEXT"
    const val MIXED = "MIXED"
}
