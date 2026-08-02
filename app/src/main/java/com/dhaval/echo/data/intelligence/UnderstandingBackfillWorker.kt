package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.understanding.StagedUnderstandingRunner
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import com.dhaval.echo.domain.understanding.Stage
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * One-shot backfill: re-asks every understanding question about every existing
 * memory, so memories captured before a question existed still get answered.
 *
 * It reuses each memory's already-stored text — no re-transcription, no re-OCR —
 * so it is as cheap as the engines behind the questions. Every write is
 * idempotent per memory, and `force` clears the previous run records so
 * questions that already settled under an older registry are asked again.
 *
 * Runs the memories one at a time rather than in parallel. On-device models hold
 * a single engine instance and a phone has one set of cores; a fan-out here
 * would contend for both and finish no sooner.
 */
@HiltWorker
class UnderstandingBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val understandingDao: com.dhaval.echo.data.db.UnderstandingDao,
    private val runner: StagedUnderstandingRunner,
    private val tagRepository: TagRepository,
    private val authRepository: AuthRepository,
    private val collectionSuggestionService: com.dhaval.echo.data.collections.CollectionSuggestionService
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val userId = authRepository.getCurrentUser()?.id ?: run {
            Log.w(TAG, "No current user — nothing to backfill")
            return Result.success()
        }

        val entries = diaryEntryDao.getAllEntries(userId).first()
        var processed = 0
        var skipped = 0
        for (entry in entries) {
            val text = sourceTextOf(entry)
            if (text.isBlank()) {
                skipped++
                continue
            }
            runCatching {
                runner.run(
                    content = normalizedContentFor(entry, userId, text),
                    // PREPARE is skipped: the text is already stored, and
                    // re-cleaning it would rewrite what the user has been reading.
                    stages = setOf(Stage.GROUND, Stage.INTERPRET, Stage.NARRATE),
                    force = true
                )
            }.onFailure { Log.e(TAG, "Backfill understanding failed for ${entry.id}", it) }
            runCatching { refreshTags(entry.id) }
                .onFailure { Log.w(TAG, "Backfill tag refresh failed for ${entry.id}", it) }
            // Recompute the e5 embedding + related-memory links for this entry.
            EmbeddingWorker.enqueue(applicationContext, entry.id)
            processed++
        }

        // Now that the graph is rebuilt, auto-populate Collections from recurring
        // topics/projects (Worlds derive from the same graph, on demand).
        runCatching { collectionSuggestionService.refresh() }
            .onFailure { Log.w(TAG, "Backfill collection curation failed", it) }

        Log.i(TAG, "Understanding backfill done: $processed processed, $skipped without text")
        return Result.success()
    }

    /**
     * Replace placeholder and keyword tags with the entities the graph actually
     * extracted.
     *
     * Removes both the old fixed placeholder trio and any tag that is not an
     * entity this memory names — that is how the word-frequency tags
     * ("Need", "Next", "Take") get cleaned off memories that already have them.
     * A tag the user typed themselves survives, because it is compared against
     * the whole set of entity names, not deleted blindly.
     */
    private suspend fun refreshTags(entryId: String) {
        val existing = tagRepository.getTagsForEntry(entryId).first().toSet()
        if (existing.containsAll(LEGACY_PLACEHOLDER_TAGS)) {
            LEGACY_PLACEHOLDER_TAGS.forEach { tagRepository.removeTagFromEntry(entryId, it) }
        }

        val entityNames = understandingDao.getLinkedEntitiesOnce(entryId)
            .asSequence()
            .filter { !it.inferred && it.type in TAGGABLE_ENTITY_TYPES }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_ENTITY_TAGS)
            .toList()

        entityNames.forEach { tagRepository.addTagToEntry(entryId, it) }
        // No keyword floor. A memory that names nothing gets no tags — see
        // UnderstandingWorker.applyEntityTags for why that is the honest answer.
    }

    /**
     * Reuse stored text — no re-transcription/re-OCR. Includes the photo visual
     * summary ("Echo sees…") and the user's own photo captions so photo-only
     * memories still contribute people/places to the entity graph (→ Worlds),
     * instead of being skipped for having no transcript.
     */
    private fun sourceTextOf(entry: DiaryEntry): String = listOfNotNull(
        entry.transcript?.takeIf { it.isNotBlank() },
        entry.textContent?.takeIf { it.isNotBlank() },
        entry.visualSummary?.takeIf { it.isNotBlank() },
        entry.photoCaptions?.values?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString("\n")
    ).joinToString("\n\n").trim()

    private fun normalizedContentFor(entry: DiaryEntry, userId: String, text: String): NormalizedContent {
        val kinds = buildSet {
            if (entry.audioPath.isNotBlank()) add(SourceKind.VOICE)
            if (!entry.textContent.isNullOrBlank()) add(SourceKind.TEXT)
            if (!entry.imagePaths.isNullOrEmpty()) add(SourceKind.PHOTO)
            if (!entry.videos.isNullOrEmpty()) add(SourceKind.VIDEO)
        }
        return NormalizedContent(
            memoryId = entry.id,
            userId = userId,
            text = text,
            sourceKinds = kinds.ifEmpty { setOf(SourceKind.TEXT) },
            capturedAt = entry.createdAt,
            imagePaths = entry.imagePaths.orEmpty()
        )
    }

    companion object {
        private const val TAG = "UnderstandingBackfill"
        const val WORK_NAME = "understanding_backfill"

        /** The exact tags the old FakeTagSuggestionService emitted for every memory. */
        private val LEGACY_PLACEHOLDER_TAGS = listOf("Personal", "Reflection", "Voice")

        private const val MAX_ENTITY_TAGS = 8
        private val TAGGABLE_ENTITY_TYPES = setOf(
            "PERSON", "PROJECT", "TOPIC", "PLACE", "ORG", "PRODUCT", "ACTIVITY", "OBJECT"
        )

        /** Enqueue the one-shot backfill; KEEP so repeated taps don't pile up. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<UnderstandingBackfillWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
