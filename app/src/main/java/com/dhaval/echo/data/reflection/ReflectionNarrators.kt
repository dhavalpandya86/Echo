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
            appendLine("Themes, largest first:")
            brief.themes.forEach { theme ->
                append("- ${theme.name}: ${theme.memoryCount} memories")
                if (theme.entities.isNotEmpty()) {
                    append(" — ").append(theme.entities.joinToString(", ") { "${it.name} (${it.count})" })
                }
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

        val shifts = brief.shifts()
        if (shifts.isNotEmpty()) {
            appendLine()
            appendLine("Compared with the period before: ${shifts.joinToString("; ")}.")
        }
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

        val instruction = buildString {
            appendLine(instructionFor(brief.intent, brief.question))
            appendLine()
            appendLine(
                "Write two or three short paragraphs, speaking to them directly as someone " +
                    "who remembers their life. Draw a conclusion — say what the period was " +
                    "about, what connects, and what changed. Do not list the memories back, " +
                    "do not use bullet points, and do not mention counts as numbers."
            )
            appendLine(
                "Everything below is already established fact. Do not invent people, " +
                    "projects or events that are not in it. If it is too thin to say " +
                    "anything meaningful, say that plainly and briefly."
            )
        }

        return runCatching {
            narrator.narrate(instruction, BriefFormatter.render(brief), maxTokens = 700)
        }.onFailure { Log.w(TAG, "Cloud reflection failed", it) }.getOrNull()
    }

    private fun instructionFor(intent: ReflectionIntent, question: String): String = when (intent) {
        ReflectionIntent.TIMELINE_SUMMARY ->
            "The user asked: \"$question\". Tell them what their period was actually spent on."
        ReflectionIntent.THEME_DISCOVERY ->
            "The user asked: \"$question\". Tell them what has been occupying their mind, and " +
                "what the themes have in common."
        ReflectionIntent.COMMITMENT_ANALYSIS ->
            "The user asked: \"$question\". Tell them what they have left undone, most pressing " +
                "first, and be direct about anything past its date."
        ReflectionIntent.MOOD_ANALYSIS ->
            "The user asked: \"$question\". Describe how they have been feeling and what those " +
                "feelings attach to. Be careful and kind; do not diagnose."
        ReflectionIntent.PROJECT_ANALYSIS ->
            "The user asked: \"$question\". Tell them which projects are moving, which have gone " +
                "quiet, and where their attention actually went."
        ReflectionIntent.RELATIONSHIP_ANALYSIS ->
            "The user asked: \"$question\". Describe how this person has featured in their life " +
                "and whether that has changed."
        ReflectionIntent.PERIOD_REFLECTION ->
            "The user asked for their reflection on \"$question\". Give them the shape of the " +
                "period: what dominated, what shifted, what is unresolved."
        ReflectionIntent.OPEN_QUESTION ->
            "The user asked: \"$question\". Answer from what is known below, and say so if it " +
                "does not really cover the question."
    }

    private companion object { const val TAG = "CloudReflection" }
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

        val shifts = brief.shifts()
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
        val quiet = brief.shifts().filter { it.contains("quietened") }
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
