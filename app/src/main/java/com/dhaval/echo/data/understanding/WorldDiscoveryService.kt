package com.dhaval.echo.data.understanding

import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.auth.AuthRepository
import javax.inject.Inject

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
            DiscoveredWorld(
                id = "world_${c.seedEntityId}",
                title = c.title,
                seedEntityId = c.seedEntityId,
                entityCount = c.entityIds.size,
                memoryCount = memories
            )
        }
    }
}
