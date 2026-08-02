package com.dhaval.echo.data.understanding

import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.ExtractorRegistry

/**
 * A rules-based answer to a question that needs to see earlier answers.
 *
 * [com.dhaval.echo.domain.understanding.MemoryAnalyzer]s are deliberately blind
 * — they only ever see the memory's text. That is right for grounding, but the
 * interpretive facets are defined in terms of what grounding found: a memory is
 * a Commitment *because* a task was extracted from it, and it is Family *because*
 * the people in it are family. Those questions get this shape instead.
 */
fun interface ContextualHeuristic {
    suspend fun answer(ctx: ExtractionContext): List<Evidence>
}

/** Emits a single facet verdict, or nothing. */
private fun facet(kind: EvidenceKind, value: String, evidence: String?, confidence: Float) =
    listOf(Evidence(kind, value, evidence, confidence))

/**
 * What kind of memory this is, decided from what grounding already found.
 *
 * Reading the facts rather than the text is what makes this reliable without a
 * model: "is there a task?" is a far sharper question than "does this sentence
 * sound like a commitment?", and the task extractor has already answered it.
 */
class MemoryTypeHeuristic : ContextualHeuristic {

    private val learningCues = listOf("learned", "learnt", "realised", "realized", "turns out", "found out")
    private val ideaCues = listOf("idea", "what if", "maybe we", "we could", "thinking of", "thinking about")
    private val conversationCues = listOf("said", "told me", "talked to", "spoke to", "we discussed", "asked me")
    private val reflectionCues = listOf("i feel", "i felt", "i've been", "i have been", "looking back", "i wonder")
    private val pastCues = listOf("went", "was", "were", "had a", "saw", "met", "visited", "yesterday", "last night")

    override suspend fun answer(ctx: ExtractionContext): List<Evidence> {
        val lower = ctx.text.lowercase()
        val kind = EvidenceKind.MEMORY_TYPE

        // Order matters: a memory that both recalls a conversation and commits
        // to something is, for the user's purposes, the commitment — that is the
        // part with a consequence they need to be reminded of.
        if (ctx.of(EvidenceKind.TASK).isNotEmpty()) {
            return facet(kind, "Commitment", ctx.of(EvidenceKind.TASK).first().evidenceText, 0.7f)
        }
        if (ctx.of(EvidenceKind.DECISION).isNotEmpty()) {
            return facet(kind, "Decision", ctx.of(EvidenceKind.DECISION).first().evidenceText, 0.7f)
        }
        learningCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Learning", sentenceAround(ctx.text, it), 0.55f) }
        ideaCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Idea", sentenceAround(ctx.text, it), 0.55f) }
        conversationCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Conversation", sentenceAround(ctx.text, it), 0.5f) }
        reflectionCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Reflection", sentenceAround(ctx.text, it), 0.5f) }
        pastCues.firstOrNull { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(lower) }
            ?.let { return facet(kind, "Experience", sentenceAround(ctx.text, it), 0.45f) }

        return facet(kind, "Observation", null, 0.35f)
    }
}

/**
 * Which area of life this belongs to.
 *
 * Decided mostly from *who and what* the memory involves rather than its
 * wording — a note naming a kinship word and a child's activity is Family
 * whatever else it says. Falls back to topic words only when the entities are
 * silent.
 */
class CategoryHeuristic : ContextualHeuristic {

    private val familyCues = listOf(
        "my son", "my daughter", "my kid", "my child", "my wife", "my husband",
        "my mom", "my mother", "my dad", "father", "my brother", "my sister",
        "my family", "my parents", "grandma", "grandpa", "school run"
    )
    private val workCues = listOf(
        "client", "meeting", "deadline", "project", "manager", "team", "office",
        "invoice", "proposal", "standup", "appraisal", "colleague"
    )
    private val healthCues = listOf(
        "doctor", "dentist", "medicine", "hospital", "clinic", "symptom",
        "blood", "prescription", "physio", "appointment with dr"
    )
    private val financeCues = listOf("budget", "salary", "loan", "emi", "insurance", "tax", "investment", "bank")
    private val travelCues = listOf("flight", "trip", "hotel", "visa", "itinerary", "airport", "booking")
    private val learningCues = listOf("course", "study", "exam", "syllabus", "tuition", "homework", "lesson")
    private val homeCues = listOf("renovation", "plumber", "electrician", "rent", "landlord", "repair")
    private val socialCues = listOf("party", "wedding", "dinner with", "catch up", "get-together", "birthday")

    /**
     * Activities that, when someone recurrently turns up alongside them, mark
     * them as a dependent rather than a colleague.
     */
    private val familyOrbit = setOf("School", "Swimming", "Tuition", "Exam", "Birthday", "Music practice")

