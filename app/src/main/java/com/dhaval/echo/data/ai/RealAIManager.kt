package com.dhaval.echo.data.ai

import android.content.Context
import com.dhaval.echo.domain.ai.*
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of AIManager that manages provider selection and service routing.
 */
@Singleton
class RealAIManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryEntryDao: com.dhaval.echo.data.db.DiaryEntryDao,
    private val intelligenceDao: com.dhaval.echo.data.db.IntelligenceDao,
    private val conversationRepository: ConversationRepository,
    private val memoryContextBuilder: Lazy<MemoryContextBuilder>,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : AIManager {

    private val providers = listOf(
        LocalProvider(),
        GeminiProvider(),
        OpenAIProvider(),
        ClaudeProvider(),
        OllamaProvider(),
        LMStudioProvider(),
        DisabledProvider()
    )

    private val _availableProviders = MutableStateFlow(providers)
    override val availableProviders: StateFlow<List<AIProvider>> = _availableProviders.asStateFlow()

    private val _currentProvider = MutableStateFlow<AIProvider>(providers.first())
    override val currentProvider: StateFlow<AIProvider> = _currentProvider.asStateFlow()

    override fun switchProvider(providerId: String) {
        providers.find { it.id == providerId }?.let {
            _currentProvider.value = it
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

    // Services are currently faked for Sprint AI-01
    override fun getLanguageDetectionService(): LanguageDetectionService = FakeLanguageDetectionService()
    
    override fun getTranscriptionService(): TranscriptionService {
        return when (_currentProvider.value.id) {
            "local" -> MLKitTranscriptionService(context)
            else -> FakeTranscriptionService()
        }
    }

    override fun getSummaryService(): SummaryService {
        return when (_currentProvider.value.id) {
            "local" -> LocalSummarizerService()
            else -> FakeSummaryService()
        }
    }

    override fun getMemoryClassificationService(): MemoryClassificationService {
        return when (_currentProvider.value.id) {
            "local" -> LocalClassificationService()
            else -> FakeMemoryClassificationService()
        }
    }

    override fun getTitleGenerationService(): TitleGenerationService = FakeTitleGenerationService()
    override fun getTagSuggestionService(): TagSuggestionService = FakeTagSuggestionService()
    override fun getEmbeddingService(): EmbeddingService = FakeEmbeddingService()
    override fun getMemoryRelationshipService(): MemoryRelationshipService = FakeMemoryRelationshipService()
    override fun getSemanticSearchService(): SemanticSearchService {
        return when (_currentProvider.value.id) {
            "local" -> LocalSemanticSearchService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeSemanticSearchService()
        }
    }

    override fun getTimelineIntelligenceService(): TimelineIntelligenceService {
        return when (_currentProvider.value.id) {
            "local" -> LocalTimelineIntelligenceService(diaryEntryDao, intelligenceDao, authRepository)
            else -> FakeTimelineIntelligenceService()
        }
    }

    override fun getConversationService(): ConversationService {
        return when (_currentProvider.value.id) {
            "local" -> RealConversationService(memoryContextBuilder.get(), conversationRepository)
            else -> FakeConversationService()
        }
    }
}
