package com.dhaval.echo.domain.ai

import kotlinx.coroutines.flow.Flow

data class TranscriptionResult(
    val text: String,
    val segments: List<TranscriptionSegment> = emptyList(),
    val isFinal: Boolean = false
)

data class TranscriptionSegment(
    val startTime: Long,
    val endTime: Long,
    val text: String,
    val languageCode: String
)

/**
 * Service for detecting language from text or audio.
 */
interface LanguageDetectionService {
    fun detectLanguage(text: String): Flow<String>
}

/**
 * Service for transcribing audio to text.
 */
interface TranscriptionService {
    fun transcribe(audioPath: String): Flow<TranscriptionResult>
}

/**
 * Service for generating summaries.
 */
interface SummaryService {
    fun summarize(text: String): Flow<String>
}

/**
 * Service for generating titles for recordings.
 */
interface TitleGenerationService {
    fun generateTitle(text: String): Flow<String>
}

/**
 * Service for suggesting tags for recordings.
 */
interface TagSuggestionService {
    fun suggestTags(text: String): Flow<List<String>>
}

/**
 * Service for generating vector embeddings.
 */
interface EmbeddingService {
    fun generateEmbedding(text: String): Flow<List<Float>>
}

/**
 * Service for identifying relationships between memories.
 */
interface MemoryRelationshipService {
    fun findRelationships(entryId: String): Flow<List<String>>
}

data class ScoredResult(
    val entryId: String,
    val score: Float,
    val matchedTerms: List<String> = emptyList()
)

/**
 * Service for performing semantic search.
 */
interface SemanticSearchService {
    fun search(query: String): Flow<List<ScoredResult>>
}

data class MemoryClassification(
    val entryId: String,
    val categories: List<String>,
    val keywords: List<String>,
    val entities: Map<String, List<String>>, // Type to list of values
    val confidence: Float
)

/**
 * Service for classifying memories.
 */
interface MemoryClassificationService {
    fun classify(text: String, summary: String): Flow<MemoryClassification>
}

data class MemoryRelationship(
    val fromEntryId: String,
    val toEntryId: String,
    val similarity: Float,
    val type: RelationshipType,
    val reason: String? = null
)

enum class RelationshipType {
    RELATED,
    CONTINUES,
    FOLLOW_UP,
    SAME_PROJECT,
    SAME_PERSON,
    SAME_PLACE
}

/**
 * Service for analyzing timeline trends and generating insights.
 */
interface TimelineIntelligenceService {
    fun analyzeTimeline(): Flow<List<TimelineInsight>>
    fun getProjectEvolution(projectName: String): Flow<List<String>> // Returns entry IDs
    fun getTopicTrends(): Flow<Map<String, List<Int>>> // Topic to weekly frequency
}

/**
 * Service for conversational memory and assistant interactions.
 */
interface ConversationService {
    fun ask(
        question: String,
        conversationId: String? = null,
        contextOverride: ConversationContext? = null
    ): Flow<Message>

    fun getSuggestedQuestions(): Flow<List<String>>
}

/**
 * The shared LLM engine behind the narrative screens (Remember answer, Reflect
 * analysis, Story narration). Implemented in the data layer over whichever cloud
 * provider has a key; null out of [AIManager] when there is none.
 */
interface NarrativeService {
    /**
     * Produce a paragraph in Echo's voice for [instruction], grounded in
     * [memoriesBlock] (already-formatted memory text). Returns null on failure so
     * the caller can fall back to on-device output.
     */
    suspend fun narrate(instruction: String, memoriesBlock: String, maxTokens: Int = 600): String?
}
