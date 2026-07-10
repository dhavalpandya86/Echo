package com.dhaval.echo.domain.intelligence

import kotlinx.coroutines.flow.Flow

interface IntelligenceRepository {
    /**
     * Enqueues an intelligence task for the given entry.
     */
    fun processEntry(entryId: String)

    /**
     * Returns the related entries for a given entry.
     */
    fun getRelatedEntries(entryId: String): Flow<List<com.dhaval.echo.domain.timeline.TimelineEntry>>
}
