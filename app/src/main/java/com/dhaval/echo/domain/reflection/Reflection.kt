package com.dhaval.echo.domain.reflection

import com.dhaval.echo.domain.ai.Citation
import java.time.LocalDateTime

/**
 * Echo's Reflection Engine.
 *
 * Reflecting is not answering a question about a document. People do not replay
 * their memories one by one — they compress, group, notice what recurs, and draw
 * a conclusion. So this is a pipeline with named stages, not a retrieval call
 * with a language model bolted to the end:
 *
 *   question → INTENT → RETRIEVE → CLUSTER → REASON → NARRATE → CITE
 *
 * The stage that matters most is CLUSTER. Handing a model twenty transcripts and
 * asking for a summary gets twenty things restated. Handing it *five themes with
 * counts, the people inside them, and what is still outstanding* gets something
 * that reads like a thought. The graph already holds all of that, so reflection
 * reasons over structure and never over raw text.
 *
 * That is also why the free tier can reflect at all: a brief made of counts and
 * names can be narrated by rules. Not as fluently as a model would, but honestly
 * and usefully — which the previous implementation did not manage, since it
 * printed its own prompt.
 */

/**
 * What kind of reflection was asked for. Decides what is retrieved and how it is
 * reasoned over — a question about feelings and a question about commitments
 * need different evidence, and searching before knowing which is the mistake the
 * old path made.
 */
enum class ReflectionIntent {
    /** "What did I do this week?" — what happened, in order. */
    TIMELINE_SUMMARY,

    /** "What have I been thinking about?" — recurring themes. */
    THEME_DISCOVERY,

    /** "What am I forgetting?" — open commitments, oldest first. */
    COMMITMENT_ANALYSIS,

    /** "How have I been feeling?" — mood over the window. */
    MOOD_ANALYSIS,

    /** "What am I working on?" — projects and their momentum. */
    PROJECT_ANALYSIS,

    /** "How are things with Komal?" — one person across time. */
    RELATIONSHIP_ANALYSIS,

    /** "Give me my weekly reflection" — everything, compared to before. */
    PERIOD_REFLECTION,

    /** Anything else. Retrieves semantically and answers narrowly. */
    OPEN_QUESTION
}

/** The stretch of time a reflection covers. */
data class ReflectionWindow(
    val since: LocalDateTime,
    /** How the window is said out loud: "this week", "the last month". */
    val label: String
) {
    /** The window immediately before this one, for "compared to…". */
    fun previous(): ReflectionWindow {
        val span = java.time.Duration.between(since, LocalDateTime.now())
        return ReflectionWindow(since.minus(span), "the period before")
    }
}

/** A name and how often it came up. The unit the engine counts in. */
data class NamedCount(val name: String, val count: Int, val type: String = "")

/**
 * A group of memories that belong together — "Family", "Echo".
 *
 * Built from the CATEGORY facet where memories have one, and from the entity
 * graph where they don't, so a theme is something the pipeline concluded rather
 * than a keyword that happened to repeat.
 */
data class Theme(
    val name: String,
    val memoryCount: Int,
    /**
     * How much this mattered, 0..1 — **decided by Echo, never by the model.**
     *
     * Volume is only part of it: a theme carrying an overdue commitment matters
     * more than a busier one that carries none. Handing a model raw counts and
     * letting it infer importance is exactly the thing that makes two providers
     * answer differently, so the judgement is made here and passed in.
     */
    val importance: Float = 0f,
    /** One line saying what this theme *is*, composed by Echo from its contents. */
    val summary: String = "",
    /** Who and what this theme is made of, most-mentioned first. */
    val entities: List<NamedCount> = emptyList(),
    val memoryIds: List<String> = emptyList()
)

/** An outstanding commitment, with enough context to be actionable. */
data class CommitmentBrief(
    val memoryId: String,
    val text: String,
    val dueAtMillis: Long? = null,
    val isOverdue: Boolean = false
)

/**
 * Everything the narrator is allowed to know — structure, never transcripts.
 *
 * This is the contract that keeps reflection honest. A narrator handed only
 * counts, names and themes cannot quote a memory it half-remembers, and cannot
 * pad an answer with the user's own words fed back to them.
 */
