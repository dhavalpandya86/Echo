package com.dhaval.echo.data.intelligence

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.dhaval.echo.data.db.DiaryEntryDao
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

    companion object {
        const val KEY_ENTRY_ID = "entryId"

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
