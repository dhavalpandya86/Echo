package com.dhaval.echo.domain.understanding

/**
 * Every question Echo asks about a memory, in the order it asks them.
 *
 * The old pipeline asked one broad question — "analyse this" — and got broad,
 * shallow answers: a summary that was really just the first sentence back, and
 * chips built from word frequency. This registry replaces that with ~22 narrow
 * questions, each answered on its own.
 *
 * Three reasons the narrow form wins:
 *  - Small on-device models answer "who are the people here?" far more reliably
 *    than "extract everything". A constrained question with a constrained answer
 *    shape is most of what makes a 4B model usable.
 *  - A question that fails costs one facet, not the whole board.
 *  - Grounding comes before interpretation, and narration comes last. The model
 *    that writes the summary has already been told who and what the memory is
 *    about, so it is summarising facts rather than guessing them.
 *
 * The registry is data. Adding a question is an entry here; nothing else changes.
 */
object ExtractorRegistry {

    // ── Closed answer sets ────────────────────────────────────────────
    // Facets are single-choice on purpose. An open-ended "what category is
    // this?" drifts across memories ("Family", "family stuff", "Kids") and
    // stops being a retrieval handle. A fixed list keeps chips groupable.

    val MEMORY_TYPES = listOf(
        "Commitment",   // something the user has undertaken to do
        "Decision",     // a choice they state they have made
        "Experience",   // something that happened to them
        "Observation",  // something they noticed about the world
        "Idea",         // a thought they want to keep
        "Learning",     // something they now know
        "Conversation", // an exchange with someone
        "Reflection"    // thinking about themselves
    )

    val CATEGORIES = listOf(
        "Family", "Work", "Health", "Finance", "Travel",
        "Social", "Learning", "Home", "Personal"
    )

    val PRIORITIES = listOf("High", "Medium", "Low")

    val MOODS = listOf(
        "Happy", "Excited", "Grateful", "Caring", "Proud", "Hopeful",
        "Calm", "Reflective", "Motivated", "Tired",
        "Anxious", "Frustrated", "Sad", "Confused", "Disappointed"
    )

    // ── PREPARE ───────────────────────────────────────────────────────
    // Runs first and alone. Its output is what the user reads while the rest
    // of the pipeline is still working.

    private val prepare = listOf(
        ExtractorSpec(
            id = "language",
            stage = Stage.PREPARE,
            kinds = emptySet(),
            capability = Capability.DETERMINISTIC,
            question = "Which language is this memory in?",
            outputShape = OutputShape.TEXT,
            textTarget = TextTarget.LANGUAGE,
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "cleanup",
            stage = Stage.PREPARE,
            kinds = emptySet(),
            capability = Capability.REASONING,
            question = """
                Rewrite this transcript as clean, readable text. Restore punctuation and
                capitalisation, remove filler words and false starts, and fix obvious
                speech-recognition slips.

                Change nothing else. Do not summarise, do not reorder, do not add or
                remove facts, and keep the speaker's own words and phrasing wherever
                they are already clear. If the text is already clean, return it unchanged.
            """.trimIndent(),
            outputShape = OutputShape.TEXT,
            textTarget = TextTarget.CLEANED_TEXT,
            hasHeuristicFloor = true
        )
    )

    // ── GROUND ────────────────────────────────────────────────────────
    // What the memory literally says. Every claim must quote the text, because
    // these become long-lived entities in the graph — a hallucinated person is
    // far more damaging than a missed one, and outlives the memory that
    // invented them.

