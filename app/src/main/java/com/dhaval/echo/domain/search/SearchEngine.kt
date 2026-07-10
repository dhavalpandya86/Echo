package com.dhaval.echo.domain.search

import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow

/**
 * Strategy interface for executing searches.
 * This allows us to swap between a Standard Database Engine (SQL)
 * and a Semantic Search Engine (AI/Embeddings) in the future.
 */
interface SearchEngine {
    fun search(filter: SearchFilter): Flow<List<TimelineEntry>>
}
