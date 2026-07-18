package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One type's worth of entities, e.g. all People, most-mentioned first. */
data class EntityGroup(val type: String, val label: String, val entities: List<EntityNode>)

data class EntitiesUiState(
    val groups: List<EntityGroup> = emptyList(),
    val isLoading: Boolean = true
)

/** The entity graph made browsable: People, Projects, Topics… (MU-5). */
@HiltViewModel
class EntitiesViewModel @Inject constructor(
    understandingDao: UnderstandingDao,
    authRepository: AuthRepository
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<EntitiesUiState> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) flowOf(EntitiesUiState(isLoading = false))
        else understandingDao.getAllEntities(userId).map { all ->
            val groups = TYPE_ORDER.mapNotNull { (type, label) ->
                all.filter { it.type == type }
                    .sortedByDescending { it.memoryCount }
                    .takeIf { it.isNotEmpty() }
                    ?.let { EntityGroup(type, label, it) }
            }
            EntitiesUiState(groups, isLoading = false)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), EntitiesUiState())

    private companion object {
        val TYPE_ORDER = listOf(
            EntityType.PERSON to "People",
            EntityType.PROJECT to "Projects",
            EntityType.TOPIC to "Topics",
            EntityType.PLACE to "Places",
            EntityType.ORG to "Organizations",
            EntityType.PRODUCT to "Products"
        )
    }
}
