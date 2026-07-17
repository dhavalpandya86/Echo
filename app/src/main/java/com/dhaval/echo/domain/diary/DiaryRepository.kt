package com.dhaval.echo.domain.diary

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.domain.video.VideoAttachment
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing diary entries.
 */
interface DiaryRepository {
    fun getEntryById(id: String): Flow<DiaryEntry?>
    suspend fun updateTitle(id: String, title: String)
    suspend fun toggleFavorite(id: String)
    suspend fun deleteEntry(id: String)
    suspend fun createTextEntry(
        title: String,
        textContent: String,
        imagePaths: List<String> = emptyList(),
        videos: List<VideoAttachment> = emptyList()
    ): String

    // ── Video attachments ────────────────────────────────────────────────
    // A Memory is the parent; videos are attachments belonging to it.

    /** Videos attached to a memory, in attachment order. */
    fun getVideosForEntry(entryId: String): Flow<List<VideoAttachment>>

    /** Appends videos to an existing memory. Already-attached ids are ignored. */
    suspend fun addVideosToEntry(entryId: String, videos: List<VideoAttachment>)

    /**
     * Detaches a video and deletes its file and thumbnail from storage.
     * Removing the row without the bytes would orphan the file forever.
     */
    suspend fun removeVideoFromEntry(entryId: String, videoId: String)
}
