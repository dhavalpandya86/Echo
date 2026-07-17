package com.dhaval.echo.data.intelligence

import android.content.Context
import androidx.work.*
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.intelligence.IntelligenceRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class RealIntelligenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intelligenceDao: IntelligenceDao,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : IntelligenceRepository {

    override fun processEntry(entryId: String) {
        val request = OneTimeWorkRequestBuilder<MemoryIntelligenceWorker>()
            .setInputData(workDataOf(MemoryIntelligenceWorker.KEY_ENTRY_ID to entryId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // Unique per entry: re-processing an entry should replace any in-flight
        // run rather than stack a second one alongside it.
        WorkManager.getInstance(context).enqueueUniqueWork(
            workNameFor(entryId),
            ExistingWorkPolicy.REPLACE,
            request
        )
        android.util.Log.d(TAG, "Enqueued intelligence work for entry $entryId")
    }

    private fun workNameFor(entryId: String) = "intelligence_$entryId"

    private companion object {
        const val TAG = "IntelligenceRepo"
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getRelatedEntries(entryId: String): Flow<List<TimelineEntry>> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
            intelligenceDao.getRelatedEntries(entryId, userId).map { entries ->
                entries.map { entry ->
                    TimelineEntry(
                        id = entry.id,
                        title = entry.title,
                        audioPath = entry.audioPath,
                        durationMillis = entry.duration,
                        timestamp = entry.createdAt,
                        transcription = entry.transcript,
                        isSynced = false,
                        isFavorite = entry.favorite
                    )
                }
            }
        }
    }
}
