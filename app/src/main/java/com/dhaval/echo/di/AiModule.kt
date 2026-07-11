package com.dhaval.echo.di

import com.dhaval.echo.data.intelligence.MockIntelligenceService
import com.dhaval.echo.data.intelligence.RealIntelligenceRepository
import com.dhaval.echo.domain.intelligence.IntelligenceRepository
import com.dhaval.echo.domain.intelligence.IntelligenceService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindIntelligenceService(
        mockIntelligenceService: MockIntelligenceService
    ): IntelligenceService

    @Binds
    @Singleton
    abstract fun bindIntelligenceRepository(
        realIntelligenceRepository: RealIntelligenceRepository
    ): IntelligenceRepository
}
