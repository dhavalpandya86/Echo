package com.dhaval.echo.domain.embeddings

/**
 * Interface for generating semantic embeddings from text.
 */
interface EmbeddingEngine {

    /**
     * Generates an embedding vector for the given text.
     * 
     * Implementations should handle model-specific prefixes 
     * (e.g., "query: " or "passage: ") internally.
     */
    suspend fun generateEmbedding(
        text: String,
        isQuery: Boolean = false
    ): EmbeddingResult
}
