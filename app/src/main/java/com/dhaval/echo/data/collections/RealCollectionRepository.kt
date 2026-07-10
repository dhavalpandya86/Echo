package com.dhaval.echo.data.collections

import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.data.db.CollectionDao
import com.dhaval.echo.data.db.DiaryEntryCollectionCrossRef
import com.dhaval.echo.domain.collections.CollectionRepository
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

class RealCollectionRepository @Inject constructor(
    private val collectionDao: CollectionDao
) : CollectionRepository {
    override fun getAllCollections(): Flow<List<EchoCollection>> = collectionDao.getAllCollections()

    override fun getCollectionById(id: String): Flow<EchoCollection?> = flow {
        emit(collectionDao.getCollectionById(id))
    }

    override fun getEntriesForCollection(collectionId: String): Flow<List<TimelineEntry>> {
        return collectionDao.getEntriesForCollection(collectionId).map { entries ->
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

    override fun getEntryCountForCollection(collectionId: String): Flow<Int> {
        return collectionDao.getEntryCountForCollection(collectionId)
    }

    override suspend fun createCollection(name: String, description: String?) {
        val now = LocalDateTime.now()
        val collection = EchoCollection(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            createdAt = now,
            updatedAt = now
        )
        collectionDao.insertCollection(collection)
    }

    override suspend fun updateCollection(collection: EchoCollection) {
        collectionDao.updateCollection(collection.copy(updatedAt = LocalDateTime.now()))
    }

    override suspend fun deleteCollection(collection: EchoCollection) {
        collectionDao.deleteCollection(collection)
    }

    override suspend fun addEntryToCollection(entryId: String, collectionId: String) {
        collectionDao.insertCrossRef(DiaryEntryCollectionCrossRef(entryId, collectionId))
    }

    override suspend fun removeEntryFromCollection(entryId: String, collectionId: String) {
        collectionDao.deleteCrossRef(entryId, collectionId)
    }

    override fun getCollectionsForEntry(entryId: String): Flow<List<EchoCollection>> =
        collectionDao.getCollectionsForEntry(entryId)
}
