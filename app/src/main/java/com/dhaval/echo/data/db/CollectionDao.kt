package com.dhaval.echo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY updatedAt DESC")
    fun getAllCollections(): Flow<List<EchoCollection>>

    @Query("SELECT COUNT(*) FROM diary_entry_collection_cross_ref WHERE collectionId = :collectionId")
    fun getEntryCountForCollection(collectionId: String): Flow<Int>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getCollectionById(id: String): EchoCollection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: EchoCollection)

    @Update
    suspend fun updateCollection(collection: EchoCollection)

    @Delete
    suspend fun deleteCollection(collection: EchoCollection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: DiaryEntryCollectionCrossRef)

    @Query("DELETE FROM diary_entry_collection_cross_ref WHERE entryId = :entryId AND collectionId = :collectionId")
    suspend fun deleteCrossRef(entryId: String, collectionId: String)

    @Query("""
        SELECT de.* FROM diary_entries de
        INNER JOIN diary_entry_collection_cross_ref ref ON de.id = ref.entryId
        WHERE ref.collectionId = :collectionId AND de.deleted = 0
        ORDER BY de.createdAt DESC
    """)
    fun getEntriesForCollection(collectionId: String): Flow<List<DiaryEntry>>
    
    @Query("SELECT collectionId FROM diary_entry_collection_cross_ref WHERE entryId = :entryId")
    fun getCollectionIdsForEntry(entryId: String): Flow<List<String>>

    @Query("""
        SELECT * FROM collections 
        WHERE id IN (SELECT collectionId FROM diary_entry_collection_cross_ref WHERE entryId = :entryId)
    """)
    fun getCollectionsForEntry(entryId: String): Flow<List<EchoCollection>>
}
