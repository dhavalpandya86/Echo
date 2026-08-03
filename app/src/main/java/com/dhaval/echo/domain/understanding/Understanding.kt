package com.dhaval.echo.domain.understanding

import java.time.LocalDateTime

/**
 * The Memory Understanding Engine's core contracts.
 *
 * Pipeline (all internal — the user only adds a memory and sees output):
 *
 *   capture → NORMALIZE (per-source extractors) → [NormalizedContent]
 *           → PREPARE   (transcribe, correct the sentence)      ← shown at once
 *           → GROUND    (what the memory literally says)
 *           → INTERPRET (what it means; reads GROUND's answers)
 *           → NARRATE   (summary, title; reads everything)
 *           → RESOLVE   ([EntityResolver]: evidence → entity graph links)
 *           → EXPAND    (graph traversal; inferred links — MU-4)
 *
 * Two invariants, and one deliberate relaxation:
 *
 *  - **Within a stage, extractors stay blind to each other.** None reads
 *    another's output, and a failure in one never blocks the rest.
 *  - **Across stages, later reads earlier.** An [Extractor] receives every
 *    settled answer from earlier stages in its [ExtractionContext]. This is the
 *    point of the staged design: interpretation must reason over grounded facts
 *    rather than raw text, and the summary must be written last, once there is
 *    something to summarise. The dependency only ever points backwards, so the
 *    order stays a simple sequence with no cycles to resolve.
 *  - Every conclusion carries evidence text and a confidence. Nothing is "magic".
 *
 * The engine asks one narrow question at a time rather than one broad one.
 * Small on-device models answer "who are the people here?" far more reliably
 * than "analyse this memory", and a question that fails costs one facet instead
 * of the whole board.
 */

/** How a memory was captured. One memory may have several. */
enum class SourceKind { VOICE, TEXT, PHOTO, VIDEO }

/**
 * Stage-2 canonical representation. After this point the pipeline no longer
 * cares whether content came from voice, text, photo, or video.
 */
data class NormalizedContent(
    val memoryId: String,
    val userId: String,
    /** Merged text: transcript + written content + OCR + visual description. */
    val text: String,
    val sourceKinds: Set<SourceKind>,
    val capturedAt: LocalDateTime,
    /** Image labels / scene objects from on-device detection. */
    val visualObjects: List<String> = emptyList(),
    /**
     * The memory's images, for extractors that can actually look at them.
     *
     * Carried alongside the text rather than only folded into it, because the
     * two answer different questions. Flattening a photo to "Echo sees: Forklift,
     * Carton" and asking the text questions about that string can only ever
     * recover what the labeller already named — it cannot read a whiteboard,
     * recognise who is in the frame, or tell that a receipt is a receipt. A
     * [Capability.VISION] extractor needs the pixels.
     */
    val imagePaths: List<String> = emptyList()
) {
    val hasImages: Boolean get() = imagePaths.isNotEmpty()
}

/**
 * What kind of fact a piece of evidence claims.
 *
 * Three families, which decide where a claim is persisted:
 *  - *entity-forming* → an [com.dhaval.echo.data.db.EntityNode] + a memory link.
 *    These are the recurring things a life is made of, so they must have identity
 *    across memories ("all Swimming memories", "everything about Prabir").
 *  - *commitments* → an [com.dhaval.echo.data.db.ExtractedItem] with a lifecycle.
 *  - *facets* → a single interpretive verdict about this one memory, also an
 *    ExtractedItem but with no lifecycle. Exactly one value per memory per facet.
 */
enum class EvidenceKind {
    // entity-forming → resolver → entity graph
    PERSON, PROJECT, TOPIC, PLACE, ORG, PRODUCT,
    /** A thing that recurs and is worth retrieving by ("swimming goggles"). */
    OBJECT,
    /** Something the user does, recurring ("Swimming", "Gym"). */
    ACTIVITY,

    // per-memory commitments → evidence board, with status
    TASK, REMINDER, MOOD, DECISION,

    // per-memory facets → evidence board, one verdict each
    /** What the user means to bring about ("Buy equipment"). */
    INTENT,
    /** What sort of memory this is ("Commitment", "Experience"). */
    MEMORY_TYPE,
    /** Which area of life this belongs to ("Family", "Work"). */
    CATEGORY,
    /** How much it matters ("High", "Medium", "Low"). */
    PRIORITY;

