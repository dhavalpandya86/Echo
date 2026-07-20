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

/** A neighbour in the entity graph: the connected entity + how strong the edge is. */
data class RelatedEntityView(
    val entityId: String,
    val name: String,
    val type: String,
    val weight: Int,
    val confidence: Float
)

/** A connection Echo inferred (Stage-6) — a memory linked to an entity it didn't name. */
data class InferredConnectionView(
    val memoryId: String,
    val memoryTitle: String,
    val entityId: String,
    val entityName: String,
    val entityType: String
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

    @Query("SELECT * FROM entities WHERE userId = :userId AND archived = 0 ORDER BY memoryCount DESC")
    fun getAllEntities(userId: String): Flow<List<EntityNode>>

    /** Entities whose name or alias matches a query — for hybrid recall. */
    @Query(
        """SELECT * FROM entities
           WHERE userId = :userId AND archived = 0
             AND (LOWER(name) LIKE '%' || LOWER(:query) || '%'
                  OR LOWER(aliases) LIKE '%' || LOWER(:query) || '%')
           ORDER BY memoryCount DESC LIMIT 10"""
    )
    suspend fun searchEntities(userId: String, query: String): List<EntityNode>

    /** Same-type candidates for a merge picker, excluding one entity and archived ones. */
    @Query(
        """SELECT * FROM entities
           WHERE userId = :userId AND type = :type AND archived = 0 AND id <> :excludeId
           ORDER BY memoryCount DESC"""
    )
    suspend fun getMergeCandidates(userId: String, type: String, excludeId: String): List<EntityNode>

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

    // ── Entity Graph edges (Phase A — the weighted node↔node graph) ──

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelationships(edges: List<EntityRelationship>)

    /** Drop every edge originating at an entity, so it can be rebuilt idempotently. */
    @Query("DELETE FROM entity_relationships WHERE sourceEntityId = :entityId")
    suspend fun deleteRelationshipsFrom(entityId: String)

    /**
     * Replace all outgoing edges of one entity in a single transaction. Called by
     * the resolver after a memory touches this entity: its neighbourhood is small,
     * so a full delete+reinsert keeps weights exactly in sync with co-occurrence.
     */
    @Transaction
    suspend fun rebuildRelationshipsFrom(entityId: String, edges: List<EntityRelationship>) {
        deleteRelationshipsFrom(entityId)
        if (edges.isNotEmpty()) insertRelationships(edges)
    }

    /** The entities connected to [entityId], strongest edge first — for related-to UI. */
    @Query(
        """SELECT r.targetEntityId AS entityId, e.name AS name, e.type AS type,
                  r.weight AS weight, r.confidence AS confidence
           FROM entity_relationships r
           JOIN entities e ON e.id = r.targetEntityId
           WHERE r.sourceEntityId = :entityId AND e.archived = 0
           ORDER BY r.weight DESC, e.name ASC"""
    )
    fun getRelatedEntities(entityId: String): Flow<List<RelatedEntityView>>

    /** Same as [getRelatedEntities], one-shot — for traversal/tests/clustering. */
    @Query(
        """SELECT r.targetEntityId AS entityId, e.name AS name, e.type AS type,
                  r.weight AS weight, r.confidence AS confidence
           FROM entity_relationships r
           JOIN entities e ON e.id = r.targetEntityId
           WHERE r.sourceEntityId = :entityId AND e.archived = 0
           ORDER BY r.weight DESC, e.name ASC"""
    )
    suspend fun getRelatedEntitiesOnce(entityId: String): List<RelatedEntityView>

    // ── Worlds clustering (Phase C) ──────────────────────────────────

    /** Every active entity for a user — the nodes to cluster into Worlds. */
    @Query("SELECT * FROM entities WHERE userId = :userId AND archived = 0")
    suspend fun getActiveEntities(userId: String): List<EntityNode>

    /** Every edge for a user — the graph to cluster. */
    @Query("SELECT * FROM entity_relationships WHERE userId = :userId")
    suspend fun getAllRelationshipsForUser(userId: String): List<EntityRelationship>

    /** How many distinct memories a set of entities spans (stated links only). */
    @Query(
        """SELECT COUNT(DISTINCT memoryId) FROM memory_entity_links
           WHERE entityId IN (:entityIds) AND inferred = 0"""
    )
    suspend fun countMemoriesForEntities(entityIds: List<String>): Int

    /** The memories a set of entities spans, newest first — for a World page. */
    @Query(
        """SELECT DISTINCT d.* FROM diary_entries d
           JOIN memory_entity_links l ON l.memoryId = d.id
           WHERE l.entityId IN (:entityIds) AND l.inferred = 0
           ORDER BY d.createdAt DESC"""
    )
    suspend fun getMemoriesForEntities(entityIds: List<String>): List<DiaryEntry>

    // ── Corrections loop (Phase B) ───────────────────────────────────

    @Query("UPDATE entities SET archived = :archived WHERE id = :entityId")
    suspend fun setArchived(entityId: String, archived: Boolean)

    @Query("DELETE FROM entities WHERE id = :entityId")
    suspend fun deleteEntity(entityId: String)

    /**
     * Move every link from one entity to another, dropping links that would
     * duplicate one the target already has (same memory). Used by merge — after
     * this the source entity has no links and can be deleted.
     */
    @Query(
        """DELETE FROM memory_entity_links
           WHERE entityId = :fromId
             AND memoryId IN (SELECT memoryId FROM memory_entity_links WHERE entityId = :toId)"""
    )
    suspend fun deleteRedundantLinksBeforeMerge(fromId: String, toId: String)

    @Query("UPDATE memory_entity_links SET entityId = :toId WHERE entityId = :fromId")
    suspend fun repointLinks(fromId: String, toId: String)

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

    /** The most recent connection Echo inferred, for surfacing in Remember. */
    @Query(
        """SELECT d.id AS memoryId, d.title AS memoryTitle,
                  e.id AS entityId, e.name AS entityName, e.type AS entityType
           FROM memory_entity_links l
           JOIN diary_entries d ON d.id = l.memoryId
           JOIN entities e ON e.id = l.entityId
           WHERE l.inferred = 1 AND e.userId = :userId
           ORDER BY l.createdAt DESC
           LIMIT 1"""
    )
    fun recentInferredConnection(userId: String): kotlinx.coroutines.flow.Flow<InferredConnectionView?>

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

    /** Completed commitments, most-recently-created first — for the "Done" view. */
    @Query(
        """SELECT * FROM extracted_items
           WHERE userId = :userId AND kind IN ('TASK','REMINDER') AND status = 'DONE'
           ORDER BY createdAt DESC"""
    )
    fun getCompletedActionables(userId: String): Flow<List<ExtractedItem>>

    @Query("UPDATE extracted_items SET status = :status WHERE id = :itemId")
    suspend fun updateItemStatus(itemId: String, status: String)

    /** Reschedule a commitment (null clears its due date). */
    @Query("UPDATE extracted_items SET dueAtMillis = :dueAtMillis WHERE id = :itemId")
    suspend fun updateItemDue(itemId: String, dueAtMillis: Long?)

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
