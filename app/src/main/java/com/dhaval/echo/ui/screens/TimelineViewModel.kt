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
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val aiManager: com.dhaval.echo.domain.ai.AIManager,
    private val phoneCalendarRepository: com.dhaval.echo.data.calendar.PhoneCalendarRepository
) : ViewModel() {

    /**
     * The user's device-calendar events (Google/Samsung/phone), loaded once
     * permission is granted, shown alongside memories. Empty until then.
     */
    private val _phoneEvents =
        MutableStateFlow<List<com.dhaval.echo.data.calendar.PhoneEvent>>(emptyList())
    val phoneEvents: StateFlow<List<com.dhaval.echo.data.calendar.PhoneEvent>> = _phoneEvents

    fun hasCalendarPermission(): Boolean = phoneCalendarRepository.hasPermission()

    /** Load ~a year either side of today so month/year/day views all have data. */
    fun loadPhoneCalendar() {
        if (!phoneCalendarRepository.hasPermission()) return
        viewModelScope.launch {
            val zone = java.time.ZoneId.systemDefault()
            val start = LocalDate.now().minusMonths(13).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.now().plusMonths(13).atStartOfDay(zone).toInstant().toEpochMilli()
            _phoneEvents.value = phoneCalendarRepository.eventsBetween(start, end)
        }
    }

    private val searchQuery = MutableStateFlow("")

    /**
     * The "story so far": an AI-written running narration of recent memories +
     * mood, shown above the calendar. Null on the free tier (no key) — the header
     * then falls back to a simple count line. Cached in [lastNarrativeKey] and
     * regenerated only when the recent-memory signature changes (a new memory, or
     * a new day), so it updates daily without calling the LLM on every recompose.
     */
    private val _storyNarrative = MutableStateFlow<String?>(null)
    val storyNarrative: StateFlow<String?> = _storyNarrative
    private var lastNarrativeKey: String? = null

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

    // Declared after uiState so the field is initialised before the collector
    // starts (an init block above uiState would see it as null → crash on launch).
    init {
        viewModelScope.launch {
            uiState.collect { maybeGenerateNarrative(it) }
        }
    }

    /** The calendar day the user tapped, for the "add on this day" affordance. */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    fun onDateSelected(date: LocalDate) {
        _selectedDate.value = date
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    private suspend fun maybeGenerateNarrative(state: TimelineUiState) {
        // Only narrate the unfiltered story, and only when there's something to tell.
        if (state.searchQuery.isNotEmpty() || state.days.isEmpty()) return

        val recentDays = state.days.take(RECENT_DAYS)
        val count = recentDays.sumOf { day -> day.parts.sumOf { it.entries.size } }
        val key = "${recentDays.firstOrNull()?.date}:$count"
        if (key == lastNarrativeKey) return
        lastNarrativeKey = key

        val narrator = aiManager.getNarrativeService()
        if (narrator == null) {
            _storyNarrative.value = null // free tier → header shows a simple line
            return
        }

        val block = recentDays.joinToString("\n\n") { day ->
            val titles = day.parts.flatMap { it.entries }
                .joinToString("; ") { it.title.ifBlank { "Untitled" } }
            "• ${day.date}: $titles"
        }
        _storyNarrative.value = narrator.narrate(
            instruction = "Write a short 'story so far' for the top of the user's timeline — 2 " +
                "to 3 sentences narrating what's been happening across these recent days and the " +
                "mood of it, in a warm second-person voice. Ground it only in these memories.",
            memoriesBlock = block,
            maxTokens = 280
        )
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

    private companion object {
        /** How many recent active days the story-so-far narration draws from. */
        const val RECENT_DAYS = 7
    }
}
