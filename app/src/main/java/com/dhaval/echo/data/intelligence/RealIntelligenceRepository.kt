package com.dhaval.echo.data.intelligence

import android.content.Context
import androidx.work.*
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.domain.intelligence.IntelligenceRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class RealIntelligenceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intelligenceDao: IntelligenceDao,
    private val authRepository: com.dhaval.echo.domain.auth.AuthRepository
) : IntelligenceRepository {

    override fun processEntry(entryId: String) {
        // ... (existing logic is fine, it just triggers the worker)
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
