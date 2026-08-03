package com.dhaval.echo.data.ai

import com.dhaval.echo.domain.ai.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Fake implementations of AI services that return placeholder values.
 */

class FakeLanguageDetectionService : LanguageDetectionService {
    override fun detectLanguage(text: String): Flow<String> = flow {
        delay(500)
        emit("en")
    }
}

class FakeTranscriptionService : TranscriptionService {
    override fun transcribe(audioPath: String): Flow<TranscriptionResult> = flow {
        delay(1000)
        emit(TranscriptionResult(
            text = "This is a fake transcription of the audio.",
            segments = listOf(
                TranscriptionSegment(0, 5000, "This is a fake transcription of the audio.", "en")
            ),
            isFinal = true
        ))
    }
}

class FakeSummaryService : SummaryService {
    override fun summarize(text: String): Flow<String> = flow {
        delay(800)
        emit("Summary: The user talked about various things in a short recording.")
    }
}

class FakeTitleGenerationService : TitleGenerationService {
    override fun generateTitle(text: String): Flow<String> = flow {
        delay(600)
        emit("Memory of the Day")
    }
}

// FakeTagSuggestionService is gone with TagSuggestionService itself. It is the
// origin of the "Personal / Reflection / Voice" tags the backfill worker still
// strips off older memories.

class FakeEmbeddingService : EmbeddingService {
    override fun generateEmbedding(text: String): Flow<List<Float>> = flow {
        delay(400)
        emit(List(128) { 0.1f })
    }
}

class FakeMemoryRelationshipService : MemoryRelationshipService {
    override fun findRelationships(entryId: String): Flow<List<String>> = flow {
        delay(900)
        emit(listOf("Related to entry 123", "Same topic as entry 456"))
    }
}

class FakeSemanticSearchService : SemanticSearchService {
    override fun search(query: String): Flow<List<ScoredResult>> = flow {
        delay(1100)
        emit(listOf(
            ScoredResult("result_1", 0.9f, listOf("fake")),
            ScoredResult("result_2", 0.7f, listOf("test"))
        ))
    }
}

class FakeMemoryClassificationService : MemoryClassificationService {
    override fun classify(text: String, summary: String): Flow<MemoryClassification> = flow {
        delay(600)
        emit(MemoryClassification(
            entryId = "",
            categories = listOf("Personal"),
            keywords = listOf("fake", "test"),
            entities = emptyMap(),
            confidence = 0.5f
        ))
    }
}

class FakeTimelineIntelligenceService : TimelineIntelligenceService {
    override fun analyzeTimeline(): Flow<List<TimelineInsight>> = flow {
        delay(1200)
        emit(emptyList())
    }
    override fun getProjectEvolution(projectName: String): Flow<List<String>> = flow {
        emit(emptyList())
    }
    override fun getTopicTrends(): Flow<Map<String, List<Int>>> = flow {
        emit(emptyMap())
    }
}