    /** Facets are singular per memory: a second verdict replaces the first. */
    val isFacet: Boolean
        get() = this == INTENT || this == MEMORY_TYPE || this == CATEGORY || this == PRIORITY
}

/**
 * One fact extracted from a memory, with its supporting quote and confidence.
 */
data class Evidence(
    val kind: EvidenceKind,
    /** The fact itself: "Raj", "Oceanis", "Call Raj about the logo". */
    val value: String,
    /** The text span that supports this conclusion, when available. */
    val evidenceText: String?,
    /** 0..1. Heuristic analyzers must stay conservative here. */
    val confidence: Float,
    /** Resolved due date for TASK/REMINDER, epoch millis. */
    val dueAtMillis: Long? = null
)

/**
 * One specialist at the table. Knows nothing about the other analyzers.
 *
 * Retained as the narrow, stateless form of extraction — the on-device
 * heuristics and the cloud evidence analyzers all still implement it. The
 * staged runner wraps these in [Extractor]s, which add identity, ordering and
 * access to earlier stages' answers.
 */
interface MemoryAnalyzer {
    /** What this analyzer can claim. Used for logging and routing. */
    val kinds: Set<EvidenceKind>

    /** Extract evidence. Must not throw for ordinary content; return empty instead. */
    suspend fun analyze(content: NormalizedContent): List<Evidence>
}

// ── The staged pipeline ───────────────────────────────────────────────

/**
 * When an extractor runs. Stages run in declaration order; every extractor in a
 * stage settles before the next stage begins.
 */
enum class Stage {
    /** Make the text readable. Its output is shown before anything else runs. */
    PREPARE,

    /** What the memory literally says. Every claim must quote the text. */
    GROUND,

    /**
     * What Echo already knows about the things this memory named.
     *
     * Not an extractor stage — it produces no evidence and makes no claim about
     * the memory. It looks each grounded entity up in the graph and hands the
     * result forward, so that everything after it reasons about *Prabir, your
     * son, who swims on Saturdays* rather than about the string "Prabir".
     *
     * This is the stage that separates a memory system from an extraction
     * pipeline: the same sentence means something different depending on whose
     * life it lands in, and only the graph knows that.
     */
    ENRICH,

    /** What it means. Reads [GROUND]'s answers and [ENRICH]'s context. */
    INTERPRET,

    /** Say it back to the user. Reads everything. */
    NARRATE
}

/**
 * What an extractor needs in order to answer its question — a *capability*,
 * never a model.
 *
 * The provider layer decides what actually serves each capability: an on-device
 * model the user has installed, a cloud provider they have keyed, or the
 * heuristic floor. That indirection is the point — the model landscape moves
 * faster than this app should have to, so no extractor names one.
 */
enum class Capability {
    /**
     * Rules with a single right answer: date parsing, language codes, sentence
     * repair. Always available, needs no download, and either correct or not —
     * there is no model behind it to be uncertain.
     */
    DETERMINISTIC,

    /** Closed-set labelling. A small classifier beats generation here, and is near-free. */
    CLASSIFICATION,

    /**
     * Answering by traversing what Echo already knows — who this person is, what
     * they usually do, which project this belongs to.
     *
     * Deliberately *not* grouped with [DETERMINISTIC]. Both run on-device with no
     * model, which is the only thing they have in common: a regex reads one
     * memory's characters, while a traversal reads the accumulated shape of
     * someone's life and gets better as the graph grows. Collapsing them hides
     * the layer that is most of Echo's long-term advantage.
     */
    GRAPH,

    /** Open-ended language understanding and generation. An instruct LLM. */
    REASONING,

    /** Understanding images. Multimodal. */
    VISION
}

/** Whether a question has one answer or many. */
enum class OutputShape {
    /** A list of findings; may legitimately be empty. */
    LIST,

    /** Exactly one verdict from [ExtractorSpec.choices], or none if unclear. */
    SINGLE_CHOICE,

    /** Free text written back onto the memory — see [TextTarget]. */
    TEXT,

    /** Pairs of already-identified entities and how they relate. */
    RELATIONS
}

/**
 * Where an [OutputShape.TEXT] answer is stored on the memory.
 *
 * Kept in the spec so the runner stays data-driven: it never switches on an
 * extractor id to decide what to do with an answer.
 */
enum class TextTarget {
    NONE,

    /** `diary_entries.cleanedText` — never `transcript`, which stays verbatim. */
    CLEANED_TEXT,
    SUMMARY,

