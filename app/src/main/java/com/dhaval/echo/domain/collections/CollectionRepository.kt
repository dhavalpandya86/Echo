package com.dhaval.echo.domain.collections

import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.domain.timeline.TimelineEntry
import kotlinx.coroutines.flow.Flow

interface CollectionRepository {
    fun getAllCollections(): Flow<List<EchoCollection>>
    fun getCollectionById(id: String): Flow<EchoCollection?>
    fun getEntriesForCollection(collectionId: String): Flow<List<TimelineEntry>>
    fun getEntryCountForCollection(collectionId: String): Flow<Int>
    suspend fun createCollection(name: String, description: String? = null)
    suspend fun updateCollection(collection: EchoCollection)
    suspend fun deleteCollection(collection: EchoCollection)
    suspend fun addEntryToCollection(entryId: String, collectionId: String)
    suspend fun removeEntryFromCollection(entryId: String, collectionId: String)
    fun getCollectionsForEntry(entryId: String): Flow<List<EchoCollection>>
}
