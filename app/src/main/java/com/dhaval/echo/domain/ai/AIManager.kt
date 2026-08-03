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

    /**
     * True when a cloud provider is selected *and* has a key — i.e. the LLM
     * narrative features are available. Screens read this to show or hide the
     * "add a key to unlock" affordance.
     */
    fun hasCloudKey(): Boolean

    /**
     * The narrative engine behind Remember/Reflect/Story, or null on the free
     * tier (no key) so callers fall back to their on-device output.
     */
    fun getNarrativeService(): NarrativeService?

    /**
     * A raw cloud completion for structured extraction, or null on the free tier.
     *
     * Separate from [getNarrativeService] because the two want opposite things:
     * narration folds in Echo's persona ("speak warmly, like a thoughtful
     * friend"), which actively fights a prompt whose entire contract is "return
     * only this JSON object". Extraction gets a neutral instruction instead.
     */
    fun getExtractionCompleter(): TextCompleter?
    
    // Service Accessors
    fun getLanguageDetectionService(): LanguageDetectionService
    fun getTranscriptionService(): TranscriptionService
    fun getSpeechToTextEngine(): com.dhaval.echo.domain.transcription.SpeechToTextEngine
    fun getSummaryService(): SummaryService
    fun getTitleGenerationService(): TitleGenerationService
    fun getEmbeddingService(): EmbeddingService
    fun getMemoryRelationshipService(): MemoryRelationshipService
    fun getSemanticSearchService(): SemanticSearchService
    fun getMemoryClassificationService(): MemoryClassificationService
    fun getTimelineIntelligenceService(): TimelineIntelligenceService

    /**
     * The Memory Understanding analyzers to run for a memory (MU-1). Routes
     * Claude ⇄ on-device heuristics like the other service accessors: the
     * Claude suite when its provider is selected with a key, the local
     * heuristics otherwise.
     */
    fun getMemoryAnalyzers(): List<com.dhaval.echo.domain.understanding.MemoryAnalyzer>

    /**
     * Who describes a memory's photos. Cloud vision only when the user has
     * supplied a key for it; on-device labelling otherwise.
     */
    fun getPhotoVisualDescriber(): com.dhaval.echo.domain.understanding.PhotoVisualDescriber
}

/**
 * A bare text completion: prompt in, text out. The narrowest possible seam over
 * "some model answered this", so callers that build their own full prompt (the
 * extraction pipeline) do not inherit anyone else's system instruction.
 */
fun interface TextCompleter {
    suspend fun complete(prompt: String, maxTokens: Int): String
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
