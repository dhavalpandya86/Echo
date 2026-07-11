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
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao
) : ViewModel() {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HomeUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(HomeUiState())

        combine(
            audioRepository.currentRecordingState,
            timelineRepository.getTimelineEntries(),
            userRepository.getUserProfile(),
            intelligenceDao.getAllInsights(userId)
        ) { recordingState, entries, userProfile, insights ->
            val sortedEntries = entries.sortedByDescending { it.timestamp }
            val recent = sortedEntries.take(5)
            
            val today = LocalDate.now()
            val todayCount = entries.count { it.timestamp.toLocalDate() == today }
            
            // Calculate streak
            val streak = calculateStreak(entries.map { it.timestamp.toLocalDate() }.distinct().sortedDescending())

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
