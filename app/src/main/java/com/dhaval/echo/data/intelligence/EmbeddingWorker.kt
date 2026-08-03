package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.data.db.MemoryConnection
import com.dhaval.echo.domain.embeddings.EmbeddingEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

private const val TAG = "EmbeddingWorker"

/**
 * Worker that generates semantic embeddings for a diary entry in the background.
 */
@HiltWorker
class EmbeddingWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val diaryEntryDao: DiaryEntryDao,
    private val intelligenceDao: IntelligenceDao,
    private val embeddingEngine: EmbeddingEngine
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryId = inputData.getString(KEY_ENTRY_ID) ?: return Result.failure()
        
        val entry = diaryEntryDao.getEntryById(entryId) ?: run {
            Log.e(TAG, "Entry $entryId not found")
            return Result.failure()
        }

        val textToEmbed = listOfNotNull(
            entry.title.takeIf { it.isNotBlank() && it != "Untitled" },
            entry.transcript,
            entry.textContent
        ).joinToString("\n\n").trim()

        if (textToEmbed.isBlank()) {
            Log.d(TAG, "Nothing to embed for entry $entryId")
            return Result.success()
        }

        return try {
            val result = embeddingEngine.generateEmbedding(textToEmbed)
            if (result.success) {
                val updatedEntry = entry.copy(
                    embedding = result.vector,
                    embeddingDimensions = result.dimensions,
                    embeddingModelVersion = result.modelVersion,
                    embeddingCreatedAt = System.currentTimeMillis()
                )
                diaryEntryDao.updateEntry(updatedEntry)
                Log.i(TAG, "Embedding updated for entry $entryId")

                // Related memories, done right: cosine-compare this memory's e5
                // vector against every other embedded memory. This replaces the
                // old classification-keyword linker, which scored every same-day
                // pair identically and so marked everything "related". Failure
                // here must not fail the embedding itself.
                runCatching { linkRelatedMemories(updatedEntry) }
                    .onFailure { Log.w(TAG, "Memory linking failed for $entryId", it) }

                Result.success()
            } else {
                Log.e(TAG, "Embedding failed for $entryId: ${result.errorMessage}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in EmbeddingWorker for $entryId", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /**
     * Writes [entry]'s related-memory links from real e5 similarity.
     *
     * The e5 vectors are L2-normalised, so cosine similarity is just the dot
     * product. We keep only neighbours above [RELATED_THRESHOLD] and cap at
     * [MAX_LINKS] so a memory links to its genuinely-closest handful, not to a
     * long tail of the merely-adjacent. Links are written both ways so the
     * related list shows up on both memories' detail screens, and this entry's
     * prior links are cleared first (self-healing on re-run).
     */
    private suspend fun linkRelatedMemories(entry: DiaryEntry) {
        val vector = entry.embedding ?: return

        val neighbours = diaryEntryDao.getEntriesWithEmbeddings(entry.userId)
            .asSequence()
            .filter { it.id != entry.id }
            .mapNotNull { other ->
                val otherVec = other.embedding ?: return@mapNotNull null
                if (otherVec.size != vector.size) return@mapNotNull null
                val sim = cosine(vector, otherVec)
                if (sim >= RELATED_THRESHOLD) other.id to sim else null
            }
            .sortedByDescending { it.second }
            .take(MAX_LINKS)
            .toList()

        val connections = neighbours.flatMap { (otherId, sim) ->
            listOf(
                MemoryConnection(
                    fromEntryId = entry.id,
                    toEntryId = otherId,
                    userId = entry.userId,
                    similarity = sim,
                    connectionReason = "Similar in meaning"
                ),
                // Mirror it so the older memory also shows this one as related.
                MemoryConnection(
                    fromEntryId = otherId,
                    toEntryId = entry.id,
                    userId = entry.userId,
                    similarity = sim,
                    connectionReason = "Similar in meaning"
                )
            )
        }

        intelligenceDao.replaceMemoryLinks(entry.id, connections)
        Log.i(TAG, "Linked ${neighbours.size} related memories to ${entry.id}")
    }

    /** Dot product; valid as cosine because e5 vectors are L2-normalised. */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }

    companion object {
        const val KEY_ENTRY_ID = "entryId"

        /**
         * Cosine floor for "related". e5-small pushes even loosely-associated
         * passages into the 0.75–0.82 band, so the bar sits above that: pairs
         * must share real subject matter, not just both be diary prose.
         */
        private const val RELATED_THRESHOLD = 0.84f
        private const val MAX_LINKS = 8

        fun enqueue(context: Context, entryId: String) {
            val data = workDataOf(KEY_ENTRY_ID to entryId)
            val request = OneTimeWorkRequestBuilder<EmbeddingWorker>()
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueueUniqueWork(
                "embedding_$entryId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
