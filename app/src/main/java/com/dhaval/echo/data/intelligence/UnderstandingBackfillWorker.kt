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
    private val understandingService: MemoryUnderstandingService,
    private val tagRepository: TagRepository,
    private val aiManager: AIManager,
    private val authRepository: AuthRepository
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
            processed++
        }
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
        aiManager.getTagSuggestionService().suggestTags(text).last()
            .forEach { tagRepository.addTagToEntry(entryId, it) }
    }

    /** Reuse stored transcript + written text only — the heavy stages already ran. */
    private fun sourceTextOf(entry: DiaryEntry): String = listOfNotNull(
        entry.transcript?.takeIf { it.isNotBlank() },
        entry.textContent?.takeIf { it.isNotBlank() }
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
