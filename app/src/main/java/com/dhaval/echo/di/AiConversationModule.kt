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
}

@Module
@InstallIn(SingletonComponent::class)
object ConversationDaoModule {
    @Provides
    fun provideConversationDao(database: EchoDatabase) = database.conversationDao()
}
