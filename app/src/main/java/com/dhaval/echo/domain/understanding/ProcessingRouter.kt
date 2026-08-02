package com.dhaval.echo.domain.understanding

/**
 * Decides which questions are worth asking about a memory, before any of them
 * are asked.
 *
 * Not a classifier. It never decides *what* a memory is — that is
 * [Stage.INTERPRET]'s `memory_type`, and it cannot be answered until grounding
 * has run, because "is this a commitment" really means "did a task come out of
 * it". Confusing the two is easy and expensive: a pre-extraction guess at memory
 * type would be wrong often and would then bias everything downstream of it.
 *
 * This only asks a cheaper question: *is there any point running this extractor
 * on this text?* Asking a 4B model "which organisations are mentioned" about
 * "buy milk" costs a full inference to be told nothing. On device, at ~22
 * questions per memory, that waste is most of the battery cost of the pipeline.
 *
 * Deliberately conservative. Skipping a question that would have found something
 * is a silent, permanent loss — the memory simply never gets that facet — while
 * running a pointless one only costs time. So every rule here answers "is it
 * *impossible* that this finds anything", not "is it likely".
 *
 * Pure and dependency-free, so the whole routing policy is testable without a
 * model, a database, or a device.
 */
object ProcessingRouter {

    /** Below this many characters, a memory is a fragment. */
    private const val MINIMUM_MEANINGFUL_LENGTH = 12

    /** A capitalised word that isn't sentence-initial — a possible name. */
    private val INTERIOR_CAPITAL = Regex("""(?<!^)(?<![.!?]\s)(?<!\n)\b[A-Z][a-z]{1,}""")

    /** Anything that could anchor a date or time. */
    private val TIME_SIGNAL = Regex(
        """\b(?:today|tomorrow|tonight|yesterday|next|last|this)\b|""" +
            """\b(?:mon|tue|wed|thur|thu|fri|sat|sun)\w*\b|""" +
            """\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\w*\b|""" +
            """\b(?:morning|afternoon|evening|noon|midnight|weekend)\b|""" +
            """\b(?:in|after|before|by|on|at)\s+\d|\d{1,2}\s*(?:am|pm)|\d{1,2}:\d{2}|""" +
            """\b(?:day|days|week|weeks|month|months|year|years|hour|hours)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Anything that could be an obligation. */
    private val COMMITMENT_SIGNAL = Regex(
        """\b(?:need|needs|have to|has to|must|should|remember|don't forget|""" +
            """going to|gonna|will|plan to|planning|todo|to-do|plan on|plan|plans|""" +
            """plan for|book|buy|call|send|pick up|drop|finish|submit|pay|renew)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Anything that could be a stated choice. */
    private val DECISION_SIGNAL = Regex(
        """\b(?:decided|decision|going with|chose|choosing|settled on|""" +
            """final(?:ly|ised|ized)?|we'll go|i'll go|agreed|concluded)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Anything that could carry feeling. */
    private val FEELING_SIGNAL = Regex(
        """\b(?:feel|felt|feeling|happy|glad|sad|upset|angry|annoyed|frustrated|""" +
            """worried|anxious|nervous|stressed|excited|thrilled|tired|exhausted|""" +
            """grateful|thankful|proud|hopeful|calm|relaxed|confused|disappointed|""" +
            """love|hate|miss|enjoy|hurt|great|terrible|awful|wonderful|amazing)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Whether this question is worth putting to this memory.
     *
     * @param spec the question
     * @param content the memory, after PREPARE
     * @return false only when the question provably cannot find anything here
     */
    fun shouldRun(spec: ExtractorSpec, content: NormalizedContent): Boolean {
        val text = content.text

        // PREPARE gets the text ready and ENRICH reads the graph; neither is
        // routed. NARRATE always runs — a memory should end up with a summary
        // and a title whatever else was found.
        if (spec.stage == Stage.PREPARE || spec.stage == Stage.NARRATE) return true

        // Images can answer some questions on their own, so a photo memory with
        // barely any text is not a reason to skip those.
        if (spec.appliesToImages && content.hasImages) return true

        // Nothing meaningful to read, and no pictures to look at.
        if (text.length < MINIMUM_MEANINGFUL_LENGTH) return false

        return when (spec.id) {
            // Proper-noun questions need a capitalised word that isn't just the
            // start of a sentence. "buy milk tomorrow" has no candidate name.
            "people", "places", "organizations", "projects" ->
                INTERIOR_CAPITAL.containsMatchIn(text)

            "temporal" -> TIME_SIGNAL.containsMatchIn(text)
            "tasks" -> COMMITMENT_SIGNAL.containsMatchIn(text)
            "decisions" -> DECISION_SIGNAL.containsMatchIn(text)
            "emotion" -> FEELING_SIGNAL.containsMatchIn(text)

            // Refining an action is meaningless with no action to refine, and
            // relating entities needs at least two of them. Both depend on
            // earlier answers rather than on the text, so they are gated in the
            // runner where those answers exist — not here.
            else -> true
        }
    }

    /**
     * The questions worth asking about this memory, and the ones being skipped.
     *
     * Returning both is deliberate: a skipped question is still recorded against
     * the memory, so the progress count stays out of the same denominator and
     * "we never asked" never gets mistaken for "we asked and found nothing".
     */
    fun route(specs: List<ExtractorSpec>, content: NormalizedContent): Routing {
        val (run, skip) = specs.partition { shouldRun(it, content) }
        return Routing(run, skip)
    }

    data class Routing(
        val toRun: List<ExtractorSpec>,
        val toSkip: List<ExtractorSpec>
    )
}
