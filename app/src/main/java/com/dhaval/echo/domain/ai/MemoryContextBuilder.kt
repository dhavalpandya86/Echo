package com.dhaval.echo.domain.ai

import kotlinx.coroutines.flow.Flow

interface MemoryContextBuilder {
    fun buildContext(query: String, maxMemories: Int = 5): Flow<String>
    fun buildCitations(query: String, maxCitations: Int = 3): Flow<List<Citation>>
}
