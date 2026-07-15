package com.dhaval.echo.di

import com.dhaval.echo.data.ai.RealAIManager
import com.dhaval.echo.domain.ai.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiFoundationModule {

    @Binds
    @Singleton
    abstract fun bindAIManager(impl: RealAIManager): AIManager
}

@Module
@InstallIn(SingletonComponent::class)
object AiServiceModule {

    @Provides
    @Singleton
    fun provideLanguageDetectionService(aiManager: AIManager): LanguageDetectionService =
        aiManager.getLanguageDetectionService()

    @Provides
    @Singleton
    fun provideTranscriptionService(aiManager: AIManager): TranscriptionService =
        aiManager.getTranscriptionService()

    @Provides
    @Singleton
    fun provideSummaryService(aiManager: AIManager): SummaryService =
        aiManager.getSummaryService()

    @Provides
    @Singleton
    fun provideTitleGenerationService(aiManager: AIManager): TitleGenerationService =
        aiManager.getTitleGenerationService()

    @Provides
    @Singleton
    fun provideTagSuggestionService(aiManager: AIManager): TagSuggestionService =
        aiManager.getTagSuggestionService()

    @Provides
    @Singleton
    fun provideEmbeddingService(aiManager: AIManager): EmbeddingService =
        aiManager.getEmbeddingService()

    @Provides
    @Singleton
    fun provideMemoryRelationshipService(aiManager: AIManager): MemoryRelationshipService =
        aiManager.getMemoryRelationshipService()

    @Provides
    @Singleton
    fun provideSemanticSearchService(aiManager: AIManager): SemanticSearchService =
        aiManager.getSemanticSearchService()

    @Provides
    @Singleton
    fun provideTimelineIntelligenceService(aiManager: AIManager): TimelineIntelligenceService =
        aiManager.getTimelineIntelligenceService()

    @Provides
    @Singleton
    fun provideConversationService(aiManager: AIManager): ConversationService =
        aiManager.getConversationService()
}
