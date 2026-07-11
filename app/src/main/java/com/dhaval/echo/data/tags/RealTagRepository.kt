package com.dhaval.echo.data.tags

import com.dhaval.echo.data.db.DiaryEntryTagCrossRef
import com.dhaval.echo.data.db.Tag
import com.dhaval.echo.data.db.TagDao
import com.dhaval.echo.domain.auth.AuthRepository
import com.dhaval.echo.domain.tags.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class RealTagRepository @Inject constructor(
    private val tagDao: TagDao,
    private val authRepository: AuthRepository
) : TagRepository {
    override fun getAllTags(): Flow<List<String>> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        tagDao.getAllTags(userId).map { tags -> tags.map { it.name } }
    }

    override fun getTagsForEntry(entryId: String): Flow<List<String>> = authRepository.currentUserId.flatMapLatest { userId ->
        if (userId == null) return@flatMapLatest flowOf(emptyList())
        tagDao.getTagsForEntry(entryId, userId)
    }

    override suspend fun addTagToEntry(entryId: String, tagName: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        tagDao.insertTag(Tag(tagName, userId))
        tagDao.insertCrossRef(DiaryEntryTagCrossRef(entryId, tagName, userId))
    }

    override suspend fun removeTagFromEntry(entryId: String, tagName: String) {
        val userId = authRepository.getCurrentUser()?.id ?: return
        tagDao.deleteCrossRef(entryId, tagName, userId)
    }

    override suspend fun searchTags(query: String): List<String> {
        val userId = authRepository.getCurrentUser()?.id ?: return emptyList()
        return tagDao.searchTags(userId, query)
    }
}
