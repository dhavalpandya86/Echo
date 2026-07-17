package com.dhaval.echo.domain.video

import kotlinx.serialization.Serializable

/**
 * A video attached to a Memory.
 *
 * Videos belong to a Memory the same way photos do — the Memory is the parent
 * object. Where photos are stored as bare paths (`imagePaths`), a video needs
 * metadata a path can't carry (duration for the UI, size for storage
 * accounting, a thumbnail to render without decoding the file), so each video
 * is a small serialized record instead of a plain string.
 *
 * Persisted as a JSON list in `diary_entries.videos` via [com.dhaval.echo.data.db.Converters].
 *
 * @property id Stable identifier; also the basename of the video and thumbnail files.
 * @property path Absolute path to the video in app-managed storage.
 * @property durationMillis Playback duration. 0 when it could not be read.
 * @property sizeBytes Size of the video file on disk.
 * @property thumbnailPath Absolute path to the extracted poster frame; null until generated.
 * @property createdAtMillis Epoch millis. Epoch rather than LocalDateTime because this
 *   record is serialized to JSON, and epoch needs no custom serializer.
 */
@Serializable
data class VideoAttachment(
    val id: String,
    val path: String,
    val durationMillis: Long = 0L,
    val sizeBytes: Long = 0L,
    val thumbnailPath: String? = null,
    val createdAtMillis: Long = 0L
)
