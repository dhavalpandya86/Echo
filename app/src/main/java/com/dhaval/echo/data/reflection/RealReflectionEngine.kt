package com.dhaval.echo.data.reflection

import android.util.Log
import com.dhaval.echo.domain.ai.AIManager
import com.dhaval.echo.domain.reflection.Reflection
import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionEngine
import com.dhaval.echo.domain.reflection.ReflectionIntentClassifier
import com.dhaval.echo.domain.reflection.ReflectionRetriever
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Reflection Engine: intent → retrieve → cluster → reason → narrate → cite.
 *
 * The ordering is the design. The previous implementation retrieved first and
 * never reasoned at all — it concatenated a preamble onto its own RAG context
 * block and showed that to the user, so "Reflect" was a retrieval debugger
 * wearing a chat interface.
 *
 * Two rules hold throughout:
 *  - **The narrator never sees a transcript.** It is given a structured brief:
 *    themes with counts, people, projects, what is still open. A narrator with
 *    transcripts restates them; a narrator with structure can conclude something.
 *  - **A failure never degrades into scaffolding.** If the cloud narrator fails
 *    the local one answers from the same brief, and if that has nothing to say
 *    the engine says so in a sentence. Prompt material is never user-visible.
 */
@Singleton
class RealReflectionEngine @Inject constructor(
    private val classifier: ReflectionIntentClassifier,
    private val retriever: ReflectionRetriever,
    private val validator: com.dhaval.echo.domain.reflection.ReflectionValidator,
    private val aiManager: AIManager
) : ReflectionEngine {

    private val localNarrator = LocalReflectionNarrator()

    override suspend fun reflect(question: String, userId: String): Reflection {
        // Stage 1 — decide what is being asked before looking anything up.
        val intent = classifier.classify(question)

        // Stages 2–3 — gather from the graph and group into themes.
        val brief = runCatching { retriever.brief(question, intent, userId) }
            .onFailure { Log.e(TAG, "Reflection retrieval failed", it) }
            .getOrNull()

        if (brief == null || brief.isEmpty) {
            return Reflection(
                text = "There's nothing recorded from ${brief?.window?.label ?: "that period"} " +
                    "for me to reflect on yet.",
                basis = null
            )
        }

        // Stages 4–5 — reason over the brief and write it. Cloud when the user
        // has connected one, rules otherwise; both read the same structured
        // brief, so the two tiers differ in fluency rather than in substance.
        val cloud = aiManager.getNarrativeService()?.let { CloudReflectionNarrator(it) }

        // Then check it. A model asked to write about someone's life will
        // occasionally add a name that was never there, and the user has no way
        // to tell that from a memory they've forgotten. A rejected answer falls
        // back to the rules-based narrator, which cannot invent anything because
        // it only ever assembles the brief's own vocabulary.
        val text = cloud?.narrate(brief)?.let { written ->
            val verdict = validator.validate(written, brief)
            if (verdict.isAcceptable) written else {
                Log.w(TAG, "Rejected model reflection: ${verdict.detail}")
                null
            }
        }
            ?: localNarrator.narrate(brief)
            ?: "I don't have enough from ${brief.window.label} to say anything useful yet."

        // Stage 6 — sources, after the reflection, as links rather than excerpts.
        return Reflection(
            text = text.trim(),
            sources = brief.sources,
            attention = attentionFrom(brief),
            basis = basisLine(brief)
        )
    }

    /**
     * The planner note: what deserves attention next.
     *
     * Deliberately sparing and deliberately separate from the reflection itself.
     * Advice attached to every answer becomes something people learn to skip, so
     * this returns null unless one of a few specific conditions genuinely holds —
     * something is late, or a theme has visibly gone quiet.
     */
    private fun attentionFrom(brief: ReflectionBrief): String? {
        val overdue = brief.openCommitments.filter { it.isOverdue }
        if (overdue.isNotEmpty()) {
            val first = overdue.first()
            val on = first.dueAtMillis
                ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DUE) }
            return buildString {
                append("\"").append(first.text).append("\" is still open")
                if (on != null) append(" and was due $on")
                append(".")
                if (overdue.size > 1) append(" ${overdue.size - 1} more like it.")
            }
        }

        brief.changes.firstOrNull { it.endsWith("has quietened") }?.let { shift ->
            val theme = shift.removeSuffix(" has quietened")
            return "$theme has gone quiet compared with before — worth a look if it still matters."
        }

        return null
    }

    /** The grey sub-line: what the reflection was built from, not how it was made. */
    private fun basisLine(brief: ReflectionBrief): String {
        val memories = if (brief.memoryCount == 1) "1 memory" else "${brief.memoryCount} memories"
        val themes = brief.themes.size
        return if (themes > 0) {
            "From $memories across ${if (themes == 1) "1 theme" else "$themes themes"}."
        } else {
            "From $memories."
        }
    }

    private companion object {
        const val TAG = "ReflectionEngine"
        val DUE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")
    }
}
