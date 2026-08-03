package com.dhaval.echo.data.reflection

import android.util.Log
import com.dhaval.echo.domain.ai.NarrativeService
import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionIntent
import com.dhaval.echo.domain.reflection.ReflectionNarrator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders a [ReflectionBrief] as compact structured text for a language model.
 *
 * This is the whole reason reflection improved: the model is handed *themes,
 * counts, people and open commitments* rather than a wall of transcripts. Given
 * transcripts a model restates them; given structure it can notice that one
 * theme grew while another went quiet, which is the thing a person actually
 * wants from reflecting.
 *
 * It also bounds cost. A brief is a few hundred tokens no matter how much the
 * user recorded, where twenty transcripts is unbounded and mostly redundant.
 */
internal object BriefFormatter {

    private val DUE = DateTimeFormatter.ofPattern("d MMM")

    fun render(brief: ReflectionBrief): String = buildString {
        appendLine("Period: ${brief.window.label} (${brief.memoryCount} memories)")

        if (brief.themes.isNotEmpty()) {
            appendLine()
            // Ordered by the importance Echo assigned, and labelled in words
            // rather than numbers. A model handed "0.91" will sometimes quote it
            // back; handed "dominant" it writes prose. The ranking decision has
            // already been made either way — this only controls how it reads.
            appendLine("What this period was about, most important first:")
            brief.themes.forEach { theme ->
                append("- ${theme.name} (${weightLabel(theme.importance)})")
                if (theme.summary.isNotBlank()) append(": ${theme.summary}")
                appendLine()
            }
        }

        section("People mentioned", brief.people.map { "${it.name} (${it.count})" })
        section("Projects and topics", brief.projects.map { "${it.name} (${it.count})" })
        section("Activities", brief.activities.map { "${it.name} (${it.count})" })
        section("Feelings expressed", brief.moods.map { "${it.name} (${it.count})" })

        if (brief.openCommitments.isNotEmpty()) {
            appendLine()
            appendLine("Still open:")
            brief.openCommitments.forEach { c ->
                append("- ").append(c.text)
                c.dueAtMillis?.let {
                    val on = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DUE)
                    append(if (c.isOverdue) " (was due $on, still open)" else " (due $on)")
                }
                appendLine()
            }
        }

        if (brief.changes.isNotEmpty()) {
            appendLine()
            appendLine("What changed since the period before: ${brief.changes.joinToString("; ")}.")
        }
    }

    /** Words, not scores — see the note at the theme block. */
    private fun weightLabel(importance: Float): String = when {
        importance >= 0.75f -> "dominant"
        importance >= 0.45f -> "significant"
        else -> "minor"
    }

    private fun StringBuilder.section(title: String, values: List<String>) {
        if (values.isEmpty()) return
        appendLine()
        appendLine("$title: ${values.joinToString(", ")}")
    }
}

/**
 * Stage 4–5 with a language model.
 *
 * The instruction is written per intent, because the reasoning differs: a
 * commitment question wants what is outstanding and how late, a mood question
 * wants the shape of a feeling over time. One generic "summarise this" prompt
 * would flatten all of them back into a list.
 */
