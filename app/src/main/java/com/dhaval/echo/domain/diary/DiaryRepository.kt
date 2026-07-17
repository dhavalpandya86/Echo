package com.dhaval.echo.domain.diary

import com.dhaval.echo.data.db.DiaryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Repository for managing diary entries.
 */
interface DiaryRepository {
    fun getEntryById(id: String): Flow<DiaryEntry?>
    suspend fun updateTitle(id: String, title: String)
    suspend fun toggleFavorite(id: String)
    suspend fun deleteEntry(id: String)
    suspend fun createTextEntry(title: String, textContent: String, imagePaths: List<String> = emptyList()): String
}
