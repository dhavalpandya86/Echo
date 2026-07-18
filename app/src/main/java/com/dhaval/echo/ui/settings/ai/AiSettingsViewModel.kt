package com.dhaval.echo.ui.settings.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.preferences.AiPreferences
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.AIProvider
import com.dhaval.echo.domain.ai.STTProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiSettingsUiState(
    val currentProvider: AIProvider? = null,
    val availableProviders: List<AIProvider> = emptyList(),
    val currentSttProvider: STTProvider? = null,
    val availableSttProviders: List<STTProvider> = emptyList(),
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
        aiManager.availableProviders,
        aiManager.currentSttProvider,
        aiManager.availableSttProviders
    ) { current, available, currentStt, availableStt ->
        Data(current, available, currentStt, availableStt)
    }.flatMapLatest { data ->
        aiPreferences.getApiKeyForProvider(data.current?.id ?: "").map { key ->
            AiSettingsUiState(
                currentProvider = data.current,
                availableProviders = data.available,
                currentSttProvider = data.currentStt,
                availableSttProviders = data.availableStt,
                apiKeyIsSet = key.isNotBlank()
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiSettingsUiState(isLoading = true)
    )

    private data class Data(
        val current: AIProvider?,
        val available: List<AIProvider>,
        val currentStt: STTProvider?,
        val availableStt: List<STTProvider>
    )

    fun onProviderSelected(providerId: String) {
        aiManager.switchProvider(providerId)
    }

    fun onSttProviderSelected(providerId: String) {
        aiManager.switchSttProvider(providerId)
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
