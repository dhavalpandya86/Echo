package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.data.db.DiaryEntryDao
import com.dhaval.echo.data.db.IntelligenceDao
import com.dhaval.echo.data.db.UnderstandingDao
import com.dhaval.echo.domain.understanding.EntityResolver
import com.dhaval.echo.domain.understanding.Enrichment
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.ExtractorOutcome
import com.dhaval.echo.domain.understanding.ExtractorOutput
import com.dhaval.echo.domain.understanding.ExtractorRegistry
import com.dhaval.echo.domain.understanding.ExtractorRunRecord
import com.dhaval.echo.domain.understanding.ExtractorSpec
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.Stage
import com.dhaval.echo.domain.understanding.TextTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks the registry, one question at a time, persisting each answer as it lands.
 *
 * The shape of this loop is the feature. Understanding a memory well takes ~22
 * model calls, which is far too slow to make anyone wait for — so nobody waits.
 * The transcript is already on screen; this runs behind it and the detail screen
 * fills in as answers arrive.
 *
 * Three properties fall out of persisting per extractor rather than per memory:
 *  - **Progress is visible.** Each answer is a database write the UI observes.
 *  - **Progress is durable.** A run row settles with its results in one
 *    transaction, so a process death costs one question, not the memory.
 *  - **Failure is contained.** A question that throws is recorded and skipped
 *    past; the other 21 still get asked.
 *
 * The one ordering rule: answers flow forwards. Each extractor is handed
 * everything settled before it, which is what lets interpretation reason over
 * facts and the summary be written last.
 */
