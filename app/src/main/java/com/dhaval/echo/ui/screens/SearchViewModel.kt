package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.search.SearchFilter
import com.dhaval.echo.domain.search.SearchRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI State for the Search Screen.
 */
data class SearchUiState(
    val filter: SearchFilter = SearchFilter(),
    val results: List<TimelineEntry> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository
) : ViewModel() {

    private val _filter = MutableStateFlow(SearchFilter())
    
    val uiState: StateFlow<SearchUiState> = _filter
        .flatMapLatest { filter ->
            repository.search(filter).map { results ->
                SearchUiState(filter = filter, results = results)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SearchUiState(isSearching = true)
        )

    fun onQueryChanged(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun onToggleFavorites(onlyFavorites: Boolean) {
        _filter.value = _filter.value.copy(onlyFavorites = onlyFavorites)
    }
}
