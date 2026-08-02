package com.dhaval.echo.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.data.preferences.AppearanceMode
import com.dhaval.echo.data.preferences.AppearancePreferences
import com.dhaval.echo.data.user.StorageBreakdown
import com.dhaval.echo.data.user.StorageReporter
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.BrainStatus
import com.dhaval.echo.domain.understanding.Capability
import com.dhaval.echo.domain.understanding.ExtractionEngineProvider
import com.dhaval.echo.domain.understanding.ExtractorRegistry
import com.dhaval.echo.domain.user.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * How far along Echo is at understanding what it has been given.
 *
 * Derived entirely from extraction run rows, so it says something true rather
 * than reassuring: [awaiting] is the count of memories that still have questions
 * outstanding, and [lastAnalyzedAt] is when work last actually completed.
 */
data class MemoryStatus(
    val awaiting: Int = 0,
    val lastAnalyzedAt: LocalDateTime? = null
) {
    val isUpToDate: Boolean get() = awaiting == 0

    /** "5 minutes ago", "yesterday" — null when nothing has ever been analyzed. */
    val lastAnalyzedLabel: String?
        get() {
            val at = lastAnalyzedAt ?: return null
            val minutes = Duration.between(at, LocalDateTime.now()).toMinutes()
            return when {
                minutes < 1 -> "just now"
                minutes < 60 -> "$minutes ${plural(minutes, "minute")} ago"
                minutes < 1440 -> (minutes / 60).let { "$it ${plural(it, "hour")} ago" }
                minutes < 2880 -> "yesterday"
                else -> (minutes / 1440).let { "$it days ago" }
            }
        }

    private fun plural(n: Long, word: String) = if (n == 1L) word else "${word}s"
}

data class SettingsUiState(
    val displayName: String? = null,
    val memoryStatus: MemoryStatus = MemoryStatus(),
    val appearance: AppearanceMode = AppearanceMode.SYSTEM,
    val brains: List<BrainStatus> = emptyList(),
    val storage: StorageBreakdown? = null,
    val appVersion: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository,
    private val appearancePreferences: AppearancePreferences,
    private val understandingDao: UnderstandingDao,
    private val storageReporter: StorageReporter,
    private val aiManager: AIManager,
    private val engines: ExtractionEngineProvider,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context
) : ViewModel() {

    private val storage = MutableStateFlow<StorageBreakdown?>(null)
    private val brains = MutableStateFlow<List<BrainStatus>>(emptyList())

    init {
        refreshStorage()
        refreshBrains()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val memoryStatus: StateFlow<MemoryStatus> =
        authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) flowOf(MemoryStatus())
            else combine(
                understandingDao.countMemoriesAwaitingUnderstanding(userId, ExtractorRegistry.size),
                understandingDao.lastAnalyzedAt(userId)
            ) { awaiting, lastAt -> MemoryStatus(awaiting, lastAt) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MemoryStatus())

    val uiState: StateFlow<SettingsUiState> = combine(
        userRepository.getUserProfile().map { it?.displayName },
        memoryStatus,
        appearancePreferences.mode,
        brains,
        storage
    ) { name, status, appearance, brainList, storageBreakdown ->
        SettingsUiState(
            displayName = name,
            memoryStatus = status,
            appearance = appearance,
            brains = brainList,
            storage = storageBreakdown,
            appVersion = versionName()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    fun setAppearance(mode: AppearanceMode) {
        viewModelScope.launch { appearancePreferences.setMode(mode) }
    }

    fun updateDisplayName(name: String) {
        viewModelScope.launch { userRepository.updateDisplayName(name) }
    }

    fun logout() {
        viewModelScope.launch { authRepository.logout() }
    }

    /** Recomputed on demand — measuring the filesystem is not free. */
    fun refreshStorage() {
        viewModelScope.launch { storage.value = storageReporter.measure() }
    }

    /**
     * Ask the capability layer what is actually answering each faculty.
     *
     * Resolved fresh rather than cached so entering a key or installing a model
     * shows up the next time the page is opened, without any UI change.
     */
    fun refreshBrains() {
        viewModelScope.launch {
            val reasoning = engines.engineFor(Capability.REASONING)
            val providerName = aiManager.currentProvider.value.displayName
            val hasCloud = aiManager.hasCloudKey()

            brains.value = listOf(
                BrainStatus(
                    role = "Memory Brain",
                    // "Echo Core" rather than "on-device rules": the user is
                    // asking whether Echo is intelligent, and the honest answer
                    // is yes, at a level that can be raised — not a fallback.
                    engineName = when {
                        reasoning == null -> "Echo Core"
                        reasoning.engineId.startsWith("cloud:") -> providerName
                        else -> reasoning.engineId
                    },
                    location = when {
                        reasoning == null -> "Built into Echo"
                        reasoning.engineId.startsWith("cloud:") -> "Cloud"
                        else -> "On this device"
                    },
                    detail = if (reasoning == null) {
                        "Understands your memories on its own. Add an AI model for deeper reading."
                    } else null,
                    isOffline = reasoning == null || !reasoning.engineId.startsWith("cloud:")
                ),
                BrainStatus(
                    role = "Voice Brain",
                    engineName = "Whisper",
                    location = "On this device",
                    detail = "Turns your recordings into words, without leaving your phone.",
                    isOffline = true
                ),
                BrainStatus(
                    role = "Conversation Brain",
                    engineName = if (hasCloud) providerName else "Echo Core",
                    location = if (hasCloud) "Cloud" else "Built into Echo",
                    detail = if (hasCloud) null
                    else "Answers from your own memories. Connect a service for richer replies.",
                    isOffline = !hasCloud
                )
            )
        }
    }

    private fun versionName(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
