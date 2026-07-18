package com.dhaval.echo.domain.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Manager for handling AI providers and services.
 */
interface AIManager {
    val currentProvider: StateFlow<AIProvider>
    val availableProviders: StateFlow<List<AIProvider>>

    val currentSttProvider: StateFlow<STTProvider>
    val availableSttProviders: StateFlow<List<STTProvider>>

    fun switchProvider(providerId: String)
    fun switchSttProvider(providerId: String)
    fun isCapabilitySupported(capability: AICapability): Boolean
    
    // Service Accessors
    fun getLanguageDetectionService(): LanguageDetectionService
    fun getTranscriptionService(): TranscriptionService
    fun getSpeechToTextEngine(): com.dhaval.echo.domain.transcription.SpeechToTextEngine
    fun getSummaryService(): SummaryService
    fun getTitleGenerationService(): TitleGenerationService
    fun getTagSuggestionService(): TagSuggestionService
    fun getEmbeddingService(): EmbeddingService
    fun getMemoryRelationshipService(): MemoryRelationshipService
    fun getSemanticSearchService(): SemanticSearchService
    fun getMemoryClassificationService(): MemoryClassificationService
    fun getTimelineIntelligenceService(): TimelineIntelligenceService
    fun getConversationService(): ConversationService
}

enum class AICapability {
    Offline,
    Streaming,
    Embeddings,
    Vision,
    Audio,
    Relationships,
    SemanticSearch,
    Conversation
}
