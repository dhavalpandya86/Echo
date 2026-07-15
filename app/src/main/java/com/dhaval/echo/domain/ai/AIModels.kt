package com.dhaval.echo.domain.ai

import kotlinx.coroutines.flow.StateFlow

/**
 * Status of an AI provider.
 */
enum class AIProviderStatus {
    Available,
    Unavailable,
    NeedsLogin,
    NeedsApiKey,
    Offline,
    Unknown
}

/**
 * Status of background intelligence tasks.
 */
enum class IntelligenceStatus {
    PENDING,
    PROCESSING,
    RECORDING,
    TRANSCRIBING,
    SUMMARIZING,
    CLASSIFYING,
    LINKING,
    ANALYZING_TIMELINE,
    COMPLETED,
    FAILED
}

enum class InsightType {
    ACTIVE_PROJECT,
    FREQUENT_TOPIC,
    EMERGING_INTEREST,
    DORMANT_PROJECT,
    RETURNING_IDEA,
    FREQUENT_PERSON,
    FREQUENT_LOCATION,
    ACTIVITY_TREND
}

data class TimelineInsight(
    val id: String,
    val title: String,
    val description: String,
    val type: InsightType,
    val confidence: Float,
    val relatedMemoryIds: List<String>,
    val createdAt: java.time.LocalDateTime,
    val priority: Int
)

/**
 * Metadata for an AI provider.
 */
interface AIProvider {
    val id: String
    val displayName: String
    val supportsOffline: Boolean
    val supportsStreaming: Boolean
    val supportedLanguages: List<String>
    val supportsEmbeddings: Boolean
    val supportsVision: Boolean
    val supportsAudio: Boolean
    val supportsRelationships: Boolean
    val supportsSemanticSearch: Boolean
    val supportsConversation: Boolean
    val status: StateFlow<AIProviderStatus>
}