class CloudReflectionNarrator(
    private val narrator: NarrativeService
) : ReflectionNarrator {

    override suspend fun narrate(brief: ReflectionBrief): String? {
        if (brief.isEmpty) return null

        // Layer 1 (voice, never changes) + Layer 2 (the task, one line).
        // Layer 3 is the structured context, passed separately.
        val instruction = "$SYSTEM_VOICE\n\n${taskFor(brief.intent)}\nThey asked: \"${brief.question}\""

        return runCatching {
            narrator.narrate(instruction, BriefFormatter.render(brief), maxTokens = 700)
        }.onFailure { Log.w(TAG, "Cloud reflection failed", it) }.getOrNull()
    }

    /**
     * Layer 2: what kind of reflection this is. One line, and no product logic.
     *
     * Everything these used to say — which projects went quiet, what is overdue,
     * what changed — is now decided by Echo and arrives in the context as fact.
     * The model is told what it is writing, never how to work out what matters:
     * an instruction like "mention anything past its date" makes the answer
     * depend on how well a given provider follows instructions, which is exactly
     * how two models end up behaving differently on the same memories.
     */
    private fun taskFor(intent: ReflectionIntent): String = when (intent) {
        ReflectionIntent.TIMELINE_SUMMARY -> "Write a reflection on what this period was spent on."
        ReflectionIntent.THEME_DISCOVERY -> "Write a reflection on what has been occupying their mind."
        ReflectionIntent.COMMITMENT_ANALYSIS -> "Write a reflection on what is still unresolved."
        ReflectionIntent.MOOD_ANALYSIS -> "Write a reflection on how they have been feeling. Be kind; never diagnose."
        ReflectionIntent.PROJECT_ANALYSIS -> "Write a reflection on where their work has gone."
        ReflectionIntent.RELATIONSHIP_ANALYSIS -> "Write a reflection on this person's place in their life."
        ReflectionIntent.PERIOD_REFLECTION -> "Write their reflection for this period."
        ReflectionIntent.OPEN_QUESTION -> "Answer their question from what is known."
    }

    private companion object {
        const val TAG = "CloudReflection"

        /**
         * Layer 1: Echo's voice and its limits. Identical for every intent and
         * every provider, so switching models changes the writing and nothing
         * about what Echo will and won't do.
         */
        const val SYSTEM_VOICE =
            "You are Echo. You help someone understand their own life.\n" +
                "You have already been given a structured understanding of their memories — " +
                "it is complete, and it is the only thing you know.\n" +
                "Never invent a person, project or event that is not in it. Never quote or " +
                "restate individual memories. Never mention counts, scores, field names or " +
                "that you were given data at all.\n" +
                "Write two or three short paragraphs of natural prose, no lists. Reach a " +
                "conclusion rather than describing. If there is too little to say something " +
                "meaningful, say that briefly and stop."
    }
}

/**
 * Stage 4–5 without a model.
 *
 * This exists because the free tier is the default tier, and the previous
 * implementation's idea of an on-device answer was to print its own prompt. A
 * brief made of counts and names can be narrated by rules into something true
 * and genuinely useful — less fluent than a model, but it reaches a conclusion
 * rather than restating the inputs.
 *
 * It says only what the brief supports, and stops. Where it cannot reason it
 * stays quiet rather than padding.
 */
class LocalReflectionNarrator : ReflectionNarrator {

    private val due = DateTimeFormatter.ofPattern("d MMM")

    override suspend fun narrate(brief: ReflectionBrief): String? {
        if (brief.isEmpty) return null

        val parts = when (brief.intent) {
            ReflectionIntent.COMMITMENT_ANALYSIS -> commitments(brief)
            ReflectionIntent.MOOD_ANALYSIS -> moods(brief)
            ReflectionIntent.RELATIONSHIP_ANALYSIS -> people(brief)
            ReflectionIntent.PROJECT_ANALYSIS -> projects(brief)
            else -> overview(brief)
        }
        return parts.filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
    }

    private fun overview(brief: ReflectionBrief): List<String> {
        val out = mutableListOf<String>()
        val top = brief.themes.take(2)

        out += when {
            top.isEmpty() ->
                "You captured ${count(brief.memoryCount, "memory", "memories")} ${brief.window.label}."
            top.size == 1 ->
                "Most of ${brief.window.label} was ${top[0].name.lowercase()} — " +
                    "${count(top[0].memoryCount, "memory", "memories")} of ${brief.memoryCount}."
            else ->
                "${brief.window.label.replaceFirstChar { it.uppercase() }} was mostly two things: " +
                    "${top[0].name.lowercase()} and ${top[1].name.lowercase()}."
        }

        top.firstOrNull { it.entities.isNotEmpty() }?.let { theme ->
            out += "Around ${theme.name.lowercase()}, ${listOf(theme.entities.map { it.name }).joinNaturally()} " +
                "came up most."
        }

        brief.people.firstOrNull()?.let { person ->
            if (person.count >= 2) {
                out += "${person.name} appeared in ${count(person.count, "memory", "memories")}."
            }
        }

        val shifts = brief.changes
        if (shifts.isNotEmpty()) {
            out += "Compared with before, ${shifts.joinToString(", ")}."
        }

        if (brief.openCommitments.isNotEmpty()) {
            out += "You still have ${count(brief.openCommitments.size, "commitment", "commitments")} open."
        }
        return out
    }