data class ReflectionBrief(
    val question: String,
    val intent: ReflectionIntent,
    val window: ReflectionWindow,
    val memoryCount: Int,
    val themes: List<Theme> = emptyList(),
    val people: List<NamedCount> = emptyList(),
    val projects: List<NamedCount> = emptyList(),
    val activities: List<NamedCount> = emptyList(),
    val moods: List<NamedCount> = emptyList(),
    val openCommitments: List<CommitmentBrief> = emptyList(),
    /** The same themes over the preceding window — what makes "less than before" sayable. */
    val previousThemes: List<Theme> = emptyList(),
    /**
     * What changed against the previous period, already decided.
     *
     * Materialised rather than left for the model to spot: comparing two sets of
     * counts is arithmetic, and arithmetic is Echo's job. A model asked to
     * notice a trend will sometimes notice one that isn't there.
     */
    val changes: List<String> = emptyList(),
    val sources: List<Citation> = emptyList()
) {
    val isEmpty: Boolean get() = memoryCount == 0

    /**
     * Every proper noun the narrator is allowed to use.
     *
     * The validator checks the written reflection against this: a name that
     * appears in the output but not here was invented, and an invented person in
     * someone's diary is the worst thing this feature could do.
     */
    fun vocabulary(): Set<String> = buildSet {
        themes.forEach { theme ->
            add(theme.name)
            theme.entities.forEach { add(it.name) }
        }
        people.forEach { add(it.name) }
        projects.forEach { add(it.name) }
        activities.forEach { add(it.name) }
        moods.forEach { add(it.name) }
    }
}

/**
 * The reflection itself, plus what it rests on.
 *
 * [attention] is deliberately separate from [text]. A reflection that always
 * ends in advice becomes noise people learn to skip; this is only populated when
 * something genuinely warrants it — a commitment past its date, an important
 * project that has gone quiet, a focus that has visibly moved.
 */
data class Reflection(
    val text: String,
    val sources: List<Citation> = emptyList(),
    val attention: String? = null,
    /** For the grey sub-line: what this was built from. */
    val basis: String? = null
)

/** Stage 1. */
fun interface ReflectionIntentClassifier {
    fun classify(question: String): ReflectionIntent
}

/** Stages 2–3: gather evidence and group it. */
interface ReflectionRetriever {
    suspend fun brief(question: String, intent: ReflectionIntent, userId: String): ReflectionBrief
}

/** Stages 4–5: reason over the brief and write it. */
interface ReflectionNarrator {
    /** Null when this narrator cannot answer, so the caller can fall back. */
    suspend fun narrate(brief: ReflectionBrief): String?
}

/** Why a written reflection was rejected. */
enum class ReflectionFlaw {
    /** Named a person, project or place that is not in the brief. */
    INVENTED_SUBJECT,

    /** Leaked internal scaffolding — "Transcript:", "Memory:", JSON, headings. */
    EXPOSED_INTERNALS,

    /** Said almost nothing, or said the same thing repeatedly. */
    EMPTY_OR_REPETITIVE
}

data class ReflectionVerdict(
    val flaws: List<ReflectionFlaw> = emptyList(),
    /** What was wrong, for the log — never shown to the user. */
    val detail: String? = null
) {
    val isAcceptable: Boolean get() = flaws.isEmpty()
}

/**
 * The step after the model, and the reason a provider swap stays safe.
 *
 * A language model asked to write about someone's life will occasionally add a
 * name that was never there, or echo back the structure it was given. Neither is
 * acceptable in a diary, and neither is detectable by reading the prompt — only
 * by checking the output against the brief it was supposed to come from.
 *
 * Echo decides what is true; the model only decides how to say it. This is where
 * that rule is enforced rather than merely intended.
 */
fun interface ReflectionValidator {
    fun validate(text: String, brief: ReflectionBrief): ReflectionVerdict
}

/** The whole pipeline. */
interface ReflectionEngine {
    suspend fun reflect(question: String, userId: String): Reflection
}
