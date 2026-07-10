package com.dhaval.echo.domain.intelligence

/**
 * Result of content analysis.
 */
data class ContentAnalysis(
    val title: String,
    val tags: List<String>,
    val summary: String? = null
)

/**
 * Interface for understanding transcribed text.
 * Generates metadata like titles and tags.
 */
interface IntelligenceService {
    suspend fun analyzeContent(transcript: String): ContentAnalysis
}
