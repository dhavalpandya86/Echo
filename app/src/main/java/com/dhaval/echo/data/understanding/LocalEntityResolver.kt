package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.ExtractedItem
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.LinkRelation
import com.dhaval.echo.data.db.MemoryEntityLink
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.understanding.EntityResolver
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.NormalizedContent
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

private val kindToItem = mapOf(
    EvidenceKind.TASK to ItemKind.TASK,
    EvidenceKind.REMINDER to ItemKind.REMINDER,
    EvidenceKind.DECISION to ItemKind.DECISION
)

private fun normalize(name: String): String =
    name.trim().lowercase().replace(Regex("""\s+"""), " ").trim('.', ',', '!', '?', '\'', '"')

/**
 * Which claims become the memory's item rows, after reconciling analyzers that
 * describe one commitment from different angles.
 *
 * The task analyzer claims the obligation ("Send him the budget file tomorrow")
 * and already resolves the sentence's date onto it. The reminder analyzer claims
 * that same sentence's time anchor. Both are correct in isolation, so left alone
 * a single commitment surfaces as two items — the real one plus an echo whose
 * only content is the time. When a reminder lands on an instant a task already
 * covers, the task is strictly more informative: keep it, drop the echo.
 *
 * Reconciled here rather than inside an analyzer so the specialists stay
 * independent of each other, and so the rule also covers TASK/REMINDER pairs
 * produced by the Claude suite.
 *
 * Pure and file-scoped so it can be tested without standing up a Room DAO.
 */
internal fun reconcileItemEvidence(evidence: List<Evidence>): List<Evidence> {
    val taskDueTimes = evidence
        .filter { it.kind == EvidenceKind.TASK }
        .mapNotNull { it.dueAtMillis }
        .toSet()

    return evidence
        .filter { it.kind in kindToItem }
        // A reminder with no time of its own is never an echo of a task.
        .filterNot { it.kind == EvidenceKind.REMINDER && it.dueAtMillis in taskDueTimes }
        .distinctBy { it.kind to normalize(it.value) }
}

/**
 * Resolver v1 (Stage 5): identity by normalized name + aliases.
 *
 * For each entity-forming piece of evidence: does this entity already exist
 * for this user? Match → link to the existing node (never duplicate).
 * Miss → create the node. Claims (tasks/reminders/mood/decisions) become
 * extracted items. The whole write is idempotent per memory: re-processing
 * replaces that memory's previous conclusions but never touches other
 * memories' links.
 *
 * v2 (MU-4) adds embedding-based fuzzy matching ("Rajesh" ≈ "Raj"),
 * alias learning, and Stage-6 graph expansion.
 */
