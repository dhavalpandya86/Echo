package com.dhaval.echo.data.intelligence

import android.content.Context
import androidx.work.*
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.intelligence.IntelligenceRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class RealIntelligenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intelligenceDao: IntelligenceDao
) : IntelligenceRepository {

    override fun processEntry(entryId: String) {
        val workRequest = OneTimeWorkRequestBuilder<MemoryIntelligenceWorker>()
            .setInputData(workDataOf("entryId" to entryId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED) // For mock/cloud. In future offline, maybe not required.
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, java.util.concurrent.TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "intelligence_$entryId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
    }

    override fun getRelatedEntries(entryId: String): Flow<List<TimelineEntry>> {
        return intelligenceDao.getRelatedEntries(entryId).map { entries ->
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
