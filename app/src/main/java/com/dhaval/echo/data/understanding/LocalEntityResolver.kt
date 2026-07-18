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
    private val dao: UnderstandingDao
) : EntityResolver {

    private val kindToType = mapOf(
        EvidenceKind.PERSON to EntityType.PERSON,
        EvidenceKind.PROJECT to EntityType.PROJECT,
        EvidenceKind.TOPIC to EntityType.TOPIC,
        EvidenceKind.PLACE to EntityType.PLACE,
        EvidenceKind.ORG to EntityType.ORG,
        EvidenceKind.PRODUCT to EntityType.PRODUCT
    )

    private val kindToRelation = mapOf(
        EvidenceKind.PERSON to LinkRelation.MENTIONS,
        EvidenceKind.PROJECT to LinkRelation.BELONGS_TO,
        EvidenceKind.TOPIC to LinkRelation.DISCUSSES,
        EvidenceKind.PLACE to LinkRelation.LOCATED_AT,
        EvidenceKind.ORG to LinkRelation.INVOLVES,
        EvidenceKind.PRODUCT to LinkRelation.INVOLVES
    )

    private val kindToItem = mapOf(
        EvidenceKind.TASK to ItemKind.TASK,
        EvidenceKind.REMINDER to ItemKind.REMINDER,
        EvidenceKind.MOOD to ItemKind.MOOD,
        EvidenceKind.DECISION to ItemKind.DECISION
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

        val items = evidence
            .filter { it.kind in kindToItem }
            .distinctBy { it.kind to normalize(it.value) }
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

        dao.replaceMemoryUnderstanding(content.memoryId, links, items)
        touchedEntityIds.forEach { dao.refreshEntityStats(it, now) }

        Log.i(
            TAG,
            "Resolved memory ${content.memoryId}: ${links.size} entity links " +
                "(${touchedEntityIds.size} entities), ${items.size} items"
        )
    }

    private suspend fun findExisting(userId: String, type: String, name: String): EntityNode? {
        val needle = normalize(name)
        return dao.getEntitiesByType(userId, type).firstOrNull { entity ->
            entity.normalizedName == needle ||
                entity.aliases.any { normalize(it) == needle }
        }
    }

    private fun normalize(name: String): String =
        name.trim().lowercase().replace(Regex("""\s+"""), " ").trim('.', ',', '!', '?', '\'', '"')

    private companion object {
        const val TAG = "EntityResolver"
    }
}
