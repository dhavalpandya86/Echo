package com.dhaval.echo.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections WHERE userId = :userId ORDER BY updatedAt DESC")
    fun getAllCollections(userId: String): Flow<List<EchoCollection>>

    @Query("SELECT COUNT(*) FROM diary_entry_collection_cross_ref WHERE collectionId = :collectionId AND userId = :userId")
    fun getEntryCountForCollection(collectionId: String, userId: String): Flow<Int>

    @Query("SELECT * FROM collections WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getCollectionById(id: String, userId: String): EchoCollection?

    /** The auto-collection built around a given entity, if one exists. */
    @Query("SELECT * FROM collections WHERE userId = :userId AND sourceEntityId = :entityId LIMIT 1")
    suspend fun getCollectionForEntity(userId: String, entityId: String): EchoCollection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: EchoCollection)

    @Update
    suspend fun updateCollection(collection: EchoCollection)

    @Delete
    suspend fun deleteCollection(collection: EchoCollection)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: DiaryEntryCollectionCrossRef)

    @Query("DELETE FROM diary_entry_collection_cross_ref WHERE entryId = :entryId AND collectionId = :collectionId AND userId = :userId")
    suspend fun deleteCrossRef(entryId: String, collectionId: String, userId: String)

    @Query("""
        SELECT de.* FROM diary_entries de
        INNER JOIN diary_entry_collection_cross_ref ref ON de.id = ref.entryId
        WHERE ref.collectionId = :collectionId AND de.userId = :userId AND de.deleted = 0
        ORDER BY de.createdAt DESC
    """)
    fun getEntriesForCollection(collectionId: String, userId: String): Flow<List<DiaryEntry>>
    
    @Query("SELECT collectionId FROM diary_entry_collection_cross_ref WHERE entryId = :entryId AND userId = :userId")
    fun getCollectionIdsForEntry(entryId: String, userId: String): Flow<List<String>>

    @Query("""
        SELECT * FROM collections 
        WHERE userId = :userId AND id IN (SELECT collectionId FROM diary_entry_collection_cross_ref WHERE entryId = :entryId AND userId = :userId)
    """)
    fun getCollectionsForEntry(entryId: String, userId: String): Flow<List<EchoCollection>>

    @Query("UPDATE collections SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyCollections(userId: String)

    @Query("UPDATE diary_entry_collection_cross_ref SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyCrossRefs(userId: String)
}
