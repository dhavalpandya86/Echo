package com.dhaval.echo.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.preferences.AiPreferences
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.AIProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
    val currentProvider: AIProvider? = null,
    val availableProviders: List<AIProvider> = emptyList(),
    val apiKeyIsSet: Boolean = false,
    val isLoading: Boolean = false,
    val saveSuccess: Boolean = false
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val aiManager: AIManager,
    private val aiPreferences: AiPreferences
) : ViewModel() {

    val uiState: StateFlow<AiSettingsUiState> = combine(
        aiManager.currentProvider,
        aiManager.availableProviders
    ) { current, available ->
        current to available
    }.flatMapLatest { (current, available) ->
        aiPreferences.getApiKeyForProvider(current?.id ?: "").map { key ->
            AiSettingsUiState(
                currentProvider = current,
                availableProviders = available,
                apiKeyIsSet = key.isNotBlank()
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiSettingsUiState(isLoading = true)
    )

    fun onProviderSelected(providerId: String) {
        aiManager.switchProvider(providerId)
    }

    fun saveApiKey(key: String) {
        val providerId = aiManager.currentProvider.value.id
        viewModelScope.launch {
            when (providerId) {
                "claude" -> aiPreferences.setClaudeApiKey(key.trim())
                "openai" -> aiPreferences.setOpenAiApiKey(key.trim())
                "gemini" -> aiPreferences.setGeminiApiKey(key.trim())
            }
        }
    }

    fun clearApiKey() {
        saveApiKey("")
    }
}
