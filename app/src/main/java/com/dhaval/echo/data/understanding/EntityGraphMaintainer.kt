package com.dhaval.echo.data.understanding

import com.dhaval.echo.data.db.EntityRelation
import com.dhaval.echo.data.db.EntityRelationship
import com.dhaval.echo.data.db.UnderstandingDao
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject

/**
 * Keeps the weighted entity↔entity graph (Phase A) consistent. Given a set of
 * "seed" entities whose links just changed — because a memory mentioned them, or
 * a correction repointed links onto them — it rebuilds the edges of those seeds
 * *and their direct neighbours*, because co-occurrence is symmetric and an edge
 * has two endpoints. Bounded to the affected neighbourhood; never a full scan.
 *
 * Edge weight is the number of memories two entities share (stated links only,
 * via [UnderstandingDao.coOccurringEntities] with minShared = 1).
 */
class EntityGraphMaintainer @Inject constructor(
    private val dao: UnderstandingDao
) {
    suspend fun rebuildEdgesFor(userId: String, seedEntityIds: Set<String>, now: LocalDateTime) {
        if (seedEntityIds.isEmpty()) return

        // The seeds plus everyone they now co-occur with — the only entities whose
        // edges could have changed.
        val toRebuild = seedEntityIds.toMutableSet()
        for (eid in seedEntityIds) {
            dao.coOccurringEntities(eid, excludeMemoryId = "", minShared = 1)
                .forEach { toRebuild += it.entityId }
        }

        for (eid in toRebuild) {
            val edges = dao.coOccurringEntities(eid, excludeMemoryId = "", minShared = 1)
                .map { co ->
                    EntityRelationship(
                        id = UUID.randomUUID().toString(),
                        userId = userId,
                        sourceEntityId = eid,
                        targetEntityId = co.entityId,
                        relation = EntityRelation.RELATED_TO,
                        weight = co.shared,
                        confidence = (0.4f + 0.1f * co.shared).coerceAtMost(1f),
                        evidence = "Co-occurs across ${co.shared} " +
                            (if (co.shared == 1) "memory" else "memories"),
                        firstSeenAt = now,
                        lastSeenAt = now
                    )
                }
            dao.rebuildRelationshipsFrom(eid, edges)
        }
    }
}
