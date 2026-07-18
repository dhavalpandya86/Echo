package com.dhaval.echo.di

import com.dhaval.echo.data.understanding.KnownEntityAnalyzer
import com.dhaval.echo.data.understanding.LocalEntityResolver
import com.dhaval.echo.data.understanding.LocalMoodAnalyzer
import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalProjectAnalyzer
import com.dhaval.echo.data.understanding.LocalReminderAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.RealMemoryUnderstandingService
import com.dhaval.echo.domain.understanding.EntityResolver
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.MemoryUnderstandingService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

/**
 * Memory Understanding Engine wiring (MU-0).
 *
 * Analyzers are a Hilt multibinding set — adding a specialist is one extra
 * @Binds line, nothing else changes. MU-1 swaps the heuristic analyzers for
 * the Claude suite behind the same set when an API key is present.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UnderstandingModule {

    @Binds @IntoSet abstract fun personAnalyzer(impl: LocalPersonAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun projectAnalyzer(impl: LocalProjectAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun taskAnalyzer(impl: LocalTaskAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun reminderAnalyzer(impl: LocalReminderAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun moodAnalyzer(impl: LocalMoodAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun knownEntityAnalyzer(impl: KnownEntityAnalyzer): MemoryAnalyzer

    @Binds @Singleton
    abstract fun entityResolver(impl: LocalEntityResolver): EntityResolver

    @Binds @Singleton
    abstract fun understandingService(impl: RealMemoryUnderstandingService): MemoryUnderstandingService
}
