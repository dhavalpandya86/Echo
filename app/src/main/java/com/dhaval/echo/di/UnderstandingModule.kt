package com.dhaval.echo.di

import com.dhaval.echo.data.understanding.KnownEntityAnalyzer
import com.dhaval.echo.data.understanding.LocalEntityResolver
import com.dhaval.echo.data.understanding.LocalMoodAnalyzer
import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalPlaceAnalyzer
import com.dhaval.echo.data.understanding.LocalProjectAnalyzer
import com.dhaval.echo.data.understanding.LocalReminderAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.MlKitPhotoTextExtractor
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.understanding.EntityResolver
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.PhotoTextExtractor
import com.dhaval.echo.domain.understanding.PhotoVisualDescriber
import dagger.Binds
import dagger.Module
import dagger.Provides
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
    @Binds @IntoSet abstract fun placeAnalyzer(impl: LocalPlaceAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun taskAnalyzer(impl: LocalTaskAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun reminderAnalyzer(impl: LocalReminderAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun moodAnalyzer(impl: LocalMoodAnalyzer): MemoryAnalyzer
    @Binds @IntoSet abstract fun knownEntityAnalyzer(impl: KnownEntityAnalyzer): MemoryAnalyzer
    @Binds @IntoSet
    abstract fun activityAnalyzer(
        impl: com.dhaval.echo.data.understanding.LocalActivityAnalyzer
    ): MemoryAnalyzer

    @Binds @IntoSet
    abstract fun objectAnalyzer(
        impl: com.dhaval.echo.data.understanding.LocalObjectAnalyzer
    ): MemoryAnalyzer

    @Binds @IntoSet
    abstract fun topicAnalyzer(
        impl: com.dhaval.echo.data.understanding.LocalTopicAnalyzer
    ): MemoryAnalyzer

    @Binds @Singleton
    abstract fun entityResolver(impl: LocalEntityResolver): EntityResolver

    @Binds @Singleton
    abstract fun extractionEngineProvider(
        impl: com.dhaval.echo.data.understanding.RealExtractionEngineProvider
    ): com.dhaval.echo.domain.understanding.ExtractionEngineProvider

    /**
     * On-device engines register here. Empty until a model pack is installed —
     * declared explicitly so the graph is valid with none of them, which is the
     * state of a fresh install and of any device that never downloads one.
     */
    @dagger.multibindings.Multibinds
    abstract fun onDeviceEngines(): Set<com.dhaval.echo.domain.understanding.ExtractionEngine>

    @Binds @Singleton
    abstract fun photoTextExtractor(impl: MlKitPhotoTextExtractor): PhotoTextExtractor


    companion object {
        /**
         * Rules-based answers for the text questions, keyed by extractor id.
         * The floor those questions fall to when no reasoning model is available.
         */
        @Provides
        @Singleton
        fun textHeuristics(): Map<String, com.dhaval.echo.data.understanding.TextHeuristic> = mapOf(
            "cleanup" to com.dhaval.echo.data.understanding.CleanupHeuristic(),
            "summary" to com.dhaval.echo.data.understanding.SummaryHeuristic(),
            "title" to com.dhaval.echo.data.understanding.TitleHeuristic(),
            "language" to com.dhaval.echo.data.understanding.LanguageHeuristic()
        )

        /**
         * Rules for the interpretive facets. These read earlier answers rather
         * than raw text — "is there a task?" is a far sharper question than
         * "does this sound like a commitment?", and grounding already answered it.
         */
        @Provides
        @Singleton
        fun contextualHeuristics(): Map<String, com.dhaval.echo.data.understanding.ContextualHeuristic> = mapOf(
            "memory_type" to com.dhaval.echo.data.understanding.MemoryTypeHeuristic(),
            "category" to com.dhaval.echo.data.understanding.CategoryHeuristic(),
            "priority" to com.dhaval.echo.data.understanding.PriorityHeuristic()
        )

        /**
         * Same seam for the Photo modality. A thin delegate rather than a direct
         * binding, so switching provider or entering a key takes effect on the
         * next memory instead of the next app launch — and so callers keep
         * depending on the interface, not on which describer is live.
         */
        @Provides
        @Singleton
        fun photoVisualDescriber(aiManager: AIManager): PhotoVisualDescriber =
            object : PhotoVisualDescriber {
                override suspend fun describe(imagePaths: List<String>) =
                    aiManager.getPhotoVisualDescriber().describe(imagePaths)
            }
    }
}
