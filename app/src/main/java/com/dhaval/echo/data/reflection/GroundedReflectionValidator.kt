package com.dhaval.echo.data.reflection

import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionFlaw
import com.dhaval.echo.domain.reflection.ReflectionValidator
import com.dhaval.echo.domain.reflection.ReflectionVerdict
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks a written reflection against the brief it was supposed to come from.
 *
 * This exists because "the model was told not to" is not a guarantee. A model
 * writing about someone's life will occasionally introduce a name that was never
 * in the evidence, and in a diary that is the worst failure available — the user
 * cannot tell an invention from a memory they've forgotten.
 *
 * Tuned to be quiet. A false rejection costs a good reflection, so every rule
 * here answers "is this provably wrong", not "does this look odd": only capitalised
 * words that are clearly being used as names, only scaffolding that could not
 * appear in ordinary prose.
 */
@Singleton
class GroundedReflectionValidator @Inject constructor() : ReflectionValidator {

    override fun validate(text: String, brief: ReflectionBrief): ReflectionVerdict {
        val flaws = mutableListOf<ReflectionFlaw>()
        val notes = mutableListOf<String>()

        if (text.isBlank() || text.length < MIN_USEFUL_LENGTH) {
            flaws += ReflectionFlaw.EMPTY_OR_REPETITIVE
            notes += "too short (${text.length} chars)"
        } else if (isRepetitive(text)) {
            flaws += ReflectionFlaw.EMPTY_OR_REPETITIVE
            notes += "repeats itself"
        }

        val leaked = INTERNAL_MARKERS.filter { it in text }
        if (leaked.isNotEmpty()) {
            flaws += ReflectionFlaw.EXPOSED_INTERNALS
            notes += "leaked ${leaked.joinToString()}"
        }

        val invented = inventedSubjects(text, brief)
        if (invented.isNotEmpty()) {
            flaws += ReflectionFlaw.INVENTED_SUBJECT
            notes += "invented ${invented.joinToString()}"
        }

        return ReflectionVerdict(flaws, notes.takeIf { it.isNotEmpty() }?.joinToString("; "))
    }

    /**
     * Capitalised words used as subjects that the brief never mentioned.
     *
     * Deliberately conservative. Only mid-sentence capitals count, because a
     * sentence-initial capital carries no information; and a large stop list
     * absorbs the ordinary capitalised English — weekdays, months, "I" — that
     * would otherwise look like names.
     */
    private fun inventedSubjects(text: String, brief: ReflectionBrief): List<String> {
        val allowed = brief.vocabulary().map { it.lowercase() }.toSet()
        return MID_SENTENCE_CAPITAL.findAll(text)
            .map { it.groupValues[1] }
            .filter { it.lowercase() !in COMMON_CAPITALS }
            // A name that is part of an allowed multi-word entity ("New Delhi")
            // is fine on its own.
            .filter { candidate -> allowed.none { it.contains(candidate.lowercase()) } }
            .distinct()
            .take(MAX_REPORTED)
            .toList()
    }

    /** The same sentence, or near it, more than once. */
    private fun isRepetitive(text: String): Boolean {
        val sentences = text.split(Regex("""(?<=[.!?])\s+"""))
            .map { it.trim().lowercase() }
            .filter { it.length > 15 }
        if (sentences.size < 2) return false
        return sentences.size - sentences.distinct().size >= 1
    }

    private companion object {
        const val MIN_USEFUL_LENGTH = 40
        const val MAX_REPORTED = 5

        /** Scaffolding that cannot occur in ordinary prose about someone's week. */
        val INTERNAL_MARKERS = listOf(
            "Transcript:", "Memory:", "Summary:", "Date:",
            "Here is the relevant context", "Based on your memories about",
            "Themes, largest first", "Still open:", "Period:",
            "```", "{\"", "json"
        )

        val MID_SENTENCE_CAPITAL = Regex("""(?<![.!?]\s)(?<!^)(?<!\n)\b([A-Z][a-z]{2,})\b""")

        /** Capitalised English that is never a name. */
        val COMMON_CAPITALS = setOf(
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december",
            "echo", "you", "your", "this", "that", "there", "then", "they", "the",
            "and", "but", "for", "with", "what", "when", "where", "while", "which",
            "compared", "looking", "most", "much", "still", "also", "your", "over",
            "family", "work", "health", "finance", "travel", "social", "learning",
            "home", "personal", "week", "month", "year", "today", "yesterday",
            "tomorrow", "recently", "lately", "something", "someone", "nothing"
        )
    }
}
