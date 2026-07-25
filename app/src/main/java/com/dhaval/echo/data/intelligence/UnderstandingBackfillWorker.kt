package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.tags.TagRepository
import com.dhaval.echo.domain.understanding.MemoryUnderstandingService
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last

/**
 * One-shot backfill: re-runs the Understanding stage over every existing memory so
 * memories captured before the entity graph / feelings / Worlds landed get their
 * entities, links, feelings, and graph edges too.
 *
 * It reuses each memory's already-stored transcript + written text — no
 * re-transcription, no re-OCR, no Claude calls — so it's cheap and offline. The
 * Understanding write is idempotent per memory, so running this twice is safe.
 */
@HiltWorker
class UnderstandingBackfillWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val understandingDao: com.dhaval.echo.data.db.UnderstandingDao,
    private val understandingService: MemoryUnderstandingService,
    private val tagRepository: TagRepository,
    private val aiManager: AIManager,
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
                understandingService.understand(normalizedContentFor(entry, userId, text))
            }.onFailure { Log.e(TAG, "Backfill understanding failed for ${entry.id}", it) }
            runCatching { refreshTags(entry.id, text) }
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
     * Replace the old fixed-placeholder tags with real, content-derived ones.
     * Only the exact legacy placeholder trio is removed — genuine user tags are
     * left alone — then fresh tags are added (adding is idempotent).
     */
    private suspend fun refreshTags(entryId: String, text: String) {
        val existing = tagRepository.getTagsForEntry(entryId).first().toSet()
        if (existing.containsAll(LEGACY_PLACEHOLDER_TAGS)) {
            LEGACY_PLACEHOLDER_TAGS.forEach { tagRepository.removeTagFromEntry(entryId, it) }
        }

        // Prefer the specific entities the graph just extracted (people, places,
        // projects…) — the same source the live pipeline now tags from. Keyword
        // tags are only a floor for memories that surfaced no entities.
        val entityNames = understandingDao.getLinkedEntitiesOnce(entryId)
            .asSequence()
            .filter { !it.inferred && it.type in TAGGABLE_ENTITY_TYPES }
            .map { it.name.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_ENTITY_TAGS)
            .toList()

        if (entityNames.isNotEmpty()) {
            entityNames.forEach { tagRepository.addTagToEntry(entryId, it) }
        } else {
            aiManager.getTagSuggestionService().suggestTags(text).last()
                .forEach { tagRepository.addTagToEntry(entryId, it) }
        }
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
            capturedAt = entry.createdAt
        )
    }

    companion object {
        private const val TAG = "UnderstandingBackfill"
        const val WORK_NAME = "understanding_backfill"

        /** The exact tags the old FakeTagSuggestionService emitted for every memory. */
        private val LEGACY_PLACEHOLDER_TAGS = listOf("Personal", "Reflection", "Voice")

        private const val MAX_ENTITY_TAGS = 8
        private val TAGGABLE_ENTITY_TYPES =
            setOf("PERSON", "PROJECT", "TOPIC", "PLACE", "ORG", "PRODUCT")

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