    override suspend fun answer(ctx: ExtractionContext): List<Evidence> {
        val lower = ctx.text.lowercase()
        val kind = EvidenceKind.CATEGORY

        // A named family relationship is the strongest signal there is.
        familyCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Family", sentenceAround(ctx.text, it), 0.7f) }

        // Nothing in *this* memory says "my son" — but the graph may already
        // know that the person named here recurs alongside school and swimming.
        // This is the ENRICH stage paying for itself on the free tier: a memory
        // that reads as context-free in isolation is placed correctly because
        // Echo remembers the ones before it.
        ctx.enrichment.of(com.dhaval.echo.data.db.EntityType.PERSON)
            .firstOrNull { person ->
                person.isFamiliar && person.relatedNames.any { it in familyOrbit }
            }
            ?.let { person ->
                return facet(
                    kind,
                    "Family",
                    "${person.name} usually appears with ${person.relatedNames.joinToString(", ")}",
                    0.65f
                )
            }

        val activities = ctx.of(EvidenceKind.ACTIVITY).map { it.value }
        when {
            activities.any { it in setOf("Doctor appointment", "Dentist appointment", "Gym", "Yoga", "Running") } ->
                return facet(kind, "Health", null, 0.6f)
            activities.any { it in setOf("Meeting", "Interview") } ->
                return facet(kind, "Work", null, 0.6f)
            activities.any { it in setOf("School", "Tuition", "Exam") } ->
                return facet(kind, "Learning", null, 0.6f)
            activities.any { it in setOf("Travel") } ->
                return facet(kind, "Travel", null, 0.6f)
            activities.any { it in setOf("Party", "Wedding", "Birthday", "Movie") } ->
                return facet(kind, "Social", null, 0.6f)
        }

        // An organisation in play, with no family signal, reads as work.
        if (ctx.of(EvidenceKind.ORG).isNotEmpty() || ctx.of(EvidenceKind.PROJECT).isNotEmpty()) {
            return facet(kind, "Work", null, 0.5f)
        }

        listOf(
            "Health" to healthCues, "Work" to workCues, "Finance" to financeCues,
            "Travel" to travelCues, "Learning" to learningCues,
            "Home" to homeCues, "Social" to socialCues
        ).forEach { (category, cues) ->
            cues.firstOrNull { it in lower }
                ?.let { return facet(kind, category, sentenceAround(ctx.text, it), 0.5f) }
        }

        return facet(kind, "Personal", null, 0.35f)
    }
}

/**
 * How much this matters.
 *
 * Three signals, in order of how much they actually predict importance:
 * explicit urgency words, how soon a commitment falls due, and whether anyone
 * else is depending on it. A memory with no commitment at all sits at Low —
 * nothing is riding on it.
 */
class PriorityHeuristic : ContextualHeuristic {

    private val urgentCues = listOf(
        "urgent", "asap", "immediately", "critical", "important", "must not forget",
        "don't forget", "last chance", "deadline", "final reminder", "emergency"
    )
    private val lowCues = listOf("someday", "eventually", "at some point", "no rush", "whenever", "if i get time")

    override suspend fun answer(ctx: ExtractionContext): List<Evidence> {
        val lower = ctx.text.lowercase()
        val kind = EvidenceKind.PRIORITY

        urgentCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "High", sentenceAround(ctx.text, it), 0.7f) }
        lowCues.firstOrNull { it in lower }
            ?.let { return facet(kind, "Low", sentenceAround(ctx.text, it), 0.6f) }

        val commitments = ctx.of(EvidenceKind.TASK) + ctx.of(EvidenceKind.REMINDER)
        if (commitments.isEmpty()) return facet(kind, "Low", null, 0.4f)

        val soonest = commitments.mapNotNull { it.dueAtMillis }.minOrNull()
            ?: // A commitment with no date still matters more than an idle note,
               // but there is nothing pressing about it.
            return facet(kind, "Medium", null, 0.45f)

        val capturedAtMillis = ctx.content.capturedAt
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val daysAway = (soonest - capturedAtMillis) / MILLIS_PER_DAY

        // Someone else waiting on it raises the floor: a commitment involving
        // another person is one you can let *them* down by forgetting.
        val involvesOthers = ctx.of(EvidenceKind.PERSON).isNotEmpty()

        val level = when {
            daysAway <= 1 -> "High"
            daysAway <= 7 -> if (involvesOthers) "High" else "Medium"
            else -> if (involvesOthers) "Medium" else "Low"
        }
        return facet(kind, level, commitments.first().evidenceText, 0.55f)
    }

    private companion object { const val MILLIS_PER_DAY = 86_400_000L }
}

/**
 * A safety net so a rules-answered memory never claims a facet value the
 * registry does not know about — an off-vocabulary facet cannot group, which
 * defeats the point of having facets at all.
 */
internal fun Evidence.withinVocabulary(): Boolean = when (kind) {
    EvidenceKind.MEMORY_TYPE -> value in ExtractorRegistry.MEMORY_TYPES
    EvidenceKind.CATEGORY -> value in ExtractorRegistry.CATEGORIES
    EvidenceKind.PRIORITY -> value in ExtractorRegistry.PRIORITIES
    EvidenceKind.MOOD -> value in ExtractorRegistry.MOODS
    else -> true
}

private val SENTENCES = Regex("""(?<=[.!?\n])\s+""")

private fun sentenceAround(text: String, needle: String): String? =
    text.split(SENTENCES)
        .map { it.trim() }
        .firstOrNull { it.contains(needle, ignoreCase = true) }
        ?.take(160)
