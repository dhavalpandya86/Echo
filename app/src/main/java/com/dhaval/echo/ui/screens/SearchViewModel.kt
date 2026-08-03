package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.search.SearchFilter
import com.dhaval.echo.domain.search.SearchRepository
import com.dhaval.echo.domain.search.SearchResult
import com.dhaval.echo.data.db.InferredConnectionView
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.ai.AIManager
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
import kotlinx.coroutines.launch
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

/**
 * The state of Echo's written answer to a Remember query. Idle until the user
 * asks; NeedsKey when no cloud provider is configured (the free tier still shows
 * the ranked memories, just no synthesised paragraph).
 */
sealed interface AiAnswerState {
    data object Idle : AiAnswerState
    data object Loading : AiAnswerState
    data class Ready(val text: String) : AiAnswerState
    data object NeedsKey : AiAnswerState
    data object Failed : AiAnswerState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    private val authRepository: AuthRepository,
    private val understandingDao: UnderstandingDao,
    private val aiManager: AIManager
) : ViewModel() {

    private val _filter = MutableStateFlow(SearchFilter())

    private val _aiAnswer = MutableStateFlow<AiAnswerState>(AiAnswerState.Idle)
    val aiAnswer: StateFlow<AiAnswerState> = _aiAnswer

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
        // A new query invalidates the previous answer.
        _aiAnswer.value = AiAnswerState.Idle
    }

    fun onToggleFavorites(onlyFavorites: Boolean) {
        _filter.value = _filter.value.copy(onlyFavorites = onlyFavorites)
    }

    /** True when a cloud key is set — the screen shows/hides the "ask" affordance accordingly. */
    fun hasCloudKey(): Boolean = aiManager.hasCloudKey()

    /**
     * Synthesises a written answer to the current query from the top-ranked
     * memories, and lists those memories as the sources it used. Key-gated: with
     * no cloud provider, this reports [AiAnswerState.NeedsKey] and the ranked list
     * stands on its own.
     */
    fun askEcho() {
        val query = _filter.value.query.trim()
        if (query.isBlank()) return

        val narrator = aiManager.getNarrativeService()
        if (narrator == null) {
            _aiAnswer.value = AiAnswerState.NeedsKey
            return
        }

        val sources = uiState.value.results.take(8)
        if (sources.isEmpty()) {
            _aiAnswer.value = AiAnswerState.Failed
            return
        }

        viewModelScope.launch {
            _aiAnswer.value = AiAnswerState.Loading
            val block = sources.joinToString("\n\n") { r ->
                val e = r.entry
                val body = (e.summary ?: e.transcription ?: e.textContent ?: "").take(600)
                "• ${e.title.ifBlank { "Untitled" }}: $body"
            }
            val answer = narrator.narrate(
                instruction = "The user is trying to remember: \"$query\". Using only the memories " +
                    "below, write them a short, warm answer (a few sentences) that pulls the " +
                    "relevant threads together. Speak to them directly. If the memories don't " +
                    "really answer it, say so gently rather than guessing.",
                memoriesBlock = block
            )
            _aiAnswer.value =
                if (answer != null) AiAnswerState.Ready(answer) else AiAnswerState.Failed
        }
    }
}
