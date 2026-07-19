package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.domain.collections.CollectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CollectionItem(
    val collection: EchoCollection,
    val entryCount: Int
)

data class CollectionsUiState(
    val collections: List<CollectionItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val repository: CollectionRepository,
    private val worldDiscovery: com.dhaval.echo.data.understanding.WorldDiscoveryService
) : ViewModel() {

    /** Worlds Echo discovered by clustering the entity graph (Phase C). */
    val discoveredWorlds: StateFlow<List<com.dhaval.echo.data.understanding.DiscoveredWorld>> =
        kotlinx.coroutines.flow.flow { emit(worldDiscovery.discover()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<CollectionsUiState> = repository.getAllCollections()
        .flatMapLatest { collections ->
            if (collections.isEmpty()) {
                kotlinx.coroutines.flow.flowOf(CollectionsUiState(collections = emptyList()))
            } else {
                val countFlows = collections.map { collection ->
                    repository.getEntryCountForCollection(collection.id).map { count ->
                        CollectionItem(collection, count)
                    }
                }
                combine(countFlows) { items ->
                    CollectionsUiState(collections = items.toList())
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = CollectionsUiState(isLoading = true)
        )

    fun createCollection(name: String, description: String? = null) {
        viewModelScope.launch {
            repository.createCollection(name, description)
        }
    }

    fun deleteCollection(collectionItem: CollectionItem) {
        viewModelScope.launch {
            repository.deleteCollection(collectionItem.collection)
        }
    }

    fun updateCollection(collection: EchoCollection) {
        viewModelScope.launch {
            repository.updateCollection(collection)
        }
    }
}
