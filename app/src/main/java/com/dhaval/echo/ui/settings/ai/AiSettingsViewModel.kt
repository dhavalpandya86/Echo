package com.dhaval.echo.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.AIProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class AiSettingsUiState(
    val currentProvider: AIProvider? = null,
    val availableProviders: List<AIProvider> = emptyList(),
    val isLoading: Boolean = false
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiManager: AIManager
) : ViewModel() {

    val uiState: StateFlow<AiSettingsUiState> = combine(
        aiManager.currentProvider,
        aiManager.availableProviders
    ) { current, available ->
        AiSettingsUiState(
            currentProvider = current,
            availableProviders = available
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiSettingsUiState(isLoading = true)
    )

    fun onProviderSelected(providerId: String) {
        aiManager.switchProvider(providerId)
    }
}
