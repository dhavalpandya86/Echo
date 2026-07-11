package com.dhaval.echo.data.search

import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.ScoredResult
import com.dhaval.echo.domain.search.SearchFilter
import com.dhaval.echo.domain.search.SearchRepository
import com.dhaval.echo.domain.search.SearchResult
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/**
 * Production implementation of SearchRepository using AI-powered semantic search.
 */
class DatabaseSearchRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val aiManager: AIManager,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : SearchRepository {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun search(filter: SearchFilter): Flow<List<SearchResult>> {
        return authRepository.currentUserId.flatMapLatest { userId: String? ->
            if (userId == null) return@flatMapLatest flowOf(emptyList<SearchResult>())

            val semanticSearchService = aiManager.getSemanticSearchService()
            
            val scoredResultsFlow = if (filter.query.isBlank()) {
                diaryEntryDao.getAllEntries(userId).map { entries ->
                    entries.map { ScoredResult(it.id, 0f) }
                }
            } else {
                semanticSearchService.search(filter.query)
            }

            combine(
                scoredResultsFlow,
                diaryEntryDao.getAllEntries(userId),
                // Using a flow that emits once to get all connections
                mapOf<String, Int>().let { flowOf(it) } // Placeholder for actual connections logic
            ) { scoredResults, allEntries, _ ->
                val entryMap = allEntries.associateBy { it.id }
                
                scoredResults.mapNotNull { result ->
                    entryMap[result.entryId]?.let { entry ->
                        SearchResult(
                            entry = mapToTimelineEntry(entry),
                            score = result.score,
                            matchedTerms = result.matchedTerms
                        )
                    }
                }.filter { !filter.onlyFavorites || it.entry.isFavorite }
            }
        }
    }

    private fun mapToTimelineEntry(entry: com.dhaval.echo.data.db.DiaryEntry): TimelineEntry {
        return TimelineEntry(
            id = entry.id,
            title = entry.title,
            audioPath = entry.audioPath,
            durationMillis = entry.duration,
            timestamp = entry.createdAt,
            transcription = entry.transcript,
            summary = entry.summary,
            transcriptionStatus = entry.transcriptionStatus,
            analysisStatus = entry.analysisStatus,
            isSynced = false,
            isFavorite = entry.favorite
        )
    }
}
