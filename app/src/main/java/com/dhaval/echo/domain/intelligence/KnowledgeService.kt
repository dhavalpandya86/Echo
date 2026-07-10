package com.dhaval.echo.domain.intelligence

/**
 * Interface for semantic representation and discovery.
 */
interface KnowledgeService {
    /**
     * Generates a vector embedding for the given text.
     */
    suspend fun getEmbedding(text: String): FloatArray

    /**
     * Finds related entries based on semantic similarity.
     */
    suspend fun findRelatedEntries(entryId: String, limit: Int = 3): List<String>
}
