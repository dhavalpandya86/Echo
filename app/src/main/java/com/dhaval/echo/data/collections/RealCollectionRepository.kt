package com.dhaval.echo.data.collections

import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.data.db.CollectionDao
import com.dhaval.echo.data.db.DiaryEntryCollectionCrossRef
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.collections.CollectionRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class RealCollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao,
    private val authRepository: AuthRepository
) : CollectionRepository {
    override fun getAllCollections(): Flow<List<EchoCollection>> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        collectionDao.getAllCollections(userId)
    }

    override fun getCollectionById(id: String): Flow<EchoCollection?> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(null)
        flow { emit(collectionDao.getCollectionById(id, userId)) }
    }

    override fun getEntriesForCollection(collectionId: String): Flow<List<TimelineEntry>> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(emptyList())
            collectionDao.getEntriesForCollection(collectionId, userId).map { entries ->
                entries.map { entry ->
                    TimelineEntry(
                        id = entry.id,
                        title = entry.title,
                        audioPath = entry.audioPath,
                        durationMillis = entry.duration,
                        timestamp = entry.createdAt,
                        transcription = entry.transcript,
                        transcriptionStatus = entry.transcriptionStatus,
                        isSynced = false,
                        isFavorite = entry.favorite
                    )
                }
            }
        }
    }

    override fun getEntryCountForCollection(collectionId: String): Flow<Int> {
        return authRepository.currentUserId.flatMapLatest { userId ->
            if (userId == null) return@flatMapLatest flowOf(0)
            collectionDao.getEntryCountForCollection(collectionId, userId)
        }
    }

    override suspend fun createCollection(name: String, description: String?) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        val now = LocalDateTime.now()
        val collection = EchoCollection(
            id = UUID.randomUUID().toString(),
            userId = userId,
            name = name,
            description = description,
            createdAt = now,
            updatedAt = now
        )
        collectionDao.insertCollection(collection)
    }

    override suspend fun updateCollection(collection: EchoCollection) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        if (collection.userId == userId) {
            collectionDao.updateCollection(collection.copy(updatedAt = LocalDateTime.now()))
        }
    }

    override suspend fun deleteCollection(collection: EchoCollection) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        if (collection.userId == userId) {
            collectionDao.deleteCollection(collection)
        }
    }

    override suspend fun addEntryToCollection(entryId: String, collectionId: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        collectionDao.insertCrossRef(DiaryEntryCollectionCrossRef(entryId, collectionId, userId))
    }

    override suspend fun removeEntryFromCollection(entryId: String, collectionId: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        collectionDao.deleteCrossRef(entryId, collectionId, userId)
    }

    override fun getCollectionsForEntry(entryId: String): Flow<List<EchoCollection>> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        collectionDao.getCollectionsForEntry(entryId, userId)
    }
}
