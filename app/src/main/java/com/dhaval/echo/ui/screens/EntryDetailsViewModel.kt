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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    val collections: List<EchoCollection> = emptyList()
)

@HiltViewModel
class EntryDetailsViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val tagRepository: TagRepository,
    private val collectionRepository: CollectionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entryId: String = checkNotNull(savedStateHandle["entryId"])
    private val dateFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy • HH:mm", Locale.getDefault())

    val uiState: StateFlow<EntryDetailsUiState> = combine(
        diaryRepository.getEntryById(entryId),
        tagRepository.getTagsForEntry(entryId),
        collectionRepository.getCollectionsForEntry(entryId)
    ) { entry, tags, collections ->
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
                    isSynced = false,
                    isFavorite = entry.favorite
                ),
                isLoading = false,
                formattedDate = entry.createdAt.format(dateFormatter),
                tags = tags,
                collections = collections
            )
        }
    }
    .stateIn(
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
