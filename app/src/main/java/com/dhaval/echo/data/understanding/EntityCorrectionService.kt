package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.data.db.EntityNode
import com.dhaval.echo.data.db.UnderstandingDao
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * The corrections loop (Phase B): Echo makes its best guess, but the user has the
 * final say over their own memory. This is where they fix it — rename an entity,
 * teach it an alias, merge two that are really one, or archive noise.
 *
 * Every operation keeps the Phase-A graph and the denormalized counts consistent,
 * and every operation is safe to repeat. Fuzzy identity was deliberately left to
 * the user (MU-4 calibration showed no safe automatic threshold) — this is that
 * user-confirmed path.
 */
class EntityCorrectionService @Inject constructor(
    private val dao: UnderstandingDao,
    private val graphMaintainer: EntityGraphMaintainer
) {

    /** Rename an entity's display name (and its match key), keeping the old name as an alias. */
    suspend fun rename(entityId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val entity = dao.getEntityById(entityId) ?: return
        if (trimmed == entity.name) return

        val aliases = (entity.aliases + entity.name)
            .distinctBy { normalize(it) }
            .filter { normalize(it) != normalize(trimmed) }
        dao.updateEntity(
            entity.copy(
                name = trimmed,
                normalizedName = normalize(trimmed),
                aliases = aliases,
                lastSeenAt = LocalDateTime.now()
            )
        )
        Log.i(TAG, "Renamed ${entity.name} → $trimmed")
    }

    /** Teach an entity an alternative surface form ("Rajesh" for "Raj"). Idempotent. */
    suspend fun addAlias(entityId: String, alias: String) {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return
        val entity = dao.getEntityById(entityId) ?: return
        val exists = normalize(trimmed) == normalize(entity.name) ||
            entity.aliases.any { normalize(it) == normalize(trimmed) }
        if (exists) return
        dao.updateEntity(entity.copy(aliases = entity.aliases + trimmed))
        Log.i(TAG, "Added alias '$trimmed' to ${entity.name}")
    }

    suspend fun setArchived(entityId: String, archived: Boolean) {
        dao.setArchived(entityId, archived)
        Log.i(TAG, "${if (archived) "Archived" else "Restored"} entity $entityId")
    }

    /**
     * Merge [sourceId] into [targetId]: the two entities were really one. All the
     * source's memories are repointed onto the target (dropping any that would
     * duplicate a link the target already has), the source's name + aliases become
     * the target's aliases, the source is deleted, and the graph + counts are
     * rebuilt around the target. No-op if either side is missing or they're equal.
     *
     * @return the surviving target entity id, or null if the merge couldn't run.
     */
    suspend fun merge(sourceId: String, targetId: String): String? {
        if (sourceId == targetId) return targetId
        val source = dao.getEntityById(sourceId) ?: return null
        val target = dao.getEntityById(targetId) ?: return null

        // The target's neighbours before the merge also need their edges rebuilt,
        // since the source's edges are about to vanish and reappear on the target.
        val affected = mutableSetOf(targetId)
        dao.getRelatedEntitiesOnce(sourceId).forEach { affected += it.entityId }
        dao.getRelatedEntitiesOnce(targetId).forEach { affected += it.entityId }

        // Repoint links: drop the ones the target already covers, move the rest.
        dao.deleteRedundantLinksBeforeMerge(sourceId, targetId)
        dao.repointLinks(sourceId, targetId)

        // Fold the source's surface forms into the target's aliases.
        val mergedAliases = (target.aliases + source.name + source.aliases)
            .distinctBy { normalize(it) }
            .filter { normalize(it) != normalize(target.name) }
        dao.updateEntity(
            target.copy(
                aliases = mergedAliases,
                firstSeenAt = minOf(target.firstSeenAt, source.firstSeenAt),
                lastSeenAt = maxOf(target.lastSeenAt, source.lastSeenAt)
            )
        )

        // Delete the source (CASCADE clears its now-orphaned edges), then refresh
        // counts and rebuild the graph around everything that was touched.
        dao.deleteEntity(sourceId)
        affected.remove(sourceId)

        val now = LocalDateTime.now()
        dao.refreshEntityStats(targetId, now)
        graphMaintainer.rebuildEdgesFor(target.userId, affected, now)
        Log.i(TAG, "Merged ${source.name} into ${target.name}")
        return targetId
    }

    /** Same normalisation the resolver uses, so corrections and resolution agree. */
    private fun normalize(name: String): String =
        name.trim().lowercase().replace(Regex("""\s+"""), " ").trim('.', ',', '!', '?', '\'', '"')

    private companion object {
        const val TAG = "EntityCorrection"
    }
}
