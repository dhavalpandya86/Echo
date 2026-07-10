package com.dhaval.echo.domain.tags

import kotlinx.coroutines.flow.Flow

interface TagRepository {
    fun getAllTags(): Flow<List<String>>
    fun getTagsForEntry(entryId: String): Flow<List<String>>
    suspend fun addTagToEntry(entryId: String, tagName: String)
    suspend fun removeTagFromEntry(entryId: String, tagName: String)
    suspend fun searchTags(query: String): List<String>
}
