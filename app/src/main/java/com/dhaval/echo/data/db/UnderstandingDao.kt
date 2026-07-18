package com.dhaval.echo.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/** A memory→entity link joined with the entity it points at, for display. */
data class LinkedEntityView(
    val entityId: String,
    val name: String,
    val type: String,
    val relation: String,
    val confidence: Float,
    val inferred: Boolean
)

/** An entity that co-occurs with a query entity, and in how many memories. */
data class CoOccurrence(
    val entityId: String,
    val shared: Int
)

@Dao
interface UnderstandingDao {

    // ── Entity Graph ─────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntity(entity: EntityNode)

    @Update
    suspend fun updateEntity(entity: EntityNode)

    /**
     * All entities of one type for a user. Resolution matches in memory over
     * this set (normalized name + aliases) — at personal-diary scale that is
     * simpler and more flexible than SQL alias matching.
     */
    @Query("SELECT * FROM entities WHERE userId = :userId AND type = :type")
    suspend fun getEntitiesByType(userId: String, type: String): List<EntityNode>

    @Query("SELECT * FROM entities WHERE userId = :userId ORDER BY memoryCount DESC")
    fun getAllEntities(userId: String): Flow<List<EntityNode>>

    @Query("SELECT * FROM entities WHERE id = :id")
    suspend fun getEntityById(id: String): EntityNode?

    @Query("SELECT * FROM entities WHERE id = :id")
    fun observeEntity(id: String): Flow<EntityNode?>

    /** The memories linked to an entity, newest first — for its detail page. */
    @Query(
        """SELECT d.* FROM diary_entries d
           JOIN memory_entity_links l ON l.memoryId = d.id
           WHERE l.entityId = :entityId
           GROUP BY d.id
           ORDER BY d.createdAt DESC"""
    )
    fun getMemoriesForEntity(entityId: String): Flow<List<DiaryEntry>>

    @Query(
        """UPDATE entities SET
             memoryCount = (SELECT COUNT(DISTINCT memoryId) FROM memory_entity_links
                            WHERE entityId = :entityId AND inferred = 0),
             lastSeenAt = :seenAt
           WHERE id = :entityId"""
    )
    suspend fun refreshEntityStats(entityId: String, seenAt: LocalDateTime)

    /**
     * Stage-6 expansion: entities that co-occur with [entityId] across the
     * user's *stated* links, excluding one memory (the one being processed, so
     * it can't reinforce its own inferences) and excluding inferred links (so
     * inferences never beget inferences). Ordered by how many memories share
     * both entities.
     */
    @Query(
        """SELECT l2.entityId AS entityId, COUNT(DISTINCT l1.memoryId) AS shared
           FROM memory_entity_links l1
           JOIN memory_entity_links l2
             ON l1.memoryId = l2.memoryId AND l2.entityId <> :entityId
           WHERE l1.entityId = :entityId
             AND l1.memoryId <> :excludeMemoryId
             AND l1.inferred = 0 AND l2.inferred = 0
           GROUP BY l2.entityId
           HAVING shared >= :minShared
           ORDER BY shared DESC"""
    )
    suspend fun coOccurringEntities(
        entityId: String,
        excludeMemoryId: String,
        minShared: Int
    ): List<CoOccurrence>

    // ── Memory Graph (links) ─────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<MemoryEntityLink>)

    @Query("DELETE FROM memory_entity_links WHERE memoryId = :memoryId")
    suspend fun deleteLinksForMemory(memoryId: String)

    @Query(
        """SELECT l.entityId AS entityId, e.name AS name, e.type AS type,
                  l.relation AS relation, l.confidence AS confidence, l.inferred AS inferred
           FROM memory_entity_links l JOIN entities e ON e.id = l.entityId
           WHERE l.memoryId = :memoryId
           ORDER BY l.inferred ASC, l.confidence DESC"""
    )
    fun getLinkedEntities(memoryId: String): Flow<List<LinkedEntityView>>

    @Query("SELECT * FROM memory_entity_links WHERE entityId = :entityId")
    suspend fun getLinksForEntity(entityId: String): List<MemoryEntityLink>

    // ── Evidence board (extracted items) ─────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ExtractedItem>)

    @Query("DELETE FROM extracted_items WHERE memoryId = :memoryId")
    suspend fun deleteItemsForMemory(memoryId: String)

    @Query("SELECT * FROM extracted_items WHERE memoryId = :memoryId ORDER BY confidence DESC")
    fun getItemsForMemory(memoryId: String): Flow<List<ExtractedItem>>

    @Query(
        """SELECT * FROM extracted_items
           WHERE userId = :userId AND kind IN ('TASK','REMINDER') AND status = 'OPEN'
           ORDER BY dueAtMillis IS NULL, dueAtMillis ASC"""
    )
    fun getOpenActionables(userId: String): Flow<List<ExtractedItem>>

    @Query("UPDATE extracted_items SET status = :status WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: String)

    // ── Idempotent re-processing ─────────────────────────────────────

    @Transaction
    suspend fun replaceMemoryUnderstanding(
        memoryId: String,
        links: List<MemoryEntityLink>,
        items: List<ExtractedItem>
    ) {
        deleteLinksForMemory(memoryId)
        deleteItemsForMemory(memoryId)
        if (links.isNotEmpty()) insertLinks(links)
        if (items.isNotEmpty()) insertItems(items)
    }
}
