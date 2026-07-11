package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.domain.collections.CollectionRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * UI State for the Entry Details screen.
 */
data class EntryDetailsUiState(
    val entry: TimelineEntry? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val formattedDate: String = "",
    val tags: List<String> = emptyList(),
    val collections: List<EchoCollection> = emptyList(),
    val relatedEntries: List<TimelineEntry> = emptyList()
)

@HiltViewModel
class EntryDetailsViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entryId: String = checkNotNull(savedStateHandle["entryId"])
    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy • HH:mm", Locale.getDefault())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntryDetailsUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(EntryDetailsUiState())

        combine(
            diaryRepository.getEntryById(entryId),
            tagRepository.getTagsForEntry(entryId),
            collectionRepository.getCollectionsForEntry(entryId),
            intelligenceDao.getRelatedEntries(entryId, userId)
        ) { entry, tags, collections, related ->
        if (entry == null) {
            EntryDetailsUiState(isLoading = false, error = "Entry not found")
        } else {
            EntryDetailsUiState(
                entry = TimelineEntry(
                    id = entry.id,
                    title = entry.title,
                    audioPath = entry.audioPath,
                    durationMillis = entry.duration,
                    timestamp = entry.createdAt,
                    transcription = entry.transcript,
                    summary = entry.summary,
                    transcriptionStatus = entry.transcriptionStatus,
                    analysisStatus = entry.analysisStatus,
                    relatedMemoriesCount = related.size,
                    isSynced = false,
                    isFavorite = entry.favorite
                ),
                isLoading = false,
                formattedDate = entry.createdAt.format(dateFormatter),
                tags = tags,
                collections = collections,
                relatedEntries = related.map {
                    TimelineEntry(
                        id = it.id,
                        title = it.title,
                        audioPath = it.audioPath,
                        durationMillis = it.duration,
                        timestamp = it.createdAt,
                        transcription = it.transcript,
                        summary = it.summary,
                        transcriptionStatus = it.transcriptionStatus,
                        analysisStatus = it.analysisStatus,
                        relatedMemoriesCount = 0, // Not needed for related entries list
                        isSynced = false,
                        isFavorite = it.favorite
                    )
                }
            )
        }
    }
}.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5000),
    initialValue = EntryDetailsUiState()
)

    fun updateTitle(newTitle: String) {
        viewModelScope.launch {
            diaryRepository.updateTitle(entryId, newTitle)
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            diaryRepository.toggleFavorite(entryId)
        }
    }

    fun deleteEntry() {
        viewModelScope.launch {
            diaryRepository.deleteEntry(entryId)
        }
    }

    fun addTag(tag: String) {
        viewModelScope.launch {
            tagRepository.addTagToEntry(entryId, tag)
        }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch {
            tagRepository.removeTagFromEntry(entryId, tag)
        }
    }

    fun addToCollection(collectionId: String) {
        viewModelScope.launch {
            collectionRepository.addEntryToCollection(entryId, collectionId)
        }
    }

    fun removeFromCollection(collectionId: String) {
        viewModelScope.launch {
            collectionRepository.removeEntryFromCollection(entryId, collectionId)
        }
    }
}