    /** Written only when the user has not titled the memory themselves. */
    TITLE,
    LANGUAGE
}

/** One relationship a model read out of the memory: "Prabir | does | Swimming". */
data class AssertedRelation(
    val sourceName: String,
    val targetName: String,
    val relation: String,
    val evidenceText: String?,
    val confidence: Float
)

/**
 * What an extractor produced. Most questions yield [Evidence]; a few rewrite the
 * memory's own text, and one states relationships between entities.
 */
sealed interface ExtractorOutput {

    /** Facts for the evidence board and the entity graph. */
    data class Facts(val evidence: List<Evidence>) : ExtractorOutput

    /** Text written back onto the memory, per [ExtractorSpec.textTarget]. */
    data class Text(val value: String) : ExtractorOutput

    /** Edges between entities the memory already named. */
    data class Relations(val edges: List<AssertedRelation>) : ExtractorOutput

    /**
     * Asked and answered: there is nothing here. A real result, not a failure —
     * most memories do not mention an organisation.
     */
    data object Empty : ExtractorOutput
}

/**
 * One question the engine asks about a memory.
 *
 * A spec is data, not code: the runner turns it into a prompt, routes it to
 * whatever serves its [capability], and parses the answer back into [Evidence].
 * Adding a 23rd question is a new entry in the registry, not a new class.
 */
data class ExtractorSpec(
    /** Stable identity, persisted in run rows. Never rename — it is the resume key. */
    val id: String,
    val stage: Stage,
    /** What this extractor is allowed to claim. Anything else it returns is dropped. */
    val kinds: Set<EvidenceKind>,
    val capability: Capability,
    /** The single, narrow question. This becomes the prompt. */
    val question: String,
    val outputShape: OutputShape = OutputShape.LIST,
    /** The permitted answers for [OutputShape.SINGLE_CHOICE]. Empty otherwise. */
    val choices: List<String> = emptyList(),
    /** Where an [OutputShape.TEXT] answer lands on the memory. */
    val textTarget: TextTarget = TextTarget.NONE,
    /**
     * Earlier extractors whose answers this one needs. Advisory for ordering —
     * the runner passes every settled answer regardless — but it documents the
     * real dependency and lets a test assert the order is sane.
     */
    val dependsOn: Set<String> = emptySet(),
    /**
     * True when this question can still be answered, less well, with no model
     * installed. False means it is simply skipped on a device with no
     * capability for it — an honest gap rather than a fabricated answer.
     */
    val hasHeuristicFloor: Boolean = false,
    /**
     * True when this question should also be put to the images, not just the
     * text. People, places, objects and activities are all readable from a
     * photo; a commitment or a decision is not.
     */
    val appliesToImages: Boolean = false
)

/**
 * What the graph knows about one thing this memory named.
 *
 * Everything here is history, not inference: counts, dates, and the entities it
 * actually co-occurs with. The interpreting model draws the conclusions; this
 * only tells it what it is entitled to draw them from.
 */
data class EntityContext(
    val entityId: String,
    val name: String,
    val type: String,
    /** How many memories mention this — how well Echo knows it. */
    val memoryCount: Int,
    val firstSeenAt: LocalDateTime,
    val lastSeenAt: LocalDateTime,
    /** Strongest graph neighbours, most-connected first. */
    val relatedNames: List<String> = emptyList(),
    /** Commitments still open that involve this entity. */
    val openCommitments: List<String> = emptyList()
) {
    /**
     * True for something seen once before now — too thin to reason from, and
     * saying so keeps a model from treating a coincidence as a pattern.
     */
    val isFamiliar: Boolean get() = memoryCount >= FAMILIAR_AFTER

    private companion object { const val FAMILIAR_AFTER = 3 }
}

/**
 * The life context behind this memory: what Echo already knew about the things
 * it names, gathered in [Stage.ENRICH].
 */
data class Enrichment(
    val entities: List<EntityContext> = emptyList()
) {
    val isEmpty: Boolean get() = entities.isEmpty()

    fun of(type: String): List<EntityContext> = entities.filter { it.type == type }
}

/**
 * What an extractor is given: the memory, every answer already settled, and
 * what Echo already knew about the things in it.
 */
