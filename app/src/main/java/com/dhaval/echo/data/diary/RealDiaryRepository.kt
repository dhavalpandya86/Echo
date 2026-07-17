package com.dhaval.echo.data.diary

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.EntryType
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

class RealDiaryRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val authRepository: AuthRepository,
    private val intelligenceRepository: com.dhaval.echo.domain.intelligence.IntelligenceRepository
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

    override suspend fun createTextEntry(title: String, textContent: String, imagePaths: List<String>): String {
        val userId = authRepository.getCurrentUser()?.id ?: throw Exception("Not authenticated")
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        val entryType = if (imagePaths.isNotEmpty()) EntryType.MIXED else EntryType.TEXT
        val entry = DiaryEntry(
            id = id,
            userId = userId,
            title = title.ifBlank { "Untitled" },
            audioPath = "",
            createdAt = now,
            updatedAt = now,
            duration = 0L,
            textContent = textContent,
            imagePaths = imagePaths.ifEmpty { null },
            entryType = entryType,
            transcriptionStatus = IntelligenceStatus.COMPLETED,
            analysisStatus = IntelligenceStatus.PENDING
        )
        diaryEntryDao.insertEntry(entry)

        // Written/photo memories go through the same intelligence pipeline as voice
        // ones — the worker skips transcription when there's no audio.
        intelligenceRepository.processEntry(id)

        return id
    }
}
