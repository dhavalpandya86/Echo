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

    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<Tag>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCrossRef(crossRef: DiaryEntryTagCrossRef)

    @Query("DELETE FROM diary_entry_tag_cross_ref WHERE entryId = :entryId AND tagName = :tagName")
    suspend fun deleteCrossRef(entryId: String, tagName: String)

    @Query("SELECT tagName FROM diary_entry_tag_cross_ref WHERE entryId = :entryId")
    fun getTagsForEntry(entryId: String): Flow<List<String>>

    @Query("SELECT name FROM tags WHERE name LIKE :query || '%'")
    suspend fun searchTags(query: String): List<String>
}
