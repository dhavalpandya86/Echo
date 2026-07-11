package com.dhaval.echo.data.diary

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import kotlinx.coroutines.flow.*
import javax.inject.Inject

class RealDiaryRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val authRepository: AuthRepository
) : DiaryRepository {
    
    override fun getEntryById(id: String): Flow<DiaryEntry?> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId != null) diaryEntryDao.getEntryByIdFlow(id, userId) else flowOf(null)
    }

    override suspend fun updateTitle(id: String, title: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(id) ?: return
        if (entry.userId == userId) {
            diaryEntryDao.updateEntry(entry.copy(title = title, updatedAt = java.time.LocalDateTime.now()))
        }
    }

    override suspend fun toggleFavorite(id: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(id) ?: return
        if (entry.userId == userId) {
            diaryEntryDao.updateEntry(entry.copy(favorite = !entry.favorite, updatedAt = java.time.LocalDateTime.now()))
        }
    }

    override suspend fun deleteEntry(id: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        diaryEntryDao.softDeleteEntry(id, userId)
    }
}
