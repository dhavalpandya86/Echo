package com.dhaval.echo.data.timeline

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.timeline.TimelineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * Production implementation of TimelineRepository using Room Database.
 */
class DatabaseTimelineRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao
) : TimelineRepository {
    override fun getTimelineEntries(query: String): Flow<List<TimelineEntry>> {
        val flow = if (query.isBlank()) {
            diaryEntryDao.getAllEntries()
        } else {
            diaryEntryDao.searchEntries(query, false)
        }
        
        return flow.map { entries ->
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

    override suspend fun toggleFavorite(entryId: String) {
        val entry = diaryEntryDao.getEntryById(entryId)
        entry?.let {
            diaryEntryDao.updateEntry(it.copy(favorite = !it.favorite))
        }
    }
}
