package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import com.dhaval.echo.data.db.*
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.understanding.MemoryUnderstandingService
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last
import java.io.File

/**
 * Runs the intelligence pipeline for a single memory.
 *
 * A memory may be voice, text, photos, or any mix of them, so the pipeline is
 * split in two: resolve the memory's *source text* (transcribing audio only when
 * there is audio), then analyse that text. A memory with no source text at all
 * (e.g. photos only) is a valid, fully-processed memory — not a failure.
 */
@HiltWorker
class MemoryIntelligenceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val aiManager: AIManager,
    private val tagRepository: TagRepository,
    private val understandingService: MemoryUnderstandingService,
    private val photoTextExtractor: com.dhaval.echo.domain.understanding.PhotoTextExtractor,
    private val photoVisualDescriber: com.dhaval.echo.domain.understanding.PhotoVisualDescriber
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: run {
            Log.e(TAG, "No entryId in input data")
            return Result.failure()
        }
        val entry = diaryEntryDao.getEntryById(entryId) ?: run {
            Log.e(TAG, "Entry $entryId not found — it may have been deleted")
            return Result.failure()
        }

        return try {
            val sourceText = resolveSourceText(entry)

            if (sourceText.isBlank()) {
                // Nothing to reason about (photo-only memory, or empty recording).
                // That's a complete memory, not a failed one.
                Log.d(TAG, "Entry $entryId has no source text — skipping analysis")
                intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)
                return Result.success()
            }

            runAnalysis(entry, sourceText)

            // Memory Understanding Engine (Stages 3–5): evidence extraction →
            // entity graph resolution. Failures log loudly and degrade — a
            // broken analyzer must not cost the user their summary/transcript.
            runCatching {
                understandingService.understand(normalizedContentFor(entry, sourceText))
            }.onFailure { Log.e(TAG, "Understanding stage failed for $entryId", it) }

            Log.d(TAG, "Intelligence pipeline completed for $entryId")

            // Enqueue embedding generation
            EmbeddingWorker.enqueue(applicationContext, entryId)

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Intelligence pipeline failed for $entryId", e)
            intelligenceDao.updateTranscriptionStatus(entryId, IntelligenceStatus.FAILED)
            intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.FAILED)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /**
     * The text this memory should be reasoned about: transcript (voice) +
     * written content (text) + OCR (photos), so a mixed memory contributes every
     * modality and a photo-only memory is no longer text-less.
     */
    private suspend fun resolveSourceText(entry: DiaryEntry): String {
        val audioFile = entry.audioPath.takeIf { it.isNotBlank() }?.let(::File)
        val hasAudio = audioFile?.exists() == true

        val transcript = if (hasAudio) {
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.TRANSCRIBING)
            val result = aiManager.getTranscriptionService().transcribe(audioFile!!.absolutePath).last()
            intelligenceDao.updateTranscript(
                entry.id, result.text, result.segments.firstOrNull()?.languageCode
            )
            if (result.segments.isNotEmpty()) {
                intelligenceDao.insertSegments(
                    result.segments.map {
                        TranscriptionSegmentEntity(
                            entryId = entry.id,
                            userId = entry.userId,
                            startTime = it.startTime,
                            endTime = it.endTime,
                            text = it.text,
                            languageCode = it.languageCode
                        )
                    }
                )
            }
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.COMPLETED)
            result.text
        } else {
            if (entry.audioPath.isNotBlank()) {
                // Path recorded but file is gone — worth knowing about; not fatal.
                Log.w(TAG, "Entry ${entry.id} references missing audio: ${entry.audioPath}")
            }
            intelligenceDao.updateTranscriptionStatus(entry.id, IntelligenceStatus.COMPLETED)
            ""
        }

        // Photo OCR (MU-3) — degrade to empty on any failure, never block the memory.
        val ocr = runCatching { photoTextExtractor.extractText(entry.imagePaths.orEmpty()) }
            .onFailure { Log.w(TAG, "Photo OCR failed for ${entry.id}", it) }
            .getOrDefault("")

        // Photo visual understanding (on-device labels + EXIF-GPS place). Its snippet
        // joins the source text so places become entities and labels become tags;
        // the human summary is stored for the "Echo sees…" line.
        val visual = runCatching { photoVisualDescriber.describe(entry.imagePaths.orEmpty()) }
            .onFailure { Log.w(TAG, "Photo visual understanding failed for ${entry.id}", it) }
            .getOrNull()
        intelligenceDao.updateVisualSummary(entry.id, visual?.asSummary())

        return listOfNotNull(
            transcript.takeIf { it.isNotBlank() },
            entry.textContent?.takeIf { it.isNotBlank() },
            ocr.takeIf { it.isNotBlank() },
            visual?.asSourceText()?.takeIf { it.isNotBlank() }
        ).joinToString("\n\n").trim()
    }

    private suspend fun runAnalysis(entry: DiaryEntry, sourceText: String) {
        val entryId = entry.id
        val userId = entry.userId

        // 1. Summary
        intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.SUMMARIZING)
        val summary = aiManager.getSummaryService().summarize(sourceText).last()

        // 2. Title — only when the user didn't write one. Never overwrite their words.
        val title = if (isAutoGeneratedTitle(entry.title)) {
            aiManager.getTitleGenerationService().generateTitle(sourceText).last()
        } else {
            entry.title
        }
        intelligenceDao.updateAnalysisResults(entryId, title, summary)

        // 3. Tags
        runCatching {
            aiManager.getTagSuggestionService().suggestTags(sourceText).last()
                .forEach { tagRepository.addTagToEntry(entryId, it) }
        }.onFailure { Log.w(TAG, "Tag suggestion failed for $entryId", it) }

        // 4. Classification
        intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.CLASSIFYING)
        val classification = aiManager.getMemoryClassificationService()
            .classify(sourceText, summary).last()
        intelligenceDao.insertClassification(
            MemoryClassificationEntity(
                entryId = entryId,
                userId = userId,
                categories = classification.categories,
                keywords = classification.keywords,
                entities = classification.entities,
                confidence = classification.confidence
            )
        )

        // 5. Memory linking
        intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.LINKING)
        val connections = intelligenceDao.getAllClassifications(userId)
            .filter { it.entryId != entryId }
            .mapNotNull { other ->
                val similarity = calculateSimilarity(classification, other)
                if (similarity > SIMILARITY_THRESHOLD) {
                    MemoryConnection(
                        fromEntryId = entryId,
                        toEntryId = other.entryId,
                        userId = userId,
                        similarity = similarity,
                        connectionReason = "Shared topics or keywords"
                    )
                } else null
            }
        if (connections.isNotEmpty()) {
            intelligenceDao.updateMemoryLinks(entryId, connections)
        }

        // 6. Timeline insights
        intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.ANALYZING_TIMELINE)
        runCatching {
            val insights = aiManager.getTimelineIntelligenceService().analyzeTimeline().last()
            if (insights.isNotEmpty()) {
                intelligenceDao.updateInsights(userId, insights.map {
                    TimelineInsightEntity(
                        id = it.id,
                        userId = userId,
                        title = it.title,
                        description = it.description,
                        type = it.type,
                        confidence = it.confidence,
                        relatedMemoryIds = it.relatedMemoryIds,
                        createdAt = it.createdAt,
                        priority = it.priority
                    )
                })
            }
        }.onFailure { Log.w(TAG, "Timeline analysis failed for $entryId", it) }

        intelligenceDao.updateAnalysisStatus(entryId, IntelligenceStatus.COMPLETED)
    }

    /** Stage-2 canonical form: after this, source kind no longer matters. */
    private fun normalizedContentFor(entry: DiaryEntry, sourceText: String): NormalizedContent {
        val kinds = buildSet {
            if (entry.audioPath.isNotBlank()) add(SourceKind.VOICE)
            if (!entry.textContent.isNullOrBlank()) add(SourceKind.TEXT)
            if (!entry.imagePaths.isNullOrEmpty()) add(SourceKind.PHOTO)
            if (!entry.videos.isNullOrEmpty()) add(SourceKind.VIDEO)
        }
        return NormalizedContent(
            memoryId = entry.id,
            userId = entry.userId,
            text = sourceText,
            sourceKinds = kinds.ifEmpty { setOf(SourceKind.TEXT) },
            capturedAt = entry.createdAt
        )
    }

    /**
     * True for titles the app generated itself, which the AI may improve on.
     * A title the user typed is left alone.
     */
    private fun isAutoGeneratedTitle(title: String): Boolean =
        title.isBlank() || title == "Untitled" || title == "Untitled Memory" ||
            title == "Memory of the Day" || title.startsWith("Recording ")

    private fun calculateSimilarity(
        current: com.dhaval.echo.domain.ai.MemoryClassification,
        other: MemoryClassificationEntity
    ): Float {
        val sharedKeywords = current.keywords.intersect(other.keywords.toSet()).size
        val sharedCategories = current.categories.intersect(other.categories.toSet()).size

        val keywordScore = if (current.keywords.isEmpty()) 0f
            else sharedKeywords.toFloat() / current.keywords.size
        val categoryScore = if (current.categories.isEmpty()) 0f
            else sharedCategories.toFloat() / current.categories.size

        return (keywordScore * 0.6f) + (categoryScore * 0.4f)
    }

    companion object {
        const val KEY_ENTRY_ID = "entryId"
        private const val TAG = "MemoryIntelWorker"
        private const val MAX_ATTEMPTS = 3
        private const val SIMILARITY_THRESHOLD = 0.3f
    }
}
