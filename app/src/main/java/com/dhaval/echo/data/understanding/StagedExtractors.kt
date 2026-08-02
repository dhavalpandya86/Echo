package com.dhaval.echo.data.understanding

import android.util.Log
import com.dhaval.echo.domain.understanding.AssertedRelation
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.ExtractionEngine
import com.dhaval.echo.domain.understanding.Extractor
import com.dhaval.echo.domain.understanding.ExtractorOutput
import com.dhaval.echo.domain.understanding.ExtractorSpec
import com.dhaval.echo.domain.understanding.MemoryAnalyzer
import com.dhaval.echo.domain.understanding.OutputShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the message an engine is asked to answer.
 *
 * Deliberately short. A small on-device model holds a narrow question over a
 * few facts far better than a long instruction over raw text, so the prompt is
 * the question, the memory, the handful of facts already established, and the
 * exact answer shape — nothing else.
 */
internal object ExtractorPrompt {

    /** Facts worth showing an INTERPRET/NARRATE extractor, in a readable order. */
    private val CONTEXT_ORDER = listOf(
        EvidenceKind.PERSON to "People",
        EvidenceKind.ACTIVITY to "Activities",
        EvidenceKind.OBJECT to "Objects",
        EvidenceKind.PLACE to "Places",
        EvidenceKind.ORG to "Organisations",
        EvidenceKind.PROJECT to "Projects",
        EvidenceKind.TOPIC to "Topics",
        EvidenceKind.TASK to "Tasks",
        EvidenceKind.DECISION to "Decisions",
        EvidenceKind.MEMORY_TYPE to "Memory type",
        EvidenceKind.CATEGORY to "Category"
    )

    private val MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    fun build(spec: ExtractorSpec, ctx: ExtractionContext): String = buildString {
        appendLine(spec.question)
        appendLine()
        append("Memory captured at ")
        append(ctx.content.capturedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
        appendLine(" (local time).")
        appendLine("---")
        appendLine(ctx.text)
        appendLine("---")

        val known = knownFacts(ctx)
        if (known.isNotBlank()) {
            appendLine()
            appendLine("Already established about this memory:")
            append(known)
        }

        val context = lifeContext(ctx)
        if (context.isNotBlank()) {
            appendLine()
            appendLine("What you already know about the people and things in it:")
            append(context)
        }

        appendLine()
        appendLine(answerShape(spec))
    }

    /**
     * The graph's account of the things this memory names.
     *
     * Stated as history — counts, dates, co-occurrences — never as conclusions,
     * because the model's job is to draw the conclusion and a prompt that hands
     * it one will simply agree. Unfamiliar entities are marked as such so a
     * single prior sighting is not mistaken for a pattern.
     */
    private fun lifeContext(ctx: ExtractionContext): String = buildString {
        for (entity in ctx.enrichment.entities) {
            append("- ").append(entity.name)
            append(" (").append(entity.type.lowercase())
            if (entity.memoryCount > 0) {
                append(", in ").append(entity.memoryCount)
                append(if (entity.memoryCount == 1) " earlier memory" else " earlier memories")
                append(" since ").append(entity.firstSeenAt.format(MONTH_YEAR))
                if (!entity.isFamiliar) append("; only seen a few times, so do not assume a pattern")
            } else {
                append(", first seen in this memory")
            }
            append(")")

            if (entity.relatedNames.isNotEmpty()) {
                append(" — usually appears with ").append(entity.relatedNames.joinToString(", "))
            }
            if (entity.openCommitments.isNotEmpty()) {
                append("; already outstanding: ").append(entity.openCommitments.joinToString("; "))
            }
            appendLine()
        }
    }

    private fun knownFacts(ctx: ExtractionContext): String = buildString {
        for ((kind, label) in CONTEXT_ORDER) {
            val values = ctx.of(kind).map { it.value }.distinct()
            if (values.isNotEmpty()) appendLine("$label: ${values.joinToString(", ")}")
        }
    }

    /**
     * The output contract. Always a JSON object, even for free text — models add
     * preambles ("Sure! Here's the summary:") to bare text far more often than
     * they break a named JSON field.
     */
    private fun answerShape(spec: ExtractorSpec): String = when (spec.outputShape) {
        OutputShape.LIST ->
            """
            Return ONLY this JSON object, no prose and no markdown fences:
            {"items":[{"value":"<the finding>","quote":"<exact words from the memory>","confidence":<0..1>${dueField(spec)}}]}
            Return {"items":[]} if there are none — an empty answer is correct and expected.
            Every quote must be copied verbatim from the memory above.
            """.trimIndent()

        OutputShape.SINGLE_CHOICE ->
            """
            Choose exactly one of: ${spec.choices.joinToString(", ")}
            Return ONLY this JSON object, no prose and no markdown fences:
            {"value":"<your choice>","quote":"<exact words from the memory>","confidence":<0..1>}
            Return {"value":null} if none of them genuinely fits.
            """.trimIndent()

        OutputShape.TEXT ->
            """
            Return ONLY this JSON object, no prose and no markdown fences:
            {"text":"<your answer>"}
            """.trimIndent()

        OutputShape.RELATIONS ->
            """
            Return ONLY this JSON object, no prose and no markdown fences:
            {"relations":[{"from":"<entity>","to":"<entity>","relation":"<short verb phrase>","quote":"<exact words>","confidence":<0..1>}]}
            Use only names listed above. Return {"relations":[]} if there are no clear connections.
            """.trimIndent()
    }

    /** Only date-bearing kinds get a `due` field, so nothing else invents one. */
    private fun dueField(spec: ExtractorSpec): String =
        if (spec.kinds.any { it == EvidenceKind.TASK || it == EvidenceKind.REMINDER }) {
            ""","due":"<ISO-8601 or null>""""
        } else {
            ""
        }
}

/**
 * Raw engine response → [ExtractorOutput].
 *
 * Tolerant on purpose: strips prose and fences around the JSON, skips individual
 * malformed elements rather than discarding a whole answer, and drops anything
 * claiming a kind the extractor was not asked about. Throws only when there is
 * no parseable JSON at all — which the runner treats as that extractor failing,
 * not the memory failing.
 *
 * Pure, so the whole contract is testable without a model or a network.
 */
object ExtractorOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val DEFAULT_DUE_HOUR = 9
    private const val DEFAULT_CONFIDENCE = 0.75f

