package com.dhaval.echo.data.tags

import com.dhaval.echo.data.db.DiaryEntryTagCrossRef
import com.dhaval.echo.data.db.Tag
import com.dhaval.echo.data.db.TagDao
import com.dhaval.echo.domain.tags.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class RealTagRepository @Inject constructor(
    private val tagDao: TagDao
) : TagRepository {
    override fun getAllTags(): Flow<List<String>> = 
        tagDao.getAllTags().map { tags -> tags.map { it.name } }

    override fun getTagsForEntry(entryId: String): Flow<List<String>> = 
        tagDao.getTagsForEntry(entryId)

    override suspend fun addTagToEntry(entryId: String, tagName: String) {
        tagDao.insertTag(Tag(tagName))
        tagDao.insertCrossRef(DiaryEntryTagCrossRef(entryId, tagName))
    }

    override suspend fun removeTagFromEntry(entryId: String, tagName: String) {
        tagDao.deleteCrossRef(entryId, tagName)
    }

    override suspend fun searchTags(query: String): List<String> = 
        tagDao.searchTags(query)
}
