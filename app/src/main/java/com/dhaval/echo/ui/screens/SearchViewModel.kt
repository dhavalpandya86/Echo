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
import kotlinx.coroutines.flow.flow
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
    val entityMatches: List<com.dhaval.echo.data.db.EntityNode> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val echoConnection: InferredConnectionView? = null,
    val isSearching: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val authRepository: AuthRepository,
    private val understandingDao: UnderstandingDao
) : ViewModel() {

    private val _filter = MutableStateFlow(SearchFilter())

    private val userIdFlow = authRepository.currentUserId

    private val connectionFlow = userIdFlow.flatMapLatest { uid ->
        if (uid == null) flowOf(null) else understandingDao.recentInferredConnection(uid)
    }

    /** Real "try remembering" suggestions — the user's most-mentioned entities. */
    private val suggestionFlow = userIdFlow.flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList())
        else understandingDao.getAllEntities(uid).map { entities -> entities.take(8).map { it.name } }
    }

    /** Entities (people, places, projects, feelings) matching the query — hybrid recall. */
    private val entityFlow = combine(_filter, userIdFlow) { filter, uid -> filter.query to uid }
        .flatMapLatest { (query, uid) ->
            flow {
                emit(
                    if (uid == null || query.isBlank()) emptyList()
                    else understandingDao.searchEntities(uid, query.trim())
                )
            }
        }

    private val searchFlow = _filter.flatMapLatest { filter ->
        repository.search(filter).map { results -> filter to results }
    }

    val uiState: StateFlow<SearchUiState> = combine(
        searchFlow, entityFlow, suggestionFlow, connectionFlow
    ) { (filter, results), entities, suggestions, connection ->
        SearchUiState(
            filter = filter,
            results = results,
            entityMatches = entities,
            suggestions = if (filter.query.isEmpty()) suggestions else emptyList(),
            echoConnection = connection
        )
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
