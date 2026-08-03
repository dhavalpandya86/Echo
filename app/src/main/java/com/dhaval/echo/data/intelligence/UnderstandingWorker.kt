package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dhaval.echo.data.collections.CollectionSuggestionService
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.data.db.MemoryClassificationEntity
import com.dhaval.echo.data.db.TimelineInsightEntity
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.data.reminders.ReminderScheduler
import com.dhaval.echo.data.understanding.StagedUnderstandingRunner
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import com.dhaval.echo.domain.understanding.Stage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.last
import java.util.concurrent.TimeUnit

/**
 * Asks the ~22 understanding questions about one memory, in the background.
 *
 * Split out from [MemoryIntelligenceWorker] on purpose. That worker's job is to
 * get readable text on screen as fast as it can — transcribe, correct, done.
 * This one does the slow, thorough part afterwards, where nobody is waiting: on
 * a phone answering each question with a local model, the full pass is minutes,
 * not seconds, and that is fine as long as it is not blocking anything.
 *
 * Safe to run more than once. Every question already settled is skipped, so a
 * retry after a process death resumes rather than restarting, and re-enqueueing
 * for an already-understood memory is close to free.
 */
@HiltWorker
class UnderstandingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val understandingDao: UnderstandingDao,
    private val intelligenceDao: IntelligenceDao,
    private val aiManager: AIManager,
    private val runner: StagedUnderstandingRunner,
    private val tagRepository: TagRepository,
    private val reminderScheduler: ReminderScheduler,
    private val collectionSuggestionService: CollectionSuggestionService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()
        val entry = diaryEntryDao.getEntryById(entryId) ?: run {
            // Deleted while queued. Nothing to understand, and nothing wrong.
            Log.d(TAG, "Entry $entryId no longer exists — dropping")
            return Result.success()
        }

        val sourceText = entry.readableText.orEmpty().ifBlank { entry.textContent.orEmpty() }
        if (sourceText.isBlank()) {
            Log.d(TAG, "Entry $entryId has no text to understand")
            return Result.success()
        }

        return try {
            runner.run(
                content = normalizedContentFor(entry, sourceText),
                stages = setOf(Stage.GROUND, Stage.INTERPRET, Stage.NARRATE),
                force = inputData.getBoolean(KEY_FORCE, false)
            )

            // Consequences of what was just understood. Each is independently
            // recoverable, so one failing must not cost the others.
            runCatching { reminderScheduler.scheduleForMemory(entryId) }
                .onFailure { Log.e(TAG, "Could not arm reminders for $entryId", it) }
            runCatching { applyEntityTags(entryId) }
                .onFailure { Log.e(TAG, "Could not tag $entryId", it) }
            runCatching { collectionSuggestionService.refresh() }
                .onFailure { Log.e(TAG, "Could not refresh collections", it) }
            runCatching { classifyAndAnalyzeTimeline(entry, sourceText) }
                .onFailure { Log.e(TAG, "Classification/timeline failed for $entryId", it) }

            EmbeddingWorker.enqueue(applicationContext, entryId)
            Log.d(TAG, "Understanding complete for $entryId")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Understanding failed for $entryId", e)
            // Individual questions record their own failures and are skipped
            // past; reaching here means something structural broke. Retrying
            // resumes from whatever already settled.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    /**
     * Tags a memory with the entities it actually named — people, activities,
     * objects, places, projects.
     *
     * There is deliberately no fallback. The old pipeline, finding no entities,
     * tagged the memory with its most frequent words: a note about taking Prabir
     * swimming came back tagged "Need, Next, Take, Think, Week". Those tags
     * looked like understanding and were noise, and they made the real failure —
     * that nothing was extracted — invisible. Silence is the honest answer, and
     * the user can add their own tag.
     */
    private suspend fun applyEntityTags(entryId: String) {
        understandingDao.getLinkedEntitiesOnce(entryId)
            .asSequence()
            .filter { !it.inferred }
            .filter { it.type in TAGGABLE_ENTITY_TYPES }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_ENTITY_TAGS)
            .forEach { tagRepository.addTagToEntry(entryId, it) }
    }

    /**
     * Corpus-level derivations that depend on this memory having been understood.
     *
     * Classification is kept because [com.dhaval.echo.data.ai.LocalTimelineIntelligenceService]
     * still reads `memory_classifications` to find active projects and recurring
     * topics. The entity graph now holds strictly better versions of both — real
     * PROJECT entities with memory counts, and CATEGORY facets — so rebuilding
     * timeline insights on the graph and retiring the classification table is
     * worth doing, but it is a change to timeline, not to extraction, and does
     * not belong in this pass.
     */
    private suspend fun classifyAndAnalyzeTimeline(entry: DiaryEntry, sourceText: String) {
        val classification = aiManager.getMemoryClassificationService()
            .classify(sourceText, entry.summary.orEmpty()).last()
        intelligenceDao.insertClassification(
            MemoryClassificationEntity(
                entryId = entry.id,
                userId = entry.userId,
                categories = classification.categories,
                keywords = classification.keywords,
                entities = classification.entities,
                confidence = classification.confidence
            )
        )

        val insights = aiManager.getTimelineIntelligenceService().analyzeTimeline().last()
        if (insights.isNotEmpty()) {
            intelligenceDao.updateInsights(
                entry.userId,
                insights.map {
                    TimelineInsightEntity(
                        id = it.id,
                        userId = entry.userId,
                        title = it.title,
                        description = it.description,
                        type = it.type,
                        confidence = it.confidence,
                        relatedMemoryIds = it.relatedMemoryIds,
                        createdAt = it.createdAt,
                        priority = it.priority
                    )
                }
            )
        }
    }

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
            capturedAt = entry.createdAt,
            imagePaths = entry.imagePaths.orEmpty()
        )
    }

    companion object {
        const val KEY_ENTRY_ID = "entryId"
        const val KEY_FORCE = "force"
        private const val TAG = "UnderstandingWorker"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_ENTITY_TAGS = 8

        /** Entity types worth surfacing as a tag on the memory. */
        private val TAGGABLE_ENTITY_TYPES = setOf(
            "PERSON", "PROJECT", "TOPIC", "PLACE", "ORG", "PRODUCT", "ACTIVITY", "OBJECT"
        )

        fun workNameFor(entryId: String) = "understanding_$entryId"

        /**
         * Queue understanding for a memory. Unique per memory and REPLACE, so
         * re-capturing or re-editing supersedes an in-flight pass rather than
         * racing it.
         */
        fun enqueue(context: Context, entryId: String, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<UnderstandingWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_ENTRY_ID, entryId)
                        .putBoolean(KEY_FORCE, force)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(workNameFor(entryId), ExistingWorkPolicy.REPLACE, request)
        }
    }
}
