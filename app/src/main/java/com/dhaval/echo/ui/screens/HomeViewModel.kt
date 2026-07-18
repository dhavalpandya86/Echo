package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.audio.AudioRepository
import com.dhaval.echo.domain.audio.RecordingState
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.timeline.TimelineRepository
import com.dhaval.echo.domain.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * ViewModel for the Home Screen.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val timelineRepository: TimelineRepository,
    private val diaryRepository: DiaryRepository,
    private val userRepository: UserRepository,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val understandingDao: com.dhaval.echo.data.db.UnderstandingDao
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(HomeUiState())

        combine(
            audioRepository.currentRecordingState,
            timelineRepository.getTimelineEntries(),
            userRepository.getUserProfile(),
            intelligenceDao.getAllInsights(userId),
            understandingDao.getOpenActionables(userId)
        ) { recordingState, entries, userProfile, insights, commitments ->
            val sortedEntries = entries.sortedByDescending { it.timestamp }
            val recent = sortedEntries.take(5)

            val today = LocalDate.now()
            val todayCount = entries.count { it.timestamp.toLocalDate() == today }
            val streak = calculateStreak(entries.map { it.timestamp.toLocalDate() }.distinct().sortedDescending())

            // ── Briefing (Phase 1: Today) ──────────────────────────────
            // Yesterday's recap: an honest count, warmly phrased.
            val yesterday = today.minusDays(1)
            val yEntries = sortedEntries.filter { it.timestamp.toLocalDate() == yesterday }
            val yesterdayRecap = if (yEntries.isNotEmpty()) {
                val n = yEntries.size
                "Yesterday you captured $n ${if (n == 1) "memory" else "memories"}."
            } else null

            // Continue: the thing you were last doing.
            val continueMemory = recent.firstOrNull()
            // Worth revisiting: a favorite you didn't just touch, else something
            // from a couple of weeks back — never the same card as "continue".
            val revisitMemory = sortedEntries.firstOrNull {
                it.isFavorite && it.id != continueMemory?.id
            } ?: sortedEntries.lastOrNull {
                it.id != continueMemory?.id &&
                    ChronoUnit.DAYS.between(it.timestamp.toLocalDate(), today) >= 14
            }

            val baseState = when (recordingState) {
                is RecordingState.Idle -> HomeUiState()
                is RecordingState.Recording -> HomeUiState(
                    isRecording = true,
                    isPaused = recordingState.isPaused,
                    durationMillis = recordingState.durationMillis,
                    amplitude = recordingState.amplitude
                )
                is RecordingState.Saving -> HomeUiState(isSaving = true)
                is RecordingState.Error -> HomeUiState(errorMessage = recordingState.message)
            }

            baseState.copy(
                displayName = userProfile?.displayName,
                recentRecordings = recent,
                yesterdayRecap = yesterdayRecap,
                commitments = commitments.take(3),
                continueMemory = continueMemory,
                revisitMemory = revisitMemory,
                stats = HomeStats(
                    todayCount = todayCount,
                    totalCount = entries.size,
                    streakDays = streak
                ),
                insightOfDay = insights.firstOrNull()?.let {
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
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    private fun calculateStreak(dates: List<LocalDate>): Int {
        if (dates.isEmpty()) return 0
        val today = LocalDate.now()
        var currentStreak = 0
        var expectedDate = if (dates.first() == today) today else today.minusDays(1)
        
        if (dates.first() != today && dates.first() != today.minusDays(1)) return 0

        for (date in dates) {
            if (date == expectedDate) {
                currentStreak++
                expectedDate = expectedDate.minusDays(1)
            } else {
                break
            }
        }
        return currentStreak
    }

    fun toggleFavorite(entryId: String) {
        viewModelScope.launch {
            diaryRepository.toggleFavorite(entryId)
        }
    }
}