@Singleton
class StagedUnderstandingRunner @Inject constructor(
    private val factory: ExtractorFactory,
    private val enricher: GraphEnricher,
    private val resolver: EntityResolver,
    private val understandingDao: UnderstandingDao,
    private val intelligenceDao: IntelligenceDao,
    private val diaryEntryDao: DiaryEntryDao
) {

    /**
     * Ask every outstanding question about this memory.
     *
     * @param stages which stages to run. Splitting PREPARE from the rest is what
     *   lets the capture path show readable text before the slow work begins.
     * @param force re-ask questions that already settled — the backfill path.
     */
    suspend fun run(
        content: NormalizedContent,
        stages: Set<Stage> = Stage.entries.toSet(),
        force: Boolean = false
    ) {
        val settled = if (force) emptySet() else understandingDao
            .getSettledExtractorIds(content.memoryId)
            .toSet()

        // Earlier answers, carried forward. Seeded from what previous passes
        // already persisted so a resumed run still gives INTERPRET its facts.
        val answers = mutableListOf<Evidence>()
        if (settled.isNotEmpty()) answers += recoverPriorEvidence(content.memoryId)

        // Text corrected by an earlier pass beats the raw transcript for
        // everything downstream — that is the point of running cleanup first.
        var working = content
        var enrichment = Enrichment()

        // Stage by stage, so the two barriers in the pipeline are explicit:
        // grounding must finish before enrichment can look anything up, and
        // enrichment must finish before interpretation can use it.
        for (stage in Stage.entries) {
            if (stage !in stages) continue

            if (stage == Stage.ENRICH) {
                // Not an extractor stage — it makes no claim and writes nothing.
                // It reads back what grounding just persisted and turns bare
                // names into the life context the interpreter reasons over.
                enrichment = runCatching { enricher.enrich(working) }
                    .onFailure { Log.e(TAG, "Enrichment failed for ${content.memoryId}", it) }
                    .getOrDefault(Enrichment())
                continue
            }

            for (spec in ExtractorRegistry.stage(stage)) {
                if (spec.id in settled) {
                    Log.v(TAG, "${spec.id} already settled for ${content.memoryId}")
                    continue
                }
                // A cancelled worker must stop asking questions, not race the next.
                currentCoroutineContext().ensureActive()

                val ctx = ExtractionContext(working, answers.toList(), enrichment)
                val outcome = ask(spec, ctx)

                when (val output = outcome.output) {
                    is ExtractorOutput.Facts -> answers += output.evidence
                    is ExtractorOutput.Text ->
                        if (spec.textTarget == TextTarget.CLEANED_TEXT) {
                            working = working.copy(text = output.value)
                        }
                    else -> Unit
                }
            }
        }

        // Expansion and edge rebuilding reason about everything the memory named
        // together, so they run once the questions are done — not 22 times.
        if (Stage.GROUND in stages) {
            runCatching { resolver.finalizeMemory(content) }
                .onFailure { Log.e(TAG, "Finalize failed for ${content.memoryId}", it) }
        }
    }

    private data class Outcome(val output: ExtractorOutput)

    /**
     * Ask one question and persist whatever comes back — including the fact that
     * it failed, which is itself worth recording.
     */
    private suspend fun ask(spec: ExtractorSpec, ctx: ExtractionContext): Outcome {
        val startedAt = LocalDateTime.now()
        val startedMs = System.currentTimeMillis()

        val extractor = runCatching { factory.create(spec, ctx.content) }
            .onFailure { Log.w(TAG, "Could not build extractor '${spec.id}'", it) }
            .getOrNull()

        if (extractor == null) {
            // Nothing on this device can answer it and it has no rules-based
            // floor. Recorded as skipped so the UI can say so honestly rather
            // than counting it as an answer that found nothing.
            persist(spec, ctx, ExtractorOutput.Empty, record(spec, ExtractorOutcome.SKIPPED, "none", startedAt, startedMs))
            return Outcome(ExtractorOutput.Empty)
        }

        val output = runCatching { extractor.run(ctx) }
            .getOrElse { error ->
                Log.e(TAG, "'${spec.id}' failed for ${ctx.content.memoryId}", error)
                persist(
                    spec, ctx, ExtractorOutput.Empty,
                    record(
                        spec, ExtractorOutcome.FAILED, extractor.engineId, startedAt, startedMs,
                        error = "${error::class.simpleName}: ${error.message}".take(300)
                    )
                )
                return Outcome(ExtractorOutput.Empty)
            }

        val count = (output as? ExtractorOutput.Facts)?.evidence?.size ?: 0
        persist(
            spec, ctx, output,
            record(spec, ExtractorOutcome.COMPLETED, extractor.engineId, startedAt, startedMs, count)
        )
        return Outcome(output)
    }

    /**
     * Text answers are written onto the memory *before* the run row, so a crash
     * between the two leaves the question unsettled and it is simply asked
     * again. The reverse order would record success for text that was never
     * stored.
     */
    private suspend fun persist(
        spec: ExtractorSpec,
        ctx: ExtractionContext,
        output: ExtractorOutput,
        record: ExtractorRunRecord
    ) {
        if (output is ExtractorOutput.Text) {
            runCatching { writeText(spec, ctx, output.value) }
                .onFailure { Log.e(TAG, "Could not store text from '${spec.id}'", it) }
        }
        runCatching { resolver.persistExtractorOutput(ctx.content, record, output) }
            .onFailure { Log.e(TAG, "Could not persist '${spec.id}'", it) }
    }

    private suspend fun writeText(spec: ExtractorSpec, ctx: ExtractionContext, value: String) {
        val memoryId = ctx.content.memoryId
        when (spec.textTarget) {
            TextTarget.CLEANED_TEXT -> intelligenceDao.updateCleanedText(memoryId, value)
            TextTarget.SUMMARY -> intelligenceDao.updateSummary(memoryId, value)
            TextTarget.LANGUAGE -> intelligenceDao.updateLanguage(memoryId, value)
            TextTarget.TITLE -> {
                // Never overwrite a title the user wrote themselves.
                val entry = diaryEntryDao.getEntryById(memoryId)
                if (entry != null && isAutoGeneratedTitle(entry.title)) {
                    intelligenceDao.updateTitle(memoryId, value.trim().trim('"'))
                }
            }
            TextTarget.NONE -> Unit
        }
    }

    /**
     * Rebuild the evidence a resumed run would otherwise have lost.
     *
     * Read back from what earlier extractors persisted rather than re-asking
     * them — cheaper, and it keeps a resumed run's answers identical to an
     * uninterrupted one's.
     */
    private suspend fun recoverPriorEvidence(memoryId: String): List<Evidence> {
        val fromLinks = understandingDao.getLinkedEntitiesOnce(memoryId)
            .filter { !it.inferred }
            .mapNotNull { linked ->
                ENTITY_TYPE_TO_KIND[linked.type]?.let { kind ->
                    Evidence(kind, linked.name, null, linked.confidence)
                }
            }
        val fromItems = understandingDao.getItemsForMemoryOnce(memoryId)
            .mapNotNull { item ->
                ITEM_KIND_TO_KIND[item.kind]?.let { kind ->
                    Evidence(kind, item.value, item.evidence, item.confidence, item.dueAtMillis)
                }
            }
        return fromLinks + fromItems
    }

    private fun record(
        spec: ExtractorSpec,
        outcome: ExtractorOutcome,
        engineId: String,
        startedAt: LocalDateTime,
        startedMs: Long,
        evidenceCount: Int = 0,
        error: String? = null
    ) = ExtractorRunRecord(
        extractorId = spec.id,
        outcome = outcome,
        engineId = engineId,
        startedAt = startedAt,
        completedAt = LocalDateTime.now(),
        latencyMs = System.currentTimeMillis() - startedMs,
        evidenceCount = evidenceCount,
        error = error
    )

    /** True for titles Echo generated itself, which a later answer may improve. */
    private fun isAutoGeneratedTitle(title: String): Boolean =
        title.isBlank() || title == "Untitled" || title == "Untitled Memory" ||
            title == "Memory of the Day" || title.startsWith("Recording ")

    private companion object {
        const val TAG = "UnderstandingRunner"

        val ENTITY_TYPE_TO_KIND = mapOf(
            com.dhaval.echo.data.db.EntityType.PERSON to com.dhaval.echo.domain.understanding.EvidenceKind.PERSON,
            com.dhaval.echo.data.db.EntityType.PROJECT to com.dhaval.echo.domain.understanding.EvidenceKind.PROJECT,
            com.dhaval.echo.data.db.EntityType.TOPIC to com.dhaval.echo.domain.understanding.EvidenceKind.TOPIC,
            com.dhaval.echo.data.db.EntityType.PLACE to com.dhaval.echo.domain.understanding.EvidenceKind.PLACE,
            com.dhaval.echo.data.db.EntityType.ORG to com.dhaval.echo.domain.understanding.EvidenceKind.ORG,
            com.dhaval.echo.data.db.EntityType.PRODUCT to com.dhaval.echo.domain.understanding.EvidenceKind.PRODUCT,
            com.dhaval.echo.data.db.EntityType.ACTIVITY to com.dhaval.echo.domain.understanding.EvidenceKind.ACTIVITY,
            com.dhaval.echo.data.db.EntityType.OBJECT to com.dhaval.echo.domain.understanding.EvidenceKind.OBJECT,
            com.dhaval.echo.data.db.EntityType.FEELING to com.dhaval.echo.domain.understanding.EvidenceKind.MOOD
        )

        val ITEM_KIND_TO_KIND = mapOf(
            com.dhaval.echo.data.db.ItemKind.TASK to com.dhaval.echo.domain.understanding.EvidenceKind.TASK,
            com.dhaval.echo.data.db.ItemKind.REMINDER to com.dhaval.echo.domain.understanding.EvidenceKind.REMINDER,
            com.dhaval.echo.data.db.ItemKind.DECISION to com.dhaval.echo.domain.understanding.EvidenceKind.DECISION,
            com.dhaval.echo.data.db.ItemKind.MOOD to com.dhaval.echo.domain.understanding.EvidenceKind.MOOD,
            com.dhaval.echo.data.db.ItemKind.INTENT to com.dhaval.echo.domain.understanding.EvidenceKind.INTENT,
            com.dhaval.echo.data.db.ItemKind.MEMORY_TYPE to com.dhaval.echo.domain.understanding.EvidenceKind.MEMORY_TYPE,
            com.dhaval.echo.data.db.ItemKind.CATEGORY to com.dhaval.echo.domain.understanding.EvidenceKind.CATEGORY,
            com.dhaval.echo.data.db.ItemKind.PRIORITY to com.dhaval.echo.domain.understanding.EvidenceKind.PRIORITY
        )
    }
}
