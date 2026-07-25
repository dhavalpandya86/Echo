package com.dhaval.echo.data.collections

import android.util.Log
import com.dhaval.echo.data.db.CollectionDao
import com.dhaval.echo.data.db.DiaryEntryCollectionCrossRef
import com.dhaval.echo.data.db.EchoCollection
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * Auto-populates Collections from the entity graph.
 *
 * A memory's Collections used to be manual-only, so a fresh diary showed an empty
 * screen. This curator proposes a collection per strong recurring topic/project —
 * the ones the graph has seen across enough memories to be worth grouping — and
 * files those memories into it. Each auto-collection is tied to its source entity
 * ([EchoCollection.sourceEntityId]) so re-runs update the same collection instead
 * of spawning duplicates. Manual collections are never touched.
 *
 * Runs after understanding, on-device, from the live graph — no key required.
 */
class CollectionSuggestionService @Inject constructor(
    private val understandingDao: UnderstandingDao,
    private val collectionDao: CollectionDao,
    private val authRepository: AuthRepository
) {

    suspend fun refresh() {
        val userId = authRepository.getCurrentUser()?.id ?: return

        // Topics and projects are the axes people actually browse a diary by;
        // people/places are better served by the entity graph and Worlds.
        val candidates = (understandingDao.getEntitiesByType(userId, "TOPIC") +
            understandingDao.getEntitiesByType(userId, "PROJECT"))
            .filter { !it.archived }
            .filter { it.memoryCount >= MIN_MEMORIES }
            .sortedByDescending { it.memoryCount }
            .take(MAX_AUTO_COLLECTIONS)

        for (entity in candidates) {
            runCatching { syncCollectionFor(entity.id, entity.name, userId) }
                .onFailure { Log.w(TAG, "Auto-collection sync failed for ${entity.name}", it) }
        }
    }

    private suspend fun syncCollectionFor(entityId: String, entityName: String, userId: String) {
        val now = LocalDateTime.now()
        val existing = collectionDao.getCollectionForEntity(userId, entityId)
        val collectionId = existing?.id ?: UUID.randomUUID().toString()

        if (existing == null) {
            collectionDao.insertCollection(
                EchoCollection(
                    id = collectionId,
                    userId = userId,
                    name = entityName,
                    description = "Memories about $entityName",
                    createdAt = now,
                    updatedAt = now,
                    isAiGenerated = true,
                    sourceEntityId = entityId
                )
            )
        }

        // File every memory that names this entity. insertCrossRef REPLACEs, so
        // re-adding an already-filed memory is a no-op — safe to run each time.
        understandingDao.getLinksForEntity(entityId)
            .map { it.memoryId }
            .distinct()
            .forEach { memoryId ->
                collectionDao.insertCrossRef(
                    DiaryEntryCollectionCrossRef(memoryId, collectionId, userId)
                )
            }
    }

    private companion object {
        const val TAG = "CollectionSuggest"
        /** A topic must recur across at least this many memories to earn a collection. */
        const val MIN_MEMORIES = 3
        const val MAX_AUTO_COLLECTIONS = 12
    }
}
