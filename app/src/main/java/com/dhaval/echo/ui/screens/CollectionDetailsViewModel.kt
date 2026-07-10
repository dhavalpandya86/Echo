package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.domain.collections.CollectionRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionDetailsUiState(
    val collection: EchoCollection? = null,
    val entries: List<TimelineEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class CollectionDetailsViewModel @Inject constructor(
    private val repository: CollectionRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val collectionId: String = checkNotNull(savedStateHandle["collectionId"])

    val uiState: StateFlow<CollectionDetailsUiState> = combine(
        repository.getCollectionById(collectionId),
        repository.getEntriesForCollection(collectionId)
    ) { collection, entries ->
        CollectionDetailsUiState(
            collection = collection,
            entries = entries,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = CollectionDetailsUiState()
    )

    fun removeEntryFromCollection(entryId: String) {
        viewModelScope.launch {
            repository.removeEntryFromCollection(entryId, collectionId)
        }
    }
}
