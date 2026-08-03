package com.dhaval.echo.data.reflection

import android.util.Log
import com.dhaval.echo.data.db.DiaryEntry
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.db.ItemKind
import com.dhaval.echo.data.db.ItemStatus
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.data.db.WindowEntityView
import com.dhaval.echo.domain.ai.Citation
import com.dhaval.echo.domain.reflection.CommitmentBrief
import com.dhaval.echo.domain.reflection.NamedCount
import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionIntent
import com.dhaval.echo.domain.reflection.ReflectionRetriever
import com.dhaval.echo.domain.reflection.ReflectionWindow
import com.dhaval.echo.domain.reflection.Theme
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stages 2 and 3: gather evidence from the graph, then group it into themes.
 *
 * Retrieval here is not semantic search. Semantic similarity answers "which
 * memories resemble this sentence", which is the wrong question for reflection —
 * "what did I focus on this week" has no sentence to resemble. It is answered by
 * *everything in the window*, counted and grouped.
 *
 * Clustering is the stage that makes the difference. Twenty memories summarised
 * one by one produce twenty restatements; the same twenty grouped into "Family
 * (5)" and "Echo (12)" produce a thought. The CATEGORY facet already assigns
 * that grouping during extraction, so this reads a conclusion the pipeline
 * reached rather than inventing one at question time.
 */
