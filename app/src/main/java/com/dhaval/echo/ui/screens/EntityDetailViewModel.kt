package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.RelatedEntityView
import com.dhaval.echo.data.db.UnderstandingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

data class EntityDetailUiState(
    val entity: EntityNode? = null,
    val memories: List<DiaryEntry> = emptyList(),
    val related: List<RelatedEntityView> = emptyList(),
    val isLoading: Boolean = true
)

/** One entity and every memory that mentions it (MU-5). */
@HiltViewModel
class EntityDetailViewModel @Inject constructor(
    understandingDao: UnderstandingDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val entityId: String = checkNotNull(savedStateHandle["entityId"])

    val uiState: StateFlow<EntityDetailUiState> = combine(
        understandingDao.observeEntity(entityId),
        understandingDao.getMemoriesForEntity(entityId),
        understandingDao.getRelatedEntities(entityId)
    ) { entity, memories, related ->
        EntityDetailUiState(
            entity = entity,
            memories = memories,
            related = related,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EntityDetailUiState())
}