    fun parse(spec: ExtractorSpec, raw: String, capturedAt: LocalDateTime): ExtractorOutput {
        val objectText = extractJsonObject(raw)
            ?: throw IllegalArgumentException(
                "No JSON object in response for '${spec.id}': ${raw.take(200)}"
            )
        val root = json.parseToJsonElement(objectText).jsonObject

        return when (spec.outputShape) {
            OutputShape.LIST -> parseList(spec, root, capturedAt)
            OutputShape.SINGLE_CHOICE -> parseChoice(spec, root)
            OutputShape.TEXT -> parseText(root)
            OutputShape.RELATIONS -> parseRelations(root)
        }
    }

    private fun parseList(
        spec: ExtractorSpec,
        root: kotlinx.serialization.json.JsonObject,
        capturedAt: LocalDateTime
    ): ExtractorOutput {
        val array = root["items"]?.jsonArray ?: return ExtractorOutput.Empty
        // A single-kind extractor stamps its own kind: the model answered one
        // narrow question, so it is never asked to label the answer.
        val kind = spec.kinds.singleOrNull() ?: return ExtractorOutput.Empty

        val evidence = array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val value = obj["value"]?.jsonPrimitive?.content?.trim()
                if (value.isNullOrBlank() || value.equals("null", ignoreCase = true)) {
                    return@runCatching null
                }
                Evidence(
                    kind = kind,
                    value = value,
                    evidenceText = obj.quote(),
                    confidence = obj.confidence(),
                    dueAtMillis = obj["due"]?.jsonPrimitive?.content?.let { parseDue(it, capturedAt) }
                )
            }.getOrNull()
        }.distinctBy { it.value.lowercase() }

        return if (evidence.isEmpty()) ExtractorOutput.Empty else ExtractorOutput.Facts(evidence)
    }

    private fun parseChoice(
        spec: ExtractorSpec,
        root: kotlinx.serialization.json.JsonObject
    ): ExtractorOutput {
        val raw = root["value"]?.jsonPrimitive?.content?.trim()
        if (raw.isNullOrBlank() || raw.equals("null", ignoreCase = true)) return ExtractorOutput.Empty
        val kind = spec.kinds.singleOrNull() ?: return ExtractorOutput.Empty

        // Snap to the declared vocabulary. A model that answers "family" or
        // "Family stuff" meant the "Family" facet; anything genuinely outside
        // the list is dropped, because an off-vocabulary facet cannot group.
        val choice = spec.choices.firstOrNull { it.equals(raw, ignoreCase = true) }
            ?: spec.choices.firstOrNull { raw.contains(it, ignoreCase = true) }
            ?: return ExtractorOutput.Empty

        return ExtractorOutput.Facts(
            listOf(
                Evidence(
                    kind = kind,
                    value = choice,
                    evidenceText = root.quote(),
                    confidence = root.confidence()
                )
            )
        )
    }

    private fun parseText(root: kotlinx.serialization.json.JsonObject): ExtractorOutput {
        val text = root["text"]?.jsonPrimitive?.content?.trim()
        return if (text.isNullOrBlank()) ExtractorOutput.Empty else ExtractorOutput.Text(text)
    }

    private fun parseRelations(root: kotlinx.serialization.json.JsonObject): ExtractorOutput {
        val array = root["relations"]?.jsonArray ?: return ExtractorOutput.Empty
        val edges = array.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val from = obj["from"]?.jsonPrimitive?.content?.trim()
                val to = obj["to"]?.jsonPrimitive?.content?.trim()
                if (from.isNullOrBlank() || to.isNullOrBlank() || from.equals(to, true)) {
                    return@runCatching null
                }
                AssertedRelation(
                    sourceName = from,
                    targetName = to,
                    relation = obj["relation"]?.jsonPrimitive?.content?.trim().orEmpty()
                        .ifBlank { "RELATED_TO" },
                    evidenceText = obj.quote(),
                    confidence = obj.confidence()
                )
            }.getOrNull()
        }
        return if (edges.isEmpty()) ExtractorOutput.Empty else ExtractorOutput.Relations(edges)
    }

    private fun kotlinx.serialization.json.JsonObject.quote(): String? =
        this["quote"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotBlank() }

    private fun kotlinx.serialization.json.JsonObject.confidence(): Float =
        this["confidence"]?.jsonPrimitive?.floatOrNull?.coerceIn(0f, 1f) ?: DEFAULT_CONFIDENCE

    /** First `{ … }` slice — ignores fences and commentary around it. */
    private fun extractJsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start in 0 until end) raw.substring(start, end + 1) else null
    }

    private fun parseDue(value: String, capturedAt: LocalDateTime): Long? {
        val text = value.trim().takeIf { it.isNotBlank() && !it.equals("null", true) } ?: return null
        val dateTime = runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atTime(DEFAULT_DUE_HOUR, 0) }.getOrNull()
            ?: return null
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

