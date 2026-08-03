package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.RelatedEntityView
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.data.understanding.EntityCorrectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EntityDetailUiState(
    val entity: EntityNode? = null,
    val memories: List<DiaryEntry> = emptyList(),
    val related: List<RelatedEntityView> = emptyList(),
    val isLoading: Boolean = true
)

/** One entity, its memories, its graph neighbours (MU-5 + Phase A), and the corrections loop (Phase B). */
@HiltViewModel
class EntityDetailViewModel @Inject constructor(
    private val understandingDao: UnderstandingDao,
    private val corrections: EntityCorrectionService,
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

    /** Same-type entities the user could merge this one into (loaded on demand). */
    private val _mergeCandidates = MutableStateFlow<List<EntityNode>>(emptyList())
    val mergeCandidates: StateFlow<List<EntityNode>> = _mergeCandidates.asStateFlow()

    fun rename(newName: String) = viewModelScope.launch { corrections.rename(entityId, newName) }

    fun addAlias(alias: String) = viewModelScope.launch { corrections.addAlias(entityId, alias) }

    fun archive() = viewModelScope.launch { corrections.setArchived(entityId, true) }

    fun loadMergeCandidates() = viewModelScope.launch {
        val entity = understandingDao.getEntityById(entityId) ?: return@launch
        _mergeCandidates.value = understandingDao.getMergeCandidates(entity.userId, entity.type, entityId)
    }

    /** Merge this entity into [targetId]; this entity disappears. [onMerged] fires after. */
    fun mergeInto(targetId: String, onMerged: () -> Unit) = viewModelScope.launch {
        corrections.merge(sourceId = entityId, targetId = targetId)
        onMerged()
    }
}
