package com.dhaval.echo.data.diary

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.EntryType
import com.dhaval.echo.domain.ai.IntelligenceStatus
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.diary.DiaryRepository
import com.dhaval.echo.domain.video.VideoAttachment
import com.dhaval.echo.domain.video.VideoStorageEngine
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

class RealDiaryRepository @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val authRepository: AuthRepository,
    private val intelligenceRepository: com.dhaval.echo.domain.intelligence.IntelligenceRepository,
    private val videoStorageEngine: VideoStorageEngine
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

    override suspend fun setPhotoCaption(entryId: String, photoPath: String, caption: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(entryId) ?: return
        if (entry.userId != userId) return
        val trimmed = caption.trim()
        val updated = (entry.photoCaptions ?: emptyMap()).toMutableMap().apply {
            if (trimmed.isEmpty()) remove(photoPath) else put(photoPath, trimmed)
        }
        diaryEntryDao.updateEntry(
            entry.copy(
                photoCaptions = updated.takeIf { it.isNotEmpty() },
                updatedAt = java.time.LocalDateTime.now()
            )
        )
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

    override fun getVideosForEntry(entryId: String): Flow<List<VideoAttachment>> =
        getEntryById(entryId).map { it?.videos.orEmpty() }

    override suspend fun addVideosToEntry(entryId: String, videos: List<VideoAttachment>) {
        if (videos.isEmpty()) return
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(entryId) ?: return
        if (entry.userId != userId) return

        val existing = entry.videos.orEmpty()
        val existingIds = existing.mapTo(mutableSetOf()) { it.id }
        val merged = existing + videos.filterNot { it.id in existingIds }

        diaryEntryDao.updateEntry(
            entry.copy(videos = merged, updatedAt = LocalDateTime.now())
        )
    }

    override suspend fun removeVideoFromEntry(entryId: String, videoId: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val entry = diaryEntryDao.getEntryById(entryId) ?: return
        if (entry.userId != userId) return

        val existing = entry.videos.orEmpty()
        val target = existing.find { it.id == videoId } ?: return

        val remaining = existing - target
        diaryEntryDao.updateEntry(
            entry.copy(
                videos = remaining.ifEmpty { null },
                updatedAt = LocalDateTime.now()
            )
        )

        // Detach first, then reclaim the bytes. If deletion fails the row is
        // already gone, which is the user-visible outcome they asked for.
        videoStorageEngine.deleteVideo(target.path)
        target.thumbnailPath?.let(videoStorageEngine::deleteThumbnail)
    }

    override suspend fun createTextEntry(
        title: String,
        textContent: String,
        imagePaths: List<String>,
        videos: List<VideoAttachment>,
        date: LocalDateTime?
    ): String {
        val userId = authRepository.getCurrentUser()?.id ?: throw Exception("Not authenticated")
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        // Backdated when adding on a past calendar day; "now" otherwise.
        val createdAt = date ?: now
        val hasMedia = imagePaths.isNotEmpty() || videos.isNotEmpty()
        val entryType = if (hasMedia) EntryType.MIXED else EntryType.TEXT
        val entry = DiaryEntry(
            id = id,
            userId = userId,
            title = title.ifBlank { "Untitled" },
            audioPath = "",
            createdAt = createdAt,
            updatedAt = now,
            duration = 0L,
            textContent = textContent,
            imagePaths = imagePaths.ifEmpty { null },
            videos = videos.ifEmpty { null },
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