/**
 * Asks one question of whatever engine serves its capability.
 *
 * There is one of these per model-backed question, and they differ only by
 * their [spec] — the question, the answer shape, and the vocabulary are data.
 * That is what keeps 22 extractors from being 22 classes.
 */
class ModelExtractor(
    override val spec: ExtractorSpec,
    private val engine: ExtractionEngine
) : Extractor {

    override val engineId: String get() = engine.engineId

    override suspend fun run(ctx: ExtractionContext): ExtractorOutput {
        val raw = engine.answer(spec, ctx)
        return ExtractorOutputParser.parse(spec, raw, ctx.content.capturedAt).also {
            Log.d(TAG, "${spec.id} answered by ${engine.engineId}")
        }
    }

    private companion object { const val TAG = "ModelExtractor" }
}

/**
 * Answers a question with rules instead of a model.
 *
 * This is the floor: it needs no download, runs in microseconds, and is what a
 * user with no model pack installed actually gets. It is weaker than a model —
 * it will find "Prabir" but never work out that "swimming glasses" meant
 * goggles — but it is never wrong in the confident way a model can be, and it
 * keeps Echo useful offline on a phone with no room for a 3 GB model.
 */
class HeuristicExtractor(
    override val spec: ExtractorSpec,
    private val analyzers: List<MemoryAnalyzer>
) : Extractor {

    override val engineId: String = "heuristic"

    override suspend fun run(ctx: ExtractionContext): ExtractorOutput {
        val evidence = analyzers.flatMap { analyzer ->
            runCatching { analyzer.analyze(ctx.content) }
                .onFailure { Log.w(TAG, "${analyzer::class.simpleName} failed for ${spec.id}", it) }
                .getOrDefault(emptyList())
        }
            // Rules are chatty; hold each analyzer to the question it was asked.
            .filter { it.kind in spec.kinds }
            .distinctBy { it.kind to it.value.lowercase() }

        return if (evidence.isEmpty()) ExtractorOutput.Empty else ExtractorOutput.Facts(evidence)
    }

    private companion object { const val TAG = "HeuristicExtractor" }
}
