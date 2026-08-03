package com.dhaval.echo.di

import com.dhaval.echo.data.ai.RealConversationRepository
import com.dhaval.echo.data.ai.RealMemoryContextBuilder
import com.dhaval.echo.data.db.EchoDatabase
import com.dhaval.echo.domain.ai.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiConversationModule {

    @Binds
    @Singleton
    abstract fun bindConversationRepository(impl: RealConversationRepository): ConversationRepository

    @Binds
    @Singleton
    abstract fun bindMemoryContextBuilder(impl: RealMemoryContextBuilder): MemoryContextBuilder

    // ── Reflection Engine ────────────────────────────────────────────

    @Binds
    @Singleton
    abstract fun bindReflectionIntentClassifier(
        impl: com.dhaval.echo.data.reflection.KeywordIntentClassifier
    ): com.dhaval.echo.domain.reflection.ReflectionIntentClassifier

    @Binds
    @Singleton
    abstract fun bindReflectionRetriever(
        impl: com.dhaval.echo.data.reflection.GraphReflectionRetriever
    ): com.dhaval.echo.domain.reflection.ReflectionRetriever

    @Binds
    @Singleton
    abstract fun bindReflectionValidator(
        impl: com.dhaval.echo.data.reflection.GroundedReflectionValidator
    ): com.dhaval.echo.domain.reflection.ReflectionValidator

    @Binds
    @Singleton
    abstract fun bindReflectionEngine(
        impl: com.dhaval.echo.data.reflection.RealReflectionEngine
    ): com.dhaval.echo.domain.reflection.ReflectionEngine

    /**
     * Bound directly rather than resolved through AIManager per provider. The
     * engine chooses cloud or local at its final stage, so there is nothing
     * left for a provider switch to decide here — and binding it directly
     * removes the cold-start hazard of capturing a provider that has not
     * finished loading from DataStore.
     */
    @Binds
    @Singleton
    abstract fun bindConversationService(
        impl: com.dhaval.echo.data.ai.RealConversationService
    ): ConversationService
}

@Module
@InstallIn(SingletonComponent::class)
object ConversationDaoModule {
    @Provides
    fun provideConversationDao(database: EchoDatabase) = database.conversationDao()
}
