package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.domain.understanding.Capability
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.ExtractionEngineProvider
import com.dhaval.echo.domain.understanding.Extractor
import com.dhaval.echo.domain.understanding.ExtractorOutput
import com.dhaval.echo.domain.understanding.ExtractorSpec
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.OutputShape
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A rules-based answer to a question that produces text rather than facts —
 * the cleanup, summary and title floors.
 *
 * These cannot be [MemoryAnalyzer]s (which only speak [com.dhaval.echo.domain.understanding.Evidence]),
 * so they get their own tiny shape. Registered by extractor id.
 */
fun interface TextHeuristic {
    /** Returns null when the rules have nothing better to offer than silence. */
    suspend fun answer(ctx: ExtractionContext): String?
}

private class TextHeuristicExtractor(
    override val spec: ExtractorSpec,
    private val heuristic: TextHeuristic
) : Extractor {
    override val engineId: String = "heuristic"

    override suspend fun run(ctx: ExtractionContext): ExtractorOutput =
        heuristic.answer(ctx)?.takeIf { it.isNotBlank() }
            ?.let { ExtractorOutput.Text(it) }
            ?: ExtractorOutput.Empty
}

private class ContextualHeuristicExtractor(
    override val spec: ExtractorSpec,
    private val heuristic: ContextualHeuristic
) : Extractor {
    override val engineId: String = "heuristic"

    override suspend fun run(ctx: ExtractionContext): ExtractorOutput {
        val evidence = heuristic.answer(ctx)
            .filter { it.kind in spec.kinds }
            .filter { it.withinVocabulary() }
        return if (evidence.isEmpty()) ExtractorOutput.Empty else ExtractorOutput.Facts(evidence)
    }
}

/**
 * Turns a registry [ExtractorSpec] into something that can actually answer it,
 * given what this device can do right now.
 *
 * The resolution order is the product decision that matters:
 *
 *  1. **The capability the question asked for**, if something serves it — a
 *     downloaded reasoning model, a classifier, or a cloud provider the user
 *     keyed. This is the good answer.
 *  2. **The heuristic floor**, when the spec has one. Weaker, but real, and it
 *     works on a phone with no model pack and no network.
 *  3. **Nothing** — the question is skipped and recorded as such.
 *
 * Step 3 is deliberate. A question with no engine and no floor produces silence,
 * not a guess: the detail screen would rather show fewer chips than confident
 * wrong ones, which is exactly the failure the keyword tagger used to produce.
 */
@Singleton
class ExtractorFactory @Inject constructor(
    private val engines: ExtractionEngineProvider,
    private val analyzers: Set<@JvmSuppressWildcards MemoryAnalyzer>,
    private val textHeuristics: Map<String, @JvmSuppressWildcards TextHeuristic>,
    private val contextualHeuristics: Map<String, @JvmSuppressWildcards ContextualHeuristic>
) {

    /**
     * The extractor to run for this spec, or null when nothing can answer it.
     *
     * Resolved per memory rather than cached, so installing a model pack or
     * entering an API key takes effect on the very next memory.
     */
    suspend fun create(spec: ExtractorSpec, content: NormalizedContent): Extractor? {
        // A question a photo can answer, asked of a memory that has photos,
        // goes to vision first. "Who is in this?" over the pixels recovers what
        // no amount of reasoning over an OCR string can — but only when both
        // the images and a vision engine actually exist.
        if (spec.appliesToImages && content.hasImages) {
            engines.engineFor(Capability.VISION)?.let { return ModelExtractor(spec, it) }
        }

        if (spec.capability != Capability.DETERMINISTIC) {
            val engine = engines.engineFor(spec.capability)
            if (engine != null) return ModelExtractor(spec, engine)
            Log.d(TAG, "No engine for ${spec.capability} — '${spec.id}' falls back")
        }
        return floorFor(spec)
    }

    /** The rules-based answer for this spec, when it has one. */
    private fun floorFor(spec: ExtractorSpec): Extractor? {
        if (!spec.hasHeuristicFloor) return null

        return when (spec.outputShape) {
            OutputShape.TEXT ->
                textHeuristics[spec.id]?.let { TextHeuristicExtractor(spec, it) }

            OutputShape.RELATIONS ->
                // Co-occurrence already builds the entity graph without a model;
                // there is nothing a rule could add here that isn't already done.
                null

            OutputShape.LIST, OutputShape.SINGLE_CHOICE -> {
                // A question defined in terms of earlier answers gets the rule
                // that can see them; everything else gets the blind analyzers.
                contextualHeuristics[spec.id]?.let { return ContextualHeuristicExtractor(spec, it) }

                // Hand this question every rule that speaks to it. The extractor
                // filters by kind, so an analyzer covering several kinds (graph
                // recall, say) contributes to each question it is relevant to
                // without any id-to-analyzer table to maintain.
                val relevant = analyzers.filter { it.kinds.any { kind -> kind in spec.kinds } }
                if (relevant.isEmpty()) null else HeuristicExtractor(spec, relevant)
            }
        }
    }

    private companion object { const val TAG = "ExtractorFactory" }
}