    private val ground = listOf(
        ExtractorSpec(
            id = "people",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.PERSON),
            capability = Capability.REASONING,
            question = """
                Who are the people mentioned in this memory?

                List every named person, and people identified by their relationship to
                the speaker ("my brother", "Prabir's teacher"). Use the person's name as
                the value when it is given. Do not include the speaker themselves.
                Return an empty list if no people are mentioned.
            """.trimIndent(),
            hasHeuristicFloor = true,
            appliesToImages = true
        ),
        ExtractorSpec(
            id = "places",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.PLACE),
            capability = Capability.REASONING,
            question = """
                Which places are mentioned? Include named locations (cities, venues,
                countries) and specific named places the speaker refers to. Do not
                include vague references like "home" or "there" unless they name a place.
            """.trimIndent(),
            hasHeuristicFloor = true,
            appliesToImages = true
        ),
        ExtractorSpec(
            id = "organizations",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.ORG),
            capability = Capability.REASONING,
            question = """
                Which organisations, companies, schools, or institutions are mentioned?
                Use the organisation's name as the value.
            """.trimIndent(),
            appliesToImages = true
        ),
        ExtractorSpec(
            id = "activities",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.ACTIVITY),
            capability = Capability.REASONING,
            question = """
                What activities are mentioned — things someone does, attends, or takes
                part in? Examples: Swimming, Cricket practice, Piano lesson, Gym,
                Grocery shopping, Doctor appointment.

                Name the activity itself in its general form ("Swimming", not "take
                Prabir swimming"), so the same activity recurring in a later memory
                gets the same name.
            """.trimIndent(),
            hasHeuristicFloor = true,
            appliesToImages = true
        ),
        ExtractorSpec(
            id = "objects",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.OBJECT),
            capability = Capability.REASONING,
            question = """
                Which physical things are mentioned — items to buy, bring, find, or use?
                Examples: swimming goggles, passport, birthday present, car keys.

                Name the thing itself, not the action involving it. Skip abstractions
                and anything that is really a place, person, or organisation.
            """.trimIndent(),
            hasHeuristicFloor = true,
            appliesToImages = true
        ),
        ExtractorSpec(
            id = "projects",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.PROJECT),
            capability = Capability.REASONING,
            question = """
                Which named projects or ongoing pieces of work are mentioned? A project
                is something with a name that spans multiple memories — a product, a
                client engagement, a renovation. Do not invent a project for a one-off task.
            """.trimIndent(),
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "topics",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.TOPIC),
            capability = Capability.REASONING,
            question = """
                What subjects does this memory discuss, beyond the specific people,
                places and things already named? A topic is a recurring theme worth
                grouping memories by — "health insurance", "school admissions",
                "marathon training". At most three, and none if the memory is purely
                about a concrete event.
            """.trimIndent()
        ),
        ExtractorSpec(
            id = "temporal",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.REMINDER),
            capability = Capability.REASONING,
            question = """
                What dates and times does this memory refer to? Include relative
                expressions ("next week", "tomorrow morning", "in three days") and
                explicit ones ("5 PM", "on the 14th").

                Resolve each against the capture time given, and return the resolved
                instant in ISO-8601 alongside the phrase the speaker actually used.
            """.trimIndent(),
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "tasks",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.TASK),
            capability = Capability.REASONING,
            question = """
                What does the speaker say they need to do? Return each as a short
                imperative phrase.

                Only include things they actually commit to or intend. Do not turn a
                description of something that already happened into a task, and do not
                invent a task from a topic that merely came up.
            """.trimIndent(),
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "decisions",
            stage = Stage.GROUND,
            kinds = setOf(EvidenceKind.DECISION),
            capability = Capability.REASONING,
            question = """
                What has the speaker decided? Only include choices they state they have
                made or settled on — not options they are still weighing.
            """.trimIndent()
        )
    )

    // ── INTERPRET ─────────────────────────────────────────────────────
    // What it means. These read GROUND's answers, so they reason over
    // structured facts rather than raw text — fewer hallucinations, and a much
    // smaller prompt for an on-device model to hold.

    private val interpret = listOf(
        ExtractorSpec(
            id = "action_refinement",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.TASK),
            capability = Capability.REASONING,
            dependsOn = setOf("tasks", "objects", "people", "activities"),
            question = """
                Rewrite each task as what the speaker actually has to do, using the
                people, objects and activities already identified.

                Spoken notes are compressed and imprecise. "Take Prabir for swimming
                glasses" almost certainly means buying swimming goggles for Prabir
                before his swimming — the real task is "Buy swimming goggles for
                Prabir", not "take Prabir for glasses". Resolve that kind of shorthand:
                pick the verb the speaker meant, and correct terms they approximated.

                Stay faithful. If the plain reading is already correct, return the task
                unchanged. Never add a task that was not there, and never invent detail
                the memory does not support — a slightly awkward task the speaker
                recognises beats a fluent one they do not.
            """.trimIndent()
        ),
        ExtractorSpec(
            id = "intent",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.INTENT),
            capability = Capability.REASONING,
            dependsOn = setOf("tasks", "action_refinement", "objects"),
            question = """
                What is the speaker trying to bring about? Answer as a short verb phrase
                naming the underlying goal, not the surface action — "Buy equipment",
                "Plan a trip", "Repair a relationship", "Track a symptom".

                Return nothing if the memory has no goal behind it.
            """.trimIndent()
        ),
        ExtractorSpec(
            id = "memory_type",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.MEMORY_TYPE),
            capability = Capability.CLASSIFICATION,
            outputShape = OutputShape.SINGLE_CHOICE,
            choices = MEMORY_TYPES,
            dependsOn = setOf("tasks", "decisions"),
            question = "What kind of memory is this?",
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "category",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.CATEGORY),
            capability = Capability.CLASSIFICATION,
            outputShape = OutputShape.SINGLE_CHOICE,
            choices = CATEGORIES,
            dependsOn = setOf("people", "activities", "organizations"),
            question = "Which area of the speaker's life does this memory belong to?",
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "priority",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.PRIORITY),
            capability = Capability.CLASSIFICATION,
            outputShape = OutputShape.SINGLE_CHOICE,
            choices = PRIORITIES,
            dependsOn = setOf("tasks", "temporal"),
            question = """
                How much does this memory matter to the speaker? Weigh urgency, whether
                anyone else is depending on it, and how much the speaker's own words
                signal that it is important.
            """.trimIndent(),
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "emotion",
            stage = Stage.INTERPRET,
            kinds = setOf(EvidenceKind.MOOD),
            capability = Capability.CLASSIFICATION,
            outputShape = OutputShape.SINGLE_CHOICE,
            choices = MOODS,
            question = """
                What feeling does this memory carry? Read the speaker's tone and stance,
                not only the words they use for emotions — a note about arranging
                something for one's child carries care even with no feeling word in it.

                Return nothing when the memory is genuinely neutral. A fabricated mood
                is worse than none.
            """.trimIndent(),
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "relationships",
            stage = Stage.INTERPRET,
            kinds = emptySet(),
            capability = Capability.REASONING,
            dependsOn = setOf("people", "places", "activities", "projects", "organizations"),
            question = """
                How are the things in this memory connected to each other? Return pairs
                drawn only from the entities already identified, each with the
                relationship between them — "Prabir | does | Swimming".

                Only state connections this memory actually supports.
            """.trimIndent(),
            outputShape = OutputShape.RELATIONS
        )
    )

    // ── NARRATE ───────────────────────────────────────────────────────
    // Last, deliberately. By now the summary has facts to work from rather
    // than a blank page, which is the difference between "remember to buy
    // swimming goggles for Prabir before swimming next week" and echoing the
    // sentence back.

    private val narrate = listOf(
        ExtractorSpec(
            id = "summary",
            stage = Stage.NARRATE,
            kinds = emptySet(),
            capability = Capability.REASONING,
            dependsOn = setOf("action_refinement", "people", "objects", "activities", "temporal"),
            question = """
                Write one sentence the speaker would find useful weeks from now, using
                the facts already extracted.

                Lead with what they need to do or remember, and include who and when.
                Write it to them, plainly. Do not repeat their sentence back, do not
                open with "This memory is about", and do not add anything the facts do
                not support.
            """.trimIndent(),
            outputShape = OutputShape.TEXT,
            textTarget = TextTarget.SUMMARY,
            hasHeuristicFloor = true
        ),
        ExtractorSpec(
            id = "title",
            stage = Stage.NARRATE,
            kinds = emptySet(),
            capability = Capability.REASONING,
            dependsOn = setOf("summary"),
            question = """
                Give this memory a title of two to five words — specific enough to
                recognise in a list. Name the thing it is about. No trailing
                punctuation, no quotes.
            """.trimIndent(),
            outputShape = OutputShape.TEXT,
            textTarget = TextTarget.TITLE,
            hasHeuristicFloor = true
        )
    )

    /** Every question, in execution order. */
    val all: List<ExtractorSpec> = prepare + ground + interpret + narrate

    val byId: Map<String, ExtractorSpec> = all.associateBy { it.id }

    fun stage(stage: Stage): List<ExtractorSpec> = all.filter { it.stage == stage }

    /** How many questions a memory gets asked — the denominator in "7/22". */
    val size: Int get() = all.size
}
