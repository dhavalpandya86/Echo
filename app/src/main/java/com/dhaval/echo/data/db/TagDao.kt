package com.dhaval.echo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: Tag)

    @Query("SELECT * FROM tags WHERE userId = :userId ORDER BY name ASC")
    fun getAllTags(userId: String): Flow<List<Tag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: DiaryEntryTagCrossRef)

    @Query("DELETE FROM diary_entry_tag_cross_ref WHERE entryId = :entryId AND tagName = :tagName AND userId = :userId")
    suspend fun deleteCrossRef(entryId: String, tagName: String, userId: String)

    @Query("SELECT tagName FROM diary_entry_tag_cross_ref WHERE entryId = :entryId AND userId = :userId")
    fun getTagsForEntry(entryId: String, userId: String): Flow<List<String>>

    @Query("SELECT name FROM tags WHERE userId = :userId AND name LIKE :query || '%'")
    suspend fun searchTags(userId: String, query: String): List<String>

    @Query("UPDATE tags SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyTags(userId: String)

    @Query("UPDATE diary_entry_tag_cross_ref SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyCrossRefs(userId: String)
}
