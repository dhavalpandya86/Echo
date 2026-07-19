package com.dhaval.echo.ui.screens

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.understanding.WorldDetail
import com.dhaval.echo.data.understanding.WorldDiscoveryService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorldDetailUiState(
    val world: WorldDetail? = null,
    val isLoading: Boolean = true
)

/** One discovered World: its entities and the memories they span (Phase C). */
@HiltViewModel
class WorldDetailViewModel @Inject constructor(
    private val worldDiscovery: WorldDiscoveryService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val seedEntityId: String = checkNotNull(savedStateHandle["seedEntityId"])

    private val _uiState = MutableStateFlow(WorldDetailUiState())
    val uiState: StateFlow<WorldDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val world = worldDiscovery.worldDetail(seedEntityId)
            _uiState.value = WorldDetailUiState(world = world, isLoading = false)
        }
    }
}
