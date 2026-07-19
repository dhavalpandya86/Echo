package com.dhaval.echo.data.understanding

import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** The full contents of one World — its entities and the memories they span. */
data class WorldDetail(
    val seedEntityId: String,
    val title: String,
    val entities: List<EntityNode>,
    val memories: List<DiaryEntry>
)

/** A World surfaced to the user: a cluster of entities and the memories it spans. */
data class DiscoveredWorld(
    val id: String,
    val title: String,
    val seedEntityId: String,
    val entityCount: Int,
    val memoryCount: Int
)

/**
 * Turns the entity graph into Worlds (Phase C): cluster the graph, then attach the
 * memory span of each cluster. Computed on demand from the live graph — Worlds
 * grow and reshape themselves as memories accumulate, with no manual upkeep.
 */
class WorldDiscoveryService @Inject constructor(
    private val dao: UnderstandingDao,
    private val clusterer: WorldClusterer,
    private val authRepository: AuthRepository
) {
    /** Discovered Worlds, richest first; empty until the graph has clusters. */
    suspend fun discover(minMemoriesPerWorld: Int = 2): List<DiscoveredWorld> {
        val userId = authRepository.getCurrentUser()?.id ?: return emptyList()
        val entities = dao.getActiveEntities(userId)
        if (entities.size < 2) return emptyList()
        val edges = dao.getAllRelationshipsForUser(userId)

        return clusterer.cluster(entities, edges).mapNotNull { c ->
            val memories = dao.countMemoriesForEntities(c.entityIds)
            if (memories < minMemoriesPerWorld) return@mapNotNull null
            worldOf(c, memories)
        }
    }

    /**
     * The Worlds a single memory belongs to (Phase C → memory page): cluster the
     * graph, then keep the clusters that contain any entity this memory names. A
     * memory joins its Worlds automatically, no manual filing. Unlike [discover],
     * this has no minimum-memory floor — if a memory touches a cluster, that
     * cluster is one of its Worlds.
     */
    suspend fun worldsForMemory(memoryId: String): List<DiscoveredWorld> {
        val userId = authRepository.getCurrentUser()?.id ?: return emptyList()
        val memoryEntityIds = dao.getLinkedEntities(memoryId).first()
            .filter { !it.inferred }
            .map { it.entityId }
            .toSet()
        if (memoryEntityIds.isEmpty()) return emptyList()

        val entities = dao.getActiveEntities(userId)
        if (entities.size < 2) return emptyList()
        val edges = dao.getAllRelationshipsForUser(userId)

        return clusterer.cluster(entities, edges)
            .filter { c -> c.entityIds.any { it in memoryEntityIds } }
            .map { c -> worldOf(c, dao.countMemoriesForEntities(c.entityIds)) }
    }

    /**
     * The full contents of one World (its detail page): every entity in the
     * cluster the given seed belongs to, plus the memories they span. Returns null
     * if the seed no longer anchors a cluster (e.g. the graph reshaped).
     */
    suspend fun worldDetail(seedEntityId: String): WorldDetail? {
        val userId = authRepository.getCurrentUser()?.id ?: return null
        val entities = dao.getActiveEntities(userId)
        if (entities.size < 2) return null
        val edges = dao.getAllRelationshipsForUser(userId)

        val cluster = clusterer.cluster(entities, edges)
            .firstOrNull { seedEntityId in it.entityIds } ?: return null
        val members = entities.filter { it.id in cluster.entityIds }
        return WorldDetail(
            seedEntityId = seedEntityId,
            title = cluster.title,
            entities = members,
            memories = dao.getMemoriesForEntities(cluster.entityIds)
        )
    }

    private fun worldOf(c: EntityCluster, memories: Int) = DiscoveredWorld(
        id = "world_${c.seedEntityId}",
        title = c.title,
        seedEntityId = c.seedEntityId,
        entityCount = c.entityIds.size,
        memoryCount = memories
    )
}
