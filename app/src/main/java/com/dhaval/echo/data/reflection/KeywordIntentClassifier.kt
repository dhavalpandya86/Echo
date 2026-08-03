package com.dhaval.echo.data.reflection

import com.dhaval.echo.domain.reflection.ReflectionIntent
import com.dhaval.echo.domain.reflection.ReflectionIntentClassifier
import com.dhaval.echo.domain.reflection.ReflectionWindow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stage 1: work out what kind of reflection was asked for, before retrieving
 * anything.
 *
 * Rules rather than a model, deliberately. This is a seven-way choice over short
 * questions with strong lexical tells ("forgetting", "feeling", "working on"),
 * which rules handle about as well as anything and in microseconds — and it runs
 * on every question including on the free tier, so it cannot depend on a model
 * being installed.
 *
 * Getting it wrong is cheap: the intent only steers *what evidence is gathered*,
 * and [ReflectionIntent.OPEN_QUESTION] retrieves broadly, so a misread question
 * degrades to a general answer rather than a wrong one.
 */
@Singleton
class KeywordIntentClassifier @Inject constructor() : ReflectionIntentClassifier {

    override fun classify(question: String): ReflectionIntent {
        val q = question.lowercase()

        // Ordered by how specific the signal is. "What am I forgetting about the
        // Echo project" is a commitment question that happens to name a project,
        // so commitments are tested before projects.
        return when {
            matches(q, "forget", "forgetting", "outstanding", "pending", "still need to",
                "haven't done", "have i missed", "owe", "promised", "supposed to") ->
                ReflectionIntent.COMMITMENT_ANALYSIS

            matches(q, "feeling", "feelings", "felt", "mood", "emotionally", "happy",
                "stressed", "anxious", "been feeling") ->
                ReflectionIntent.MOOD_ANALYSIS

            matches(q, "relationship", "how are things with", "how is it going with",
                "changed with", "between me and") ->
                ReflectionIntent.RELATIONSHIP_ANALYSIS

            matches(q, "working on", "projects", "project", "building", "shipping") ->
                ReflectionIntent.PROJECT_ANALYSIS

            matches(q, "weekly reflection", "monthly reflection", "my reflection",
                "reflect on", "look back", "review my") ->
                ReflectionIntent.PERIOD_REFLECTION

            matches(q, "thinking about", "on my mind", "themes", "patterns",
                "keep coming back", "preoccupied") ->
                ReflectionIntent.THEME_DISCOVERY

            matches(q, "what did i do", "what have i done", "this week", "last week",
                "today", "yesterday", "recently", "lately", "summarize", "summarise") ->
                ReflectionIntent.TIMELINE_SUMMARY

            else -> ReflectionIntent.OPEN_QUESTION
        }
    }

    private fun matches(q: String, vararg cues: String) = cues.any { it in q }

    companion object {
        /**
         * How far back a question reaches.
         *
         * Read from the question's own words where it says, and otherwise from
         * the intent: "what am I forgetting" should look across everything still
         * open, while "what did I do" means recently and nothing more.
         */
        fun windowFor(question: String, intent: ReflectionIntent): ReflectionWindow {
            val q = question.lowercase()
            val now = LocalDateTime.now()
            return when {
                "today" in q -> ReflectionWindow(now.toLocalDate().atStartOfDay(), "today")
                "yesterday" in q -> ReflectionWindow(now.minusDays(1).toLocalDate().atStartOfDay(), "yesterday")
                "this week" in q || "last week" in q -> ReflectionWindow(now.minusDays(7), "this week")
                "this month" in q || "last month" in q -> ReflectionWindow(now.minusDays(30), "this month")
                "this year" in q || "last year" in q -> ReflectionWindow(now.minusDays(365), "this year")
                intent == ReflectionIntent.COMMITMENT_ANALYSIS ->
                    ReflectionWindow(now.minusDays(180), "recently")
                intent == ReflectionIntent.RELATIONSHIP_ANALYSIS ->
                    ReflectionWindow(now.minusDays(365), "over the past year")
                intent == ReflectionIntent.PERIOD_REFLECTION ->
                    ReflectionWindow(now.minusDays(7), "this week")
                else -> ReflectionWindow(now.minusDays(30), "recently")
            }
        }
    }
}
