package com.dhaval.echo.data.ai

import android.content.Context
import com.dhaval.echo.data.preferences.AiPreferences
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
    private val aiPreferences: AiPreferences
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

    private val _availableProviders = MutableStateFlow(providers)
    override val availableProviders: StateFlow<List<AIProvider>> = _availableProviders.asStateFlow()

    private val _currentProvider = MutableStateFlow<AIProvider>(providers.first())
    override val currentProvider: StateFlow<AIProvider> = _currentProvider.asStateFlow()

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
        when (_currentProvider.value.id) {
            "local" -> MLKitTranscriptionService(context)
            else -> FakeTranscriptionService()
        }

    override fun getSummaryService(): SummaryService =
        when (_currentProvider.value.id) {
            "local" -> LocalSummarizerService()
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeSummaryService(claudeApiKey) else FakeSummaryService()
            else -> FakeSummaryService()
        }

    override fun getTitleGenerationService(): TitleGenerationService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTitleGenerationService(claudeApiKey) else FakeTitleGenerationService()
            else -> FakeTitleGenerationService()
        }

    override fun getTagSuggestionService(): TagSuggestionService =
        when (_currentProvider.value.id) {
            "claude" -> if (claudeApiKey.isNotBlank()) ClaudeTagSuggestionService(claudeApiKey) else FakeTagSuggestionService()
            else -> FakeTagSuggestionService()
        }

    override fun getEmbeddingService(): EmbeddingService = FakeEmbeddingService()

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
}