    private fun commitments(brief: ReflectionBrief): List<String> {
        if (brief.openCommitments.isEmpty()) {
            return listOf("Nothing is outstanding from ${brief.window.label} — everything you noted is done or dismissed.")
        }
        val overdue = brief.openCommitments.filter { it.isOverdue }
        val out = mutableListOf<String>()
        out += "You have ${count(brief.openCommitments.size, "thing", "things")} still open."
        if (overdue.isNotEmpty()) {
            out += "${count(overdue.size, "one", "of them")} already past its date: " +
                overdue.take(3).joinToString("; ") { it.text } + "."
        }
        val upcoming = brief.openCommitments.filter { !it.isOverdue }.take(3)
        if (upcoming.isNotEmpty()) {
            out += "Also waiting: " + upcoming.joinToString("; ") { c ->
                val when0 = c.dueAtMillis?.let {
                    " by " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(due)
                } ?: ""
                c.text + when0
            } + "."
        }
        return out
    }

    private fun moods(brief: ReflectionBrief): List<String> {
        if (brief.moods.isEmpty()) {
            return listOf(
                "You didn't record much about how you were feeling ${brief.window.label} — " +
                    "your memories were mostly about what happened rather than how it landed."
            )
        }
        val out = mutableListOf<String>()
        val top = brief.moods.first()
        out += "${brief.window.label.replaceFirstChar { it.uppercase() }} read mostly as ${top.name.lowercase()}."
        if (brief.moods.size > 1) {
            out += "You also noted " + listOf(brief.moods.drop(1).take(3).map { it.name.lowercase() }).joinNaturally() + "."
        }
        brief.themes.firstOrNull()?.let { out += "Most of that sat around ${it.name.lowercase()}." }
        return out
    }

    private fun projects(brief: ReflectionBrief): List<String> {
        if (brief.projects.isEmpty()) {
            return listOf("No named projects came up ${brief.window.label}.")
        }
        val out = mutableListOf<String>()
        val top = brief.projects.first()
        out += "${top.name} took most of your attention ${brief.window.label}, across " +
            "${count(top.count, "memory", "memories")}."
        if (brief.projects.size > 1) {
            out += "You also touched " + listOf(brief.projects.drop(1).take(3).map { it.name }).joinNaturally() + "."
        }
        val quiet = brief.changes.filter { it.contains("quietened") }
        if (quiet.isNotEmpty()) out += quiet.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
        return out
    }

    private fun people(brief: ReflectionBrief): List<String> {
        if (brief.people.isEmpty()) {
            return listOf("No one came up by name ${brief.window.label}.")
        }
        val out = mutableListOf<String>()
        val top = brief.people.first()
        out += "${top.name} came up most ${brief.window.label} — " +
            "${count(top.count, "memory", "memories")}."
        brief.themes.firstOrNull { theme -> theme.entities.any { it.name == top.name } }?.let {
            out += "Usually around ${it.name.lowercase()}."
        }
        if (brief.people.size > 1) {
            out += "You also mentioned " + listOf(brief.people.drop(1).take(3).map { it.name }).joinNaturally() + "."
        }
        return out
    }

    private fun count(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    /** "a, b and c" — the small courtesy that keeps generated prose readable. */
    private fun List<List<String>>.joinNaturally(): String {
        val items = flatten()
        return when (items.size) {
            0 -> ""
            1 -> items[0]
            2 -> "${items[0]} and ${items[1]}"
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
