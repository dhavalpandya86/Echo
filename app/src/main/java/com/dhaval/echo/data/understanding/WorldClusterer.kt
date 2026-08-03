package com.dhaval.echo.data.understanding

import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.EntityRelationship

/**
 * A discovered World: a densely-connected cluster of entities in the graph — the
 * distinct parts of a life ("Work", "the Goa trip", "family"), found rather than
 * declared. Title and identity come from the cluster's most-connected entities.
 */
data class EntityCluster(
    val seedEntityId: String,
    val entityIds: List<String>,
    val title: String
)

/**
 * Finds Worlds in the entity graph (Phase C) using weighted **label propagation**:
 * every node starts as its own community, then repeatedly adopts the community its
 * edges pull hardest toward. It converges to natural clusters without needing to
 * know how many there are — and it's cheap and deterministic enough to run
 * on-device over a personal-scale graph.
 *
 * Pure and Android-free so it's trivially testable.
 */
class WorldClusterer @javax.inject.Inject constructor() {

    /**
     * @param minClusterSize a World needs at least this many entities — a lone
     *   node isn't a part of a life worth naming.
     */
    fun cluster(
        entities: List<EntityNode>,
        edges: List<EntityRelationship>,
        minClusterSize: Int = 2,
        maxIterations: Int = 10
    ): List<EntityCluster> {
        if (entities.isEmpty()) return emptyList()

        val ids = entities.map { it.id }
        val nameById = entities.associate { it.id to it.name }
        val order = ids.sorted() // fixed order → deterministic result

        // Weighted, symmetric adjacency. Edges are already stored both ways, but
        // union guarantees symmetry even if one direction is missing.
        val adjacency: Map<String, Map<String, Int>> = buildMap {
            for (id in ids) put(id, mutableMapOf())
            for (e in edges) {
                val a = get(e.sourceEntityId) as? MutableMap ?: continue
                val b = get(e.targetEntityId) as? MutableMap ?: continue
                a[e.targetEntityId] = maxOf(a[e.targetEntityId] ?: 0, e.weight)
                b[e.sourceEntityId] = maxOf(b[e.sourceEntityId] ?: 0, e.weight)
            }
        }

        // Label propagation.
        val label = ids.associateWith { it }.toMutableMap()
        repeat(maxIterations) {
            var changed = false
            for (id in order) {
                val neighbours = adjacency[id].orEmpty()
                if (neighbours.isEmpty()) continue
                // Sum edge weight per neighbouring label; pick the strongest,
                // breaking ties toward the smallest label id for determinism.
                val weightByLabel = HashMap<String, Int>()
                for ((nbr, w) in neighbours) {
                    val l = label.getValue(nbr)
                    weightByLabel[l] = (weightByLabel[l] ?: 0) + w
                }
                val best = weightByLabel.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key
                if (best != label.getValue(id)) {
                    label[id] = best
                    changed = true
                }
            }
            if (!changed) return@repeat
        }

        // Group by final label → clusters.
        return label.entries
            .groupBy({ it.value }, { it.key })
            .values
            .filter { it.size >= minClusterSize }
            .map { members ->
                // Seed = the most-connected member (weighted degree), tie → name.
                val seed = members.maxWith(
                    compareBy<String>({ weightedDegree(it, adjacency) }, { nameById[it] ?: it })
                )
                EntityCluster(
                    seedEntityId = seed,
                    entityIds = members,
                    title = titleFor(members, adjacency, nameById)
                )
            }
            // Biggest, richest Worlds first.
            .sortedByDescending { it.entityIds.size }
    }

    private fun weightedDegree(id: String, adjacency: Map<String, Map<String, Int>>): Int =
        adjacency[id].orEmpty().values.sum()

    /** Name a World after its up-to-three most-connected entities. */
    private fun titleFor(
        members: List<String>,
        adjacency: Map<String, Map<String, Int>>,
        nameById: Map<String, String>
    ): String = members
        .sortedWith(compareByDescending<String> { weightedDegree(it, adjacency) }.thenBy { nameById[it] ?: it })
        .take(3)
        .mapNotNull { nameById[it] }
        .joinToString(" · ")
}