@Singleton
class GraphReflectionRetriever @Inject constructor(
    private val diaryEntryDao: DiaryEntryDao,
    private val understandingDao: UnderstandingDao
) : ReflectionRetriever {

    override suspend fun brief(
        question: String,
        intent: ReflectionIntent,
        userId: String
    ): ReflectionBrief {
        val window = KeywordIntentClassifier.windowFor(question, intent)
        val entries = entriesSince(userId, window.since)

        if (entries.isEmpty()) {
            return ReflectionBrief(question, intent, window, memoryCount = 0)
        }

        val entryIds = entries.map { it.id }.toSet()
        val links = understandingDao.linkedEntitiesSince(userId, window.since)
        val items = understandingDao.itemsSince(userId, window.since)

        // Stage 3 — cluster. Themes come from the CATEGORY facet, with the
        // entities inside each theme attached so the narrator can say what a
        // theme is *made of*, not just how big it is.
        val themes = clusterIntoThemes(entries, items, links)

        // The same clustering over the previous window, which is what lets a
        // reflection say "less than before" instead of only "this happened".
        val previous = runCatching { previousThemes(userId, window) }
            .onFailure { Log.w(TAG, "Could not build comparison window", it) }
            .getOrDefault(emptyList())

        return ReflectionBrief(
            question = question,
            intent = intent,
            window = window,
            memoryCount = entries.size,
            themes = themes,
            people = counts(links, EntityType.PERSON),
            projects = counts(links, EntityType.PROJECT) + counts(links, EntityType.TOPIC),
            activities = counts(links, EntityType.ACTIVITY),
            moods = counts(links, EntityType.FEELING) +
                counts(items.filter { it.kind == ItemKind.MOOD }.map { it.value }),
            openCommitments = commitments(items, entryIds),
            previousThemes = previous,
            sources = sources(entries, themes)
        )
    }

    private suspend fun entriesSince(userId: String, since: LocalDateTime): List<DiaryEntry> =
        diaryEntryDao.getAllEntries(userId).first()
            .filter { !it.deleted && it.createdAt >= since }

    /**
     * Group the window's memories into named themes.
     *
     * A memory with a CATEGORY facet joins that theme. One without falls back to
     * its strongest project or topic, and failing that is left out rather than
     * dumped into a catch-all — a theme called "Other" tells the user nothing
     * and dilutes the ones that mean something.
     */
    private fun clusterIntoThemes(
        entries: List<DiaryEntry>,
        items: List<com.dhaval.echo.data.db.ExtractedItem>,
        links: List<WindowEntityView>
    ): List<Theme> {
        val categoryByMemory = items
            .filter { it.kind == ItemKind.CATEGORY }
            .associate { it.memoryId to it.value }

        // A memory joins the theme its CATEGORY facet named. One without a
        // category is left out rather than swept into "Other": a catch-all
        // theme tells the user nothing and dilutes the ones that mean something.
        val grouped = entries
            .mapNotNull { entry -> categoryByMemory[entry.id]?.let { it to entry.id } }
            .groupBy({ it.first }, { it.second })

        if (grouped.isEmpty()) return emptyList()

        val linksByMemory = links.groupBy { it.memoryId }

        return grouped
            .map { (name, memoryIds) ->
                // Which entities appear inside this theme — what turns
                // "Family (5)" into "Family, mostly Prabir and school".
                val inside = memoryIds.flatMap { linksByMemory[it].orEmpty() }
                Theme(
                    name = name,
                    memoryCount = memoryIds.size,
                    entities = counts(inside).take(MAX_ENTITIES_PER_THEME),
                    memoryIds = memoryIds
                )
            }
            .sortedByDescending { it.memoryCount }
            .take(MAX_THEMES)
    }

    private suspend fun previousThemes(userId: String, window: ReflectionWindow): List<Theme> {
        val prev = window.previous()
        val prevEntries = diaryEntryDao.getAllEntries(userId).first()
            .filter { !it.deleted && it.createdAt >= prev.since && it.createdAt < window.since }
        if (prevEntries.isEmpty()) return emptyList()

        val prevItems = understandingDao.itemsSince(userId, prev.since)
            .filter { item -> prevEntries.any { it.id == item.memoryId } }

        return prevItems
            .filter { it.kind == ItemKind.CATEGORY }
            .groupBy { it.value }
            .map { (name, rows) -> Theme(name = name, memoryCount = rows.size) }
            .sortedByDescending { it.memoryCount }
    }

    /**
     * Commitments still open, oldest due first, overdue flagged.
     *
     * Scoped to the window's memories so "what am I forgetting" answers from the
     * period the user asked about rather than from everything they ever said.
     */
    private fun commitments(
        items: List<com.dhaval.echo.data.db.ExtractedItem>,
        entryIds: Set<String>
    ): List<CommitmentBrief> {
        val now = System.currentTimeMillis()
        return items
            .filter { it.memoryId in entryIds }
            .filter { it.kind == ItemKind.TASK || it.kind == ItemKind.REMINDER }
            .filter { it.status == ItemStatus.OPEN }
            .distinctBy { it.value.lowercase() }
            .map {
                CommitmentBrief(
                    memoryId = it.memoryId,
                    text = it.value,
                    dueAtMillis = it.dueAtMillis,
                    isOverdue = it.dueAtMillis != null && it.dueAtMillis < now
                )
            }
            .sortedWith(compareByDescending<CommitmentBrief> { it.isOverdue }
                .thenBy { it.dueAtMillis ?: Long.MAX_VALUE })
            .take(MAX_COMMITMENTS)
    }

    private fun counts(links: List<WindowEntityView>, type: String): List<NamedCount> =
        links.filter { it.type == type }
            .groupBy { it.name }
            .map { (name, rows) -> NamedCount(name, rows.size, type) }
            .sortedByDescending { it.count }
            .take(MAX_NAMES)

    private fun counts(links: List<WindowEntityView>): List<NamedCount> =
        links.groupBy { it.name }
            .map { (name, rows) -> NamedCount(name, rows.size, rows.first().type) }
            .sortedByDescending { it.count }

    @JvmName("countsOfStrings")
    private fun counts(values: List<String>): List<NamedCount> =
        values.groupBy { it }
            .map { (name, rows) -> NamedCount(name, rows.size) }
            .sortedByDescending { it.count }

    /**
     * Stage 6's raw material: the memories a reflection actually rests on.
     *
     * Titles and dates only — never a snippet of transcript. Dumping text under
     * a reflection is how the previous implementation ended up showing its own
     * prompt; a source is a way back to a memory, not a preview of it.
     */
    private fun sources(entries: List<DiaryEntry>, themes: List<Theme>): List<Citation> {
        val themed = themes.flatMap { it.memoryIds }.toSet()
        val ordered = entries.sortedByDescending { it.createdAt }
        val preferred = ordered.filter { it.id in themed }.ifEmpty { ordered }
        return preferred.take(MAX_SOURCES).map {
            Citation(
                memoryId = it.id,
                title = it.title,
                date = it.createdAt.format(DATE),
                snippet = null
            )
        }
    }

    private companion object {
        const val TAG = "ReflectionRetriever"
        const val MAX_THEMES = 5
        const val MAX_ENTITIES_PER_THEME = 4
        const val MAX_NAMES = 8
        const val MAX_COMMITMENTS = 6
        const val MAX_SOURCES = 5
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
    }
}