class LocalEntityResolver @Inject constructor(
    private val dao: UnderstandingDao,
    private val graphMaintainer: EntityGraphMaintainer
) : EntityResolver {

    private val kindToType = mapOf(
        EvidenceKind.PERSON to EntityType.PERSON,
        EvidenceKind.PROJECT to EntityType.PROJECT,
        EvidenceKind.TOPIC to EntityType.TOPIC,
        EvidenceKind.PLACE to EntityType.PLACE,
        EvidenceKind.ORG to EntityType.ORG,
        EvidenceKind.PRODUCT to EntityType.PRODUCT,
        // Phase B: a feeling is a recurring identity, so it joins the graph.
        EvidenceKind.MOOD to EntityType.FEELING
    )

    private val kindToRelation = mapOf(
        EvidenceKind.PERSON to LinkRelation.MENTIONS,
        EvidenceKind.PROJECT to LinkRelation.BELONGS_TO,
        EvidenceKind.TOPIC to LinkRelation.DISCUSSES,
        EvidenceKind.PLACE to LinkRelation.LOCATED_AT,
        EvidenceKind.ORG to LinkRelation.INVOLVES,
        EvidenceKind.PRODUCT to LinkRelation.INVOLVES,
        EvidenceKind.MOOD to LinkRelation.FELT
    )

    override suspend fun resolve(content: NormalizedContent, evidence: List<Evidence>) {
        val now = LocalDateTime.now()

        // Same fact claimed twice (e.g. heuristics + known-entity recall):
        // keep the strongest claim, don't double-link.
        val entityEvidence = evidence
            .filter { it.kind in kindToType }
            .groupBy { it.kind to normalize(it.value) }
            .map { (_, claims) -> claims.maxBy { it.confidence } }

        val links = mutableListOf<MemoryEntityLink>()
        val touchedEntityIds = mutableSetOf<String>()

        for (ev in entityEvidence) {
            val type = kindToType.getValue(ev.kind)
            val entity = findExisting(content.userId, type, ev.value)
                ?: EntityNode(
                    id = UUID.randomUUID().toString(),
                    userId = content.userId,
                    type = type,
                    name = ev.value,
                    normalizedName = normalize(ev.value),
                    firstSeenAt = now,
                    lastSeenAt = now
                ).also {
                    dao.insertEntity(it)
                    Log.d(TAG, "New ${type.lowercase()} entity: ${ev.value}")
                }

            touchedEntityIds += entity.id
            links += MemoryEntityLink(
                id = UUID.randomUUID().toString(),
                memoryId = content.memoryId,
                entityId = entity.id,
                relation = kindToRelation.getValue(ev.kind),
                confidence = ev.confidence,
                evidence = ev.evidenceText,
                inferred = false,
                createdAt = now
            )
        }

        val items = reconcileItemEvidence(evidence)
            .map { ev ->
                ExtractedItem(
                    id = UUID.randomUUID().toString(),
                    memoryId = content.memoryId,
                    userId = content.userId,
                    kind = kindToItem.getValue(ev.kind),
                    value = ev.value,
                    dueAtMillis = ev.dueAtMillis,
                    confidence = ev.confidence,
                    evidence = ev.evidenceText,
                    status = ItemStatus.OPEN,
                    createdAt = now
                )
            }

        // Stage 6: memory expansion. Entities the user didn't name here but which
        // strongly co-occur with the ones they did are added as *inferred* links —
        // marked, low-confidence, and never affecting identity or memoryCount.
        val inferredLinks = expandByCoOccurrence(content, touchedEntityIds, now)
        val allLinks = links + inferredLinks

        dao.replaceMemoryUnderstanding(content.memoryId, allLinks, items)
        (touchedEntityIds + inferredLinks.map { it.entityId }).forEach {
            dao.refreshEntityStats(it, now)
        }

        // Phase A: keep the weighted entity↔entity graph in sync. This memory may
        // have created new co-occurrences among the entities it named, so rebuild
        // their edges (and their neighbours', to keep the symmetric edge weights
        // exact). Bounded by the local neighbourhood — no full-graph scan.
        graphMaintainer.rebuildEdgesFor(content.userId, touchedEntityIds, now)

        Log.i(
            TAG,
            "Resolved memory ${content.memoryId}: ${links.size} stated + " +
                "${inferredLinks.size} inferred links (${touchedEntityIds.size} entities), ${items.size} items"
        )
    }

    /**
     * Stage-6 traversal: for each stated entity, pull entities that co-occur with
     * it in ≥ [MIN_SHARED] past memories and link them to this memory as inferred.
     * Conservative on purpose — capped count and confidence — so enrichment never
     * drowns out what the user actually said.
     */
    private suspend fun expandByCoOccurrence(
        content: NormalizedContent,
        statedEntityIds: Set<String>,
        now: LocalDateTime
    ): List<MemoryEntityLink> {
        if (statedEntityIds.isEmpty()) return emptyList()
        val inferred = mutableListOf<MemoryEntityLink>()
        val seen = statedEntityIds.toMutableSet()
        outer@ for (eid in statedEntityIds) {
            for (co in dao.coOccurringEntities(eid, content.memoryId, MIN_SHARED)) {
                if (co.entityId in seen) continue
                seen += co.entityId
                inferred += MemoryEntityLink(
                    id = UUID.randomUUID().toString(),
                    memoryId = content.memoryId,
                    entityId = co.entityId,
                    relation = LinkRelation.DISCUSSES,
                    confidence = (0.3f + 0.05f * co.shared).coerceAtMost(0.5f),
                    evidence = "Inferred — co-occurs with a mentioned entity across ${co.shared} memories",
                    inferred = true,
                    createdAt = now
                )
                if (inferred.size >= MAX_INFERRED) break@outer
            }
        }
        return inferred
    }

    private suspend fun findExisting(userId: String, type: String, name: String): EntityNode? {
        val needle = normalize(name)
        return dao.getEntitiesByType(userId, type).firstOrNull { entity ->
            entity.normalizedName == needle ||
                entity.aliases.any { normalize(it) == needle }
        }
    }

    private companion object {
        const val TAG = "EntityResolver"
        const val MIN_SHARED = 2      // co-occur in ≥2 memories before inferring
        const val MAX_INFERRED = 3    // never flood a memory with guesses
    }
}
