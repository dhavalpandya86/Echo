package com.dhaval.echo.data.timeline

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.timeline.TimelineRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Production implementation of TimelineRepository using Room Database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseTimelineRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val authRepository: AuthRepository
) : TimelineRepository {
    override fun getTimelineEntries(query: String): Flow<List<TimelineEntry>> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(emptyList())

            val flow = if (query.isBlank()) {
                diaryEntryDao.getAllEntries(userId)
            } else {
                diaryEntryDao.searchEntries(userId, query, false)
            }
            
            flow.map { entries ->
                entries.map { entry ->
                    TimelineEntry(
                        id = entry.id,
                        title = entry.title,
                        audioPath = entry.audioPath,
                        durationMillis = entry.duration,
                        timestamp = entry.createdAt,
                        transcription = entry.transcript,
                        summary = entry.summary,
                        transcriptionStatus = entry.transcriptionStatus,
                        analysisStatus = entry.analysisStatus,
                        relatedMemoriesCount = 0, // Should be fetched if needed
                        isSynced = false,
                        isFavorite = entry.favorite
                    )
                }
            }
        }
    }

    override suspend fun toggleFavorite(entryId: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(entryId)
        if (entry != null && entry.userId == userId) {
            diaryEntryDao.updateEntry(entry.copy(favorite = !entry.favorite))
        }
    }
}
