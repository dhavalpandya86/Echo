package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.understanding.EntityContext
import com.dhaval.echo.domain.understanding.Enrichment
import com.dhaval.echo.domain.understanding.NormalizedContent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 4 (ENRICH): looks up everything this memory named, and reports what
 * Echo already knew about it.
 *
 * This is the difference between an extraction pipeline and a memory system.
 * Grounding produces the string "Prabir". On its own that is a token, and a
 * model handed it can only reason about the sentence it appeared in. Handed
 * *Prabir — a person in 23 memories since January, who turns up alongside
 * Swimming and School, with one open commitment* the same model is doing
 * something categorically different: reasoning about a life it has been told
 * about.
 *
 * Everything gathered here is **history, not inference** — counts, first and
 * last seen, real co-occurrences, actually-open commitments. The enricher never
 * concludes anything. It is the interpreting stage's job to decide what the
 * history means, and keeping that line sharp is what stops a guess made in one
 * memory from hardening into a fact in the next.
 *
 * Reads only Layer A (the immutable per-memory links) and the derived
 * neighbour view. It writes nothing.
 */
@Singleton
class GraphEnricher @Inject constructor(
    private val dao: UnderstandingDao
) {

    /**
     * Build the life context for a memory whose grounding has already been
     * persisted.
     *
     * Bounded on purpose: only the entities this memory actually named, and only
     * their strongest few neighbours. An unbounded traversal would put most of
     * the user's graph into a prompt, which costs latency, drowns the model's
     * attention, and makes every memory look related to every other.
     */
    suspend fun enrich(content: NormalizedContent): Enrichment {
        val stated = dao.getLinkedEntitiesOnce(content.memoryId).filter { !it.inferred }
        if (stated.isEmpty()) return Enrichment()

        val openByEntity = openCommitmentsByEntity(content)

        val contexts = stated.take(MAX_ENTITIES).mapNotNull { link ->
            runCatching {
                val entity = dao.getEntityById(link.entityId) ?: return@runCatching null

                // memoryCount includes this memory, which has just been written.
                // Subtracting it makes the number mean "what I knew beforehand" —
                // the honest framing when the point is prior familiarity.
                val priorCount = (entity.memoryCount - 1).coerceAtLeast(0)

                EntityContext(
                    entityId = entity.id,
                    name = entity.name,
                    type = entity.type,
                    memoryCount = priorCount,
                    firstSeenAt = entity.firstSeenAt,
                    lastSeenAt = entity.lastSeenAt,
                    relatedNames = dao.getRelatedEntitiesOnce(entity.id)
                        .take(MAX_NEIGHBOURS)
                        .map { it.name },
                    openCommitments = openByEntity[entity.id].orEmpty().take(MAX_COMMITMENTS)
                )
            }.onFailure {
                Log.w(TAG, "Could not enrich entity ${link.entityId}", it)
            }.getOrNull()
        }

        Log.d(TAG, "Enriched ${contexts.size} entities for ${content.memoryId}")
        return Enrichment(contexts)
    }

    /**
     * Open commitments grouped by the entity they involve.
     *
     * An entity's commitments are the tasks on *other* memories that also named
     * it — "you already owe Prabir a pair of goggles" is exactly the kind of
     * thing that should stop Echo from filing a second identical one. This
     * memory's own items are excluded; they are not prior knowledge.
     */
    private suspend fun openCommitmentsByEntity(content: NormalizedContent): Map<String, List<String>> {
        val open = runCatching { dao.getOpenActionablesOnce(content.userId) }
            .onFailure { Log.w(TAG, "Could not read open commitments", it) }
            .getOrDefault(emptyList())
            .filter { it.memoryId != content.memoryId }
            .filter { it.kind == ItemKind.TASK || it.kind == ItemKind.REMINDER }
            .filter { it.status == ItemStatus.OPEN }
            .take(MAX_COMMITMENT_SCAN)

        if (open.isEmpty()) return emptyMap()

        val byEntity = mutableMapOf<String, MutableList<String>>()
        for (item in open) {
            for (link in runCatching { dao.getLinkedEntitiesOnce(item.memoryId) }.getOrDefault(emptyList())) {
                if (link.inferred) continue
                byEntity.getOrPut(link.entityId) { mutableListOf() }.add(item.value)
            }
        }
        return byEntity
    }

    private companion object {
        const val TAG = "GraphEnricher"

        /** Enough to describe the memory; few enough to stay a prompt, not a dump. */
        const val MAX_ENTITIES = 8
        const val MAX_NEIGHBOURS = 4
        const val MAX_COMMITMENTS = 3

        /** Cap the commitment scan so enrichment stays O(1) in corpus size. */
        const val MAX_COMMITMENT_SCAN = 40
    }
}
