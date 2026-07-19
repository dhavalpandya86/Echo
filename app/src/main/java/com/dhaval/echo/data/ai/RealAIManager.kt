package com.dhaval.echo.data.ai

import android.content.Context
import com.dhaval.echo.data.preferences.AiPreferences
import com.dhaval.echo.data.understanding.ClaudeMemoryAnalyzer
import com.dhaval.echo.domain.ai.*
import com.dhaval.echo.domain.auth.AuthRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealAIManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryEntryDao: com.dhaval.echo.data.db.DiaryEntryDao,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val conversationRepository: ConversationRepository,
    private val memoryContextBuilder: Lazy<MemoryContextBuilder>,
    private val authRepository: AuthRepository,
    private val aiPreferences: AiPreferences,
    private val sttEngine: com.dhaval.echo.domain.transcription.SpeechToTextEngine,
    private val embeddingEngine: com.dhaval.echo.domain.embeddings.EmbeddingEngine,
    private val localMemoryAnalyzers: Set<@JvmSuppressWildcards com.dhaval.echo.domain.understanding.MemoryAnalyzer>
) : AIManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val providers = listOf(
        LocalProvider(),
        ClaudeProvider(),
        OpenAIProvider(),
        GeminiProvider(),
        OllamaProvider(),
        LMStudioProvider(),
        DisabledProvider()
    )

    private val sttProviders = listOf(
        SimpleSTTProvider("android_offline", "Android Offline", true, true, "Ready"),
        SimpleSTTProvider("whisper_cpp", "Whisper.cpp", false, true, "Coming Soon"),
        SimpleSTTProvider("mlkit", "Google ML Kit", false, true, "Coming Soon"),
        SimpleSTTProvider("gemini", "Gemini", false, false, "Coming Soon"),
        SimpleSTTProvider("openai", "OpenAI", false, false, "Coming Soon")
    )

    private val _availableProviders = MutableStateFlow(providers)
    override val availableProviders: StateFlow<List<AIProvider>> = _availableProviders.asStateFlow()

    private val _currentProvider = MutableStateFlow<AIProvider>(providers.first())
    override val currentProvider: StateFlow<AIProvider> = _currentProvider.asStateFlow()

    private val _availableSttProviders = MutableStateFlow(sttProviders)
    override val availableSttProviders: StateFlow<List<STTProvider>> = _availableSttProviders.asStateFlow()

    private val _currentSttProvider = MutableStateFlow<STTProvider>(sttProviders.first())
    override val currentSttProvider: StateFlow<STTProvider> = _currentSttProvider.asStateFlow()

    private var claudeApiKey: String = ""
    private var openAiApiKey: String = ""
    private var geminiApiKey: String = ""

    init {
        scope.launch {
            aiPreferences.selectedProviderId.collect { id ->
                providers.find { it.id == id }?.let { _currentProvider.value = it }
            }
        }
        scope.launch {
            aiPreferences.selectedSttProviderId.collect { id ->
                sttProviders.find { it.id == id }?.let { _currentSttProvider.value = it }
            }
        }
        scope.launch {
            aiPreferences.claudeApiKey.collect { key ->
                claudeApiKey = key
                val claude = providers.find { it.id == "claude" } as? ClaudeProvider
                claude?.updateStatus(
                    if (key.isNotBlank()) AIProviderStatus.Available else AIProviderStatus.NeedsApiKey
                )
            }
        }
        scope.launch { aiPreferences.openAiApiKey.collect { openAiApiKey = it } }
        scope.launch { aiPreferences.geminiApiKey.collect { geminiApiKey = it } }
    }

    override fun switchProvider(providerId: String) {
        providers.find { it.id == providerId }?.let {
            _currentProvider.value = it
            scope.launch { aiPreferences.setSelectedProvider(providerId) }
        }
    }

    override fun switchSttProvider(providerId: String) {
        sttProviders.find { it.id == providerId }?.let {
            if (it.isEnabled) {
                _currentSttProvider.value = it
                scope.launch { aiPreferences.setSelectedSttProvider(providerId) }
            }
        }
    }

    override fun isCapabilitySupported(capability: AICapability): Boolean {
        val p = _currentProvider.value
        return when (capability) {
            AICapability.Offline -> p.supportsOffline
            AICapability.Streaming -> p.supportsStreaming
            AICapability.Embeddings -> p.supportsEmbeddings
            AICapability.Vision -> p.supportsVision
            AICapability.Audio -> p.supportsAudio
            AICapability.Relationships -> p.supportsRelationships
            AICapability.SemanticSearch -> p.supportsSemanticSearch
            AICapability.Conversation -> p.supportsConversation
        }
    }

    override fun getLanguageDetectionService(): LanguageDetectionService = FakeLanguageDetectionService()

    override fun getTranscriptionService(): TranscriptionService =
        object : TranscriptionService {
            override fun transcribe(audioPath: String): Flow<TranscriptionResult> = flow {
                val result = sttEngine.transcribe(audioPath)
                emit(TranscriptionResult(
                    text = result.transcript,
                    isFinal = true
                ))
            }
        }

    override fun getSpeechToTextEngine(): com.dhaval.echo.domain.transcription.SpeechToTextEngine = sttEngine

    override fun getSummaryService(): SummaryService =
        when (_currentProvider.value.id) {
            "local" -> LocalSummarizerService()
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeSummaryService(claudeApiKey) else FakeSummaryService()
            else -> FakeSummaryService()
        }

    override fun getTitleGenerationService(): TitleGenerationService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTitleGenerationService(claudeApiKey) else LocalTitleGenerationService()
            else -> LocalTitleGenerationService()
        }

    override fun getTagSuggestionService(): TagSuggestionService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTagSuggestionService(claudeApiKey) else FakeTagSuggestionService()
            else -> FakeTagSuggestionService()
        }

    override fun getEmbeddingService(): EmbeddingService = object : EmbeddingService {
        override fun generateEmbedding(text: String): Flow<List<Float>> = flow {
            val result = embeddingEngine.generateEmbedding(text)
            emit(result.vector.toList())
        }
    }

    override fun getMemoryRelationshipService(): MemoryRelationshipService = FakeMemoryRelationshipService()

    override fun getMemoryClassificationService(): MemoryClassificationService =
        when (_currentProvider.value.id) {
            "local" -> LocalClassificationService()
            else -> FakeMemoryClassificationService()
        }

    override fun getSemanticSearchService(): SemanticSearchService =
        when (_currentProvider.value.id) {
            "local" -> LocalSemanticSearchService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeSemanticSearchService()
        }

    override fun getTimelineIntelligenceService(): TimelineIntelligenceService =
        when (_currentProvider.value.id) {
            "local" -> LocalTimelineIntelligenceService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeTimelineIntelligenceService()
        }

    override fun getConversationService(): ConversationService =
        when (_currentProvider.value.id) {
            "local" -> RealConversationService(memoryContextBuilder.get(), conversationRepository)
            "claude" -> if (claudeApiKey.isNotBlank()) {
                ClaudeConversationService(claudeApiKey, memoryContextBuilder.get(), conversationRepository)
            } else {
                FakeConversationService()
            }
            else -> FakeConversationService()
        }

    override fun getMemoryAnalyzers(): List<com.dhaval.echo.domain.understanding.MemoryAnalyzer> =
        when (_currentProvider.value.id) {
            // Claude selected with a key → one structured-output call, degrading
            // to the local heuristics on failure (see ClaudeMemoryAnalyzer).
            "claude" -> if (claudeApiKey.isNotBlank()) {
                listOf(ClaudeMemoryAnalyzer(claudeApiKey, localMemoryAnalyzers.toList()))
            } else {
                localMemoryAnalyzers.toList()
            }
            // Local and any not-yet-wired provider → on-device heuristics.
            else -> localMemoryAnalyzers.toList()
        }
}

private data class SimpleSTTProvider(
    override val id: String,
    override val displayName: String,
    override val isEnabled: Boolean,
    override val isOffline: Boolean,
    override val statusLabel: String
) : STTProvider