data class ExtractionContext(
    val content: NormalizedContent,
    /** Answers from earlier stages, in the order they were produced. */
    val priorEvidence: List<Evidence> = emptyList(),
    /** Graph context for the entities GROUND found. Empty before [Stage.ENRICH]. */
    val enrichment: Enrichment = Enrichment()
) {
    /** The text to reason about — corrected when available, raw otherwise. */
    val text: String get() = content.text

    /** Earlier findings of a given kind, e.g. the people GROUND found. */
    fun of(kind: EvidenceKind): List<Evidence> = priorEvidence.filter { it.kind == kind }

    /** The single verdict for a facet, if one was reached. */
    fun facet(kind: EvidenceKind): String? = of(kind).maxByOrNull { it.confidence }?.value

    /** What the graph knows about a named thing, if it knows anything. */
    fun known(name: String): EntityContext? =
        enrichment.entities.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

/**
 * One question, and the ability to answer it.
 *
 * Implementations are few on purpose: a heuristic wrapper around the existing
 * rule-based analyzers, and a generic model-driven one that works for every
 * spec. The 22 questions are data; only the two ways of answering are code.
 */
interface Extractor {
    val spec: ExtractorSpec

    /**
     * What is actually answering — "cloud:claude", "heuristic". Recorded on the
     * run so a thin board is explainable: "no model was installed" and "the
     * model found nothing" look identical in the results and are not the same
     * thing at all.
     */
    val engineId: String

    /**
     * Answer the question.
     *
     * Return [ExtractorOutput.Empty] when there is genuinely nothing to report.
     * Throw only when the question could not be *asked* — a model that failed to
     * load, a network error. The runner records a throw against this extractor
     * alone and carries on with the remaining questions.
     */
    suspend fun run(ctx: ExtractionContext): ExtractorOutput
}

/** How one extractor's attempt turned out. */
enum class ExtractorOutcome {
    /** Asked and answered — including an honest "nothing here". */
    COMPLETED,

    /** The question could not be asked. Retried on the next pass. */
    FAILED,

    /** Nothing on this device can answer it, and it has no rules-based floor. */
    SKIPPED
}

/**
 * The record of one extractor's attempt, persisted next to its results so the
 * pipeline can resume, report progress, and explain a thin board.
 */
data class ExtractorRunRecord(
    val extractorId: String,
    val outcome: ExtractorOutcome,
    /** What actually answered — "cloud:claude", "heuristic". Not what was asked for. */
    val engineId: String,
    val startedAt: LocalDateTime,
    val completedAt: LocalDateTime,
    val latencyMs: Long,
    val evidenceCount: Int = 0,
    val error: String? = null
)

/**
 * Stage 5: turns entity-forming evidence into graph nodes + typed links,
 * matching against entities the graph already knows (identity, not tagging).
 */
interface EntityResolver {
    /**
     * Persists the evidence board for a memory: entity evidence becomes
     * entities + memory→entity links (match-or-create, never duplicate);
     * claim evidence becomes extracted items. Idempotent per memory —
     * re-processing replaces that memory's previous conclusions.
     */
    suspend fun resolve(content: NormalizedContent, evidence: List<Evidence>)

    /**
     * Persists one extractor's answer and its run record, atomically, touching
     * no other extractor's rows.
     *
     * This is what makes understanding incremental: each of the ~22 questions
     * lands the moment it is answered, so the detail screen fills in while the
     * rest are still running, and a re-run of one question replaces exactly its
     * own conclusions.
     */
    suspend fun persistExtractorOutput(
        content: NormalizedContent,
        record: ExtractorRunRecord,
        output: ExtractorOutput
    )

    /**
     * The whole-memory pass, once every extractor has settled: graph expansion
     * (Stage 6 inferred links) and entity↔entity edge rebuilding.
     *
     * Deliberately not per-extractor — both operations reason about everything
     * the memory named together, so running them 22 times would be wasteful and,
     * for expansion, wrong.
     */
    suspend fun finalizeMemory(content: NormalizedContent)
}

// MemoryAnalyzerProvider and MemoryUnderstandingService are gone.
//
// They orchestrated the previous design: run every analyzer over the memory at
// once, concatenate one flat evidence board, resolve it in a single write. The
// staged pipeline replaces both — ExtractorRegistry decides what is asked,
// ExtractorFactory decides what answers, and StagedUnderstandingRunner persists
// each answer as it lands. What is left of the old design is MemoryAnalyzer
// itself, which is still exactly the right shape for a rules-based specialist.
