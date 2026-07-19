package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.search.SearchFilter
import com.dhaval.echo.domain.search.SearchRepository
import com.dhaval.echo.domain.search.SearchResult
import com.dhaval.echo.data.db.InferredConnectionView
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI State for the Search Screen.
 */
data class SearchUiState(
    val filter: SearchFilter = SearchFilter(),
    val results: List<SearchResult> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val echoConnection: InferredConnectionView? = null,
    val isSearching: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    authRepository: AuthRepository,
    understandingDao: UnderstandingDao
) : ViewModel() {

    private val _filter = MutableStateFlow(SearchFilter())

    private val connectionFlow = authRepository.currentUserId.flatMapLatest { uid ->
        if (uid == null) flowOf(null) else understandingDao.recentInferredConnection(uid)
    }

    private val searchFlow = _filter.flatMapLatest { filter ->
        repository.search(filter).map { results ->
            SearchUiState(
                filter = filter,
                results = results,
                suggestions = if (filter.query.isEmpty())
                    listOf("Oceanis", "Branding", "Dubai", "SEO", "Family", "Health", "Ideas") else emptyList()
            )
        }
    }

    val uiState: StateFlow<SearchUiState> = combine(searchFlow, connectionFlow) { s, connection ->
        s.copy(echoConnection = connection)
    }.stateIn(
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
