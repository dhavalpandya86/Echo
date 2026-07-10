package com.dhaval.echo.data.search

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.search.SearchFilter
import com.dhaval.echo.domain.search.SearchRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Production implementation of SearchRepository using Room's SQL capabilities.
 */
class DatabaseSearchRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao
) : SearchRepository {

    override fun search(filter: SearchFilter): Flow<List<TimelineEntry>> {
        // For now, we use a simplified version of the filter. 
        // In a full implementation, this could build dynamic SupportSQLiteQueries.
        return diaryEntryDao.searchEntries(
            query = filter.query,
            onlyFavorites = filter.onlyFavorites
        ).map { entries ->
            entries.map { entry ->
                TimelineEntry(
                    id = entry.id,
                    title = entry.title,
                    audioPath = entry.audioPath,
                    durationMillis = entry.duration,
                    timestamp = entry.createdAt,
                    transcription = entry.transcript,
                    isSynced = false,
                    isFavorite = entry.favorite
                )
            }
        }
    }
}
