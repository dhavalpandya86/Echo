package com.dhaval.echo.domain.timeline

import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/**
 * Domain model representing a single recording entry in the timeline.
 * Designed to be agnostic of the data source (Local DB or Cloud).
 */
data class TimelineEntry(
    val id: String,
    val title: String,
    val audioPath: String,
    val durationMillis: Long,
    val timestamp: LocalDateTime,
    val transcription: String? = null,
    val isSynced: Boolean = false,
    val isFavorite: Boolean = false
)

/**
 * Repository interface for fetching timeline data.
 * Supports future pagination, search, and sync.
 */
interface TimelineRepository {
    /**
     * Returns a flow of timeline entries.
     * @param query Optional search query to filter entries by title or tag.
     */
    fun getTimelineEntries(query: String = ""): Flow<List<TimelineEntry>>

    /**
     * Toggles the favorite status of a timeline entry.
     */
    suspend fun toggleFavorite(entryId: String)
}
