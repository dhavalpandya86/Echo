package com.dhaval.echo.data.understanding

import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.ExtractionContext

/**
 * The rules-based answers to the questions that produce text rather than facts.
 *
 * These are floors, not replacements. Each is honest about how little it can do
 * — [CleanupHeuristic] fixes punctuation but cannot fix a misheard word, and
 * [SummaryHeuristic] assembles facts rather than writing prose — and each stays
 * silent rather than inventing when it has nothing to offer.
 */

/**
 * Tidies a raw transcript: trims, collapses whitespace, capitalises sentence
 * starts and the first person, and adds a final full stop.
 *
 * Explicitly does *not* attempt to guess at misrecognised words. A rule that
 * rewrote "swimming glasses" to "swimming goggles" would be guessing from a
 * hardcoded list and would eventually mangle someone who really did mean
 * glasses. Correcting meaning is a reasoning job; this only fixes form.
 */
class CleanupHeuristic : com.dhaval.echo.data.understanding.TextHeuristic {

    private val fillers = Regex(
        """\b(?:um+|uh+|erm+|hmm+|you know|i mean|like,)\s*""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun answer(ctx: ExtractionContext): String? {
        val raw = ctx.text.trim()
        if (raw.isBlank()) return null

        var text = raw
            .replace(fillers, "")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""\s+([,.!?;:])"""), "$1")
            .trim()

        // Capitalise after sentence-enders, and the standalone "i".
        text = Regex("""(^|[.!?]\s+)([a-z])""").replace(text) {
            it.groupValues[1] + it.groupValues[2].uppercase()
        }
        text = Regex("""\bi\b""").replace(text, "I")

        if (text.isNotEmpty() && text.last() !in ".!?") text += "."

        // Returning null when nothing changed leaves `cleanedText` unset, so the
        // detail screen keeps showing the transcript rather than a duplicate.
        return text.takeIf { it != raw }
    }
}

/**
 * Builds a one-line summary out of the facts the pipeline already extracted.
 *
 * This replaces the old extractive summariser, which scored sentences by word
 * frequency and returned the top two — on a one-sentence memory that meant
 * handing the user their own sentence back, labelled "AI Summary".
 *
 * Assembling from facts is a smaller claim and a more useful one: it leads with
 * the commitment and names who and when, which is what someone actually needs
 * weeks later. When there are no facts to assemble it returns null rather than
 * echoing the text.
 */
class SummaryHeuristic : com.dhaval.echo.data.understanding.TextHeuristic {

    override suspend fun answer(ctx: ExtractionContext): String? {
        val task = ctx.of(EvidenceKind.TASK).maxByOrNull { it.confidence }
        val people = ctx.of(EvidenceKind.PERSON).map { it.value }.distinct()
        val activities = ctx.of(EvidenceKind.ACTIVITY).map { it.value }.distinct()
        val due = (ctx.of(EvidenceKind.TASK) + ctx.of(EvidenceKind.REMINDER))
            .mapNotNull { it.dueAtMillis }
            .minOrNull()

        if (task != null) {
            return buildString {
                append(task.value.replaceFirstChar { it.uppercase() }.trimEnd('.'))
                if (people.isNotEmpty() && people.none { task.value.contains(it, ignoreCase = true) }) {
                    append(" (with ").append(people.joinToString(", ")).append(")")
                }
                due?.let { append(" — by ").append(formatDue(it)) }
                append(".")
            }
        }

        // No commitment: describe what the memory is about instead of narrating it.
        val subjects = (people + activities).distinct()
        if (subjects.isEmpty()) return null
        return "About ${subjects.joinToString(", ")}."
    }

    private fun formatDue(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
}

/**
 * Names a memory after what it is about — the commitment, or the people and
 * activities in it — rather than after its most frequent words.
 */
class TitleHeuristic : com.dhaval.echo.data.understanding.TextHeuristic {

    override suspend fun answer(ctx: ExtractionContext): String? {
        val task = ctx.of(EvidenceKind.TASK).maxByOrNull { it.confidence }
        if (task != null) return task.value.trimEnd('.').take(48).trim()

        val subjects = (
            ctx.of(EvidenceKind.PERSON).map { it.value } +
                ctx.of(EvidenceKind.ACTIVITY).map { it.value } +
                ctx.of(EvidenceKind.PLACE).map { it.value }
            ).distinct().take(2)

        return subjects.takeIf { it.isNotEmpty() }?.joinToString(" & ")
    }
}

/**
 * Reports the language the transcriber already detected.
 *
 * Whisper returns a language code per segment and it is stored at transcription
 * time, so re-deriving it here would be guessing at something already known.
 * This exists so the question is represented in the registry and its run row is
 * recorded, not to do fresh work.
 */
class LanguageHeuristic : com.dhaval.echo.data.understanding.TextHeuristic {
    override suspend fun answer(ctx: ExtractionContext): String? = null
}
