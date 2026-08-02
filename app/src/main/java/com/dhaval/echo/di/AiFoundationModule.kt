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
    fun provideLanguageDetectionService(aiManager: AIManager): LanguageDetectionService =
        aiManager.getLanguageDetectionService()

    @Provides
    fun provideTranscriptionService(aiManager: AIManager): TranscriptionService =
        aiManager.getTranscriptionService()

    @Provides
    fun provideSummaryService(aiManager: AIManager): SummaryService =
        aiManager.getSummaryService()

    @Provides
    fun provideTitleGenerationService(aiManager: AIManager): TitleGenerationService =
        aiManager.getTitleGenerationService()

    @Provides
    fun provideEmbeddingService(aiManager: AIManager): EmbeddingService =
        aiManager.getEmbeddingService()

    @Provides
    fun provideMemoryRelationshipService(aiManager: AIManager): MemoryRelationshipService =
        aiManager.getMemoryRelationshipService()

    @Provides
    fun provideSemanticSearchService(aiManager: AIManager): SemanticSearchService =
        aiManager.getSemanticSearchService()

    @Provides
    fun provideTimelineIntelligenceService(aiManager: AIManager): TimelineIntelligenceService =
        aiManager.getTimelineIntelligenceService()

    @Provides
    fun provideConversationService(aiManager: AIManager): ConversationService =
        aiManager.getConversationService()
}
