package com.dhaval.echo.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DiaryEntryDao {
    @Query("SELECT * FROM diary_entries WHERE userId = :userId AND deleted = 0 ORDER BY createdAt DESC")
    fun getAllEntries(userId: String): Flow<List<DiaryEntry>>

    @Query("SELECT * FROM diary_entries WHERE id = :id AND userId = :userId LIMIT 1")
    fun getEntryByIdFlow(id: String, userId: String): Flow<DiaryEntry?>

    @Query("SELECT * FROM diary_entries WHERE id = :id LIMIT 1")
    suspend fun getEntryById(id: String): DiaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: DiaryEntry)

    @Update
    suspend fun updateEntry(entry: DiaryEntry)

    @Query("UPDATE diary_entries SET deleted = 1 WHERE id = :id AND userId = :userId")
    suspend fun softDeleteEntry(id: String, userId: String)

    @Delete
    suspend fun hardDeleteEntry(entry: DiaryEntry)

    @Query("""
        SELECT DISTINCT de.* FROM diary_entries de
        LEFT JOIN diary_entry_tag_cross_ref ref ON de.id = ref.entryId
        WHERE de.userId = :userId AND de.deleted = 0 
        AND (de.title LIKE '%' || :query || '%' OR ref.tagName LIKE '%' || :query || '%')
        AND (:onlyFavorites = 0 OR de.favorite = 1)
        ORDER BY de.createdAt DESC
    """)
    fun searchEntries(userId: String, query: String, onlyFavorites: Boolean): Flow<List<DiaryEntry>>

    @Query("UPDATE diary_entries SET userId = :userId WHERE userId = 'legacy_user'")
    suspend fun migrateLegacyEntries(userId: String)
}
