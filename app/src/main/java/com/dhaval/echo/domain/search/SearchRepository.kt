package com.dhaval.echo.domain.search

import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow

interface SearchRepository {
    fun search(filter: SearchFilter): Flow<List<SearchResult>>
}

data class SearchResult(
    val entry: TimelineEntry,
    val score: Float,
    val matchedTerms: List<String>
)
