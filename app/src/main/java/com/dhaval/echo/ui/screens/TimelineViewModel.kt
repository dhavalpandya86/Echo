package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.timeline.TimelineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Part of the day a memory belongs to, for the narrative timeline. */
enum class DayPart(val label: String) { MORNING("Morning"), AFTERNOON("Afternoon"), EVENING("Evening") }

data class StoryPart(val part: DayPart, val entries: List<TimelineEntry>)
data class StoryDay(val date: LocalDate, val parts: List<StoryPart>)

/**
 * UI State for the Story (timeline) Screen.
 */
data class TimelineUiState(
    val days: List<StoryDay> = emptyList(),
    val memoryCount: Int = 0,
    val monthLabel: String = "",
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val insights: List<com.dhaval.echo.domain.ai.TimelineInsight> = emptyList()
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: TimelineRepository,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TimelineUiState> = combine(
        searchQuery,
        authRepository.currentUserId
    ) { query, userId -> query to userId }
        .flatMapLatest { (query, userId) ->
            if (userId == null) return@flatMapLatest flowOf(TimelineUiState())
            
            combine(
                repository.getTimelineEntries(query),
                intelligenceDao.getAllInsights(userId)
            ) { entries, insights ->
                TimelineUiState(
                    days = groupIntoDays(entries),
                    memoryCount = entries.size,
                    monthLabel = (entries.maxByOrNull { it.timestamp }?.timestamp?.toLocalDate() ?: LocalDate.now())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
                    searchQuery = query,
                    isLoading = false,
                    insights = insights.map {
                        com.dhaval.echo.domain.ai.TimelineInsight(
                            id = it.id,
                            title = it.title,
                            description = it.description,
                            type = it.type,
                            confidence = it.confidence,
                            relatedMemoryIds = it.relatedMemoryIds,
                            createdAt = it.createdAt,
                            priority = it.priority
                        )
                    }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = TimelineUiState(isLoading = true)
        )

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun toggleFavorite(entryId: String) {
        viewModelScope.launch {
            repository.toggleFavorite(entryId)
        }
    }

    /** Groups memories into days (newest first), each split Morning→Afternoon→Evening. */
    private fun groupIntoDays(entries: List<TimelineEntry>): List<StoryDay> =
        entries.groupBy { it.timestamp.toLocalDate() }
            .toSortedMap(reverseOrder())
            .map { (date, dayEntries) ->
                val parts = dayEntries.groupBy { partOf(it.timestamp.hour) }
                    .toSortedMap(compareBy { it.ordinal })
                    .map { (part, es) -> StoryPart(part, es.sortedBy { it.timestamp }) }
                StoryDay(date, parts)
            }

    private fun partOf(hour: Int): DayPart = when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..16 -> DayPart.AFTERNOON
        else -> DayPart.EVENING
    }
}
