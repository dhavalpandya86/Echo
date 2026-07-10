package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.timeline.TimelineEntry
import com.dhaval.echo.domain.timeline.TimelineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Groups for the timeline.
 */
enum class TimelineGroup(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    LAST_WEEK("Last Week"),
    EARLIER("Earlier")
}

/**
 * UI State for the Timeline Screen.
 */
data class TimelineUiState(
    val groupedEntries: Map<TimelineGroup, List<TimelineEntry>> = emptyMap(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val repository: TimelineRepository
) : ViewModel() {

    private val searchQuery = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TimelineUiState> = searchQuery
        .flatMapLatest { query ->
            repository.getTimelineEntries(query).map { entries ->
                TimelineUiState(
                    groupedEntries = groupEntries(entries),
                    searchQuery = query,
                    isLoading = false
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

    private fun groupEntries(entries: List<TimelineEntry>): Map<TimelineGroup, List<TimelineEntry>> {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val startOfThisWeek = today.minusDays(today.dayOfWeek.value.toLong() - 1)
        val startOfLastWeek = startOfThisWeek.minusWeeks(1)

        return entries.sortedByDescending { it.timestamp }
            .groupBy { entry ->
                val entryDate = entry.timestamp.toLocalDate()
                when {
                    entryDate == today -> TimelineGroup.TODAY
                    entryDate == yesterday -> TimelineGroup.YESTERDAY
                    !entryDate.isBefore(startOfThisWeek) -> TimelineGroup.THIS_WEEK
                    !entryDate.isBefore(startOfLastWeek) -> TimelineGroup.LAST_WEEK
                    else -> TimelineGroup.EARLIER
                }
            }
    }
}
