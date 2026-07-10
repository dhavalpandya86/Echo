package com.dhaval.echo.data.diary

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.diary.DiaryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class RealDiaryRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao
) : DiaryRepository {
    
    // Using flow directly from DAO if possible, but DAO currently returns suspend for single item.
    // Let's refine DAO to return Flow for single item if needed, 
    // or just use a flow that emits once or when triggered.
    // For simplicity, let's use a query that returns Flow.
    override fun getEntryById(id: String): Flow<DiaryEntry?> = diaryEntryDao.getEntryByIdFlow(id)

    override suspend fun updateTitle(id: String, title: String) {
        val entry = diaryEntryDao.getEntryById(id) ?: return
        diaryEntryDao.updateEntry(entry.copy(title = title, updatedAt = java.time.LocalDateTime.now()))
    }

    override suspend fun toggleFavorite(id: String) {
        val entry = diaryEntryDao.getEntryById(id) ?: return
        diaryEntryDao.updateEntry(entry.copy(favorite = !entry.favorite, updatedAt = java.time.LocalDateTime.now()))
    }

    override suspend fun deleteEntry(id: String) {
        diaryEntryDao.softDeleteEntry(id)
    }
}
