package com.dhaval.echo.reflection

import com.dhaval.echo.data.reflection.KeywordIntentClassifier
import com.dhaval.echo.data.reflection.LocalReflectionNarrator
import com.dhaval.echo.domain.reflection.CommitmentBrief
import com.dhaval.echo.domain.reflection.NamedCount
import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionIntent
import com.dhaval.echo.domain.reflection.ReflectionWindow
import com.dhaval.echo.domain.reflection.Theme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Reflect used to answer with its own prompt.
 *
 * Asking "Summarize what I've been thinking about this week" returned "Here is
 * the relevant context from the user's memories: Memory: … Date: … Transcript:
 * …" — the RAG block, rendered as if it were the reply.
 *
 * These tests pin the two things that make that impossible now: the narrator is
 * only ever given a structured brief (so it has no transcript to leak), and it
 * reaches a conclusion rather than restating its inputs.
 */
class ReflectionNarratorTest {

    private val window = ReflectionWindow(LocalDateTime.now().minusDays(7), "this week")

    private fun brief(
        intent: ReflectionIntent = ReflectionIntent.TIMELINE_SUMMARY,
        themes: List<Theme> = emptyList(),
        people: List<NamedCount> = emptyList(),
        commitments: List<CommitmentBrief> = emptyList(),
        moods: List<NamedCount> = emptyList(),
        changes: List<String> = emptyList(),
        count: Int = 10
    ) = ReflectionBrief(
        question = "what did I focus on this week?",
        intent = intent,
        window = window,
        memoryCount = count,
        themes = themes,
        people = people,
        moods = moods,
        openCommitments = commitments,
        // Comparisons arrive already decided — the retriever does the arithmetic
        // so neither narrator has to infer a trend.
        changes = changes
    )

    @Test
    fun a_reflection_never_contains_prompt_scaffolding() = runBlocking {
        val text = LocalReflectionNarrator().narrate(
            brief(
                themes = listOf(
                    Theme("Family", 5, entities = listOf(NamedCount("Prabir", 4), NamedCount("Swimming", 2))),
                    Theme("Work", 12, entities = listOf(NamedCount("Echo", 12)))
                ),
                people = listOf(NamedCount("Prabir", 4))
            )
        )
        assertNotNull(text)
        // The exact strings the old implementation leaked.
        listOf("Memory:", "Transcript:", "Summary:", "Here is the relevant context", "Based on your memories about")
            .forEach { assertFalse("leaked '$it': $text", text!!.contains(it)) }
    }

    @Test
    fun it_names_the_themes_rather_than_the_memories() = runBlocking {
        val text = LocalReflectionNarrator().narrate(
            brief(
                themes = listOf(
                    Theme("Work", 12, entities = listOf(NamedCount("Echo", 12))),
                    Theme("Family", 5, entities = listOf(NamedCount("Prabir", 4)))
                )
            )
        )!!
        assertTrue(text, text.contains("work", ignoreCase = true))
        assertTrue(text, text.contains("family", ignoreCase = true))
    }

    @Test
    fun it_can_say_what_changed_against_the_previous_period() = runBlocking {
        val text = LocalReflectionNarrator().narrate(
            brief(
                themes = listOf(Theme("Work", 12), Theme("Family", 5)),
                changes = listOf("Work has grown", "Family has quietened")
            )
        )!!
        // A comparison, which is the thing that makes a reflection feel like
        // more than a list.
        assertTrue(text, text.contains("Compared with before", ignoreCase = true))
    }

    @Test
    fun commitment_questions_lead_with_what_is_overdue() = runBlocking {
        val yesterday = System.currentTimeMillis() - 86_400_000
        val text = LocalReflectionNarrator().narrate(
            brief(
                intent = ReflectionIntent.COMMITMENT_ANALYSIS,
                commitments = listOf(
                    CommitmentBrief("m1", "Buy swimming goggles for Prabir", yesterday, isOverdue = true),
                    CommitmentBrief("m2", "Send the testing update", null)
                )
            )
        )!!
        assertTrue(text, text.contains("past its date", ignoreCase = true))
        assertTrue(text, text.contains("swimming goggles", ignoreCase = true))
    }

    @Test
    fun an_empty_period_produces_nothing_rather_than_filler() = runBlocking {
        assertNull(LocalReflectionNarrator().narrate(brief(count = 0)))
    }

    @Test
    fun a_mood_question_with_no_recorded_feeling_says_so_honestly() = runBlocking {
        val text = LocalReflectionNarrator().narrate(brief(intent = ReflectionIntent.MOOD_ANALYSIS))!!
        assertTrue(text, text.contains("didn't record", ignoreCase = true))
    }

    @Test
    fun intent_is_classified_before_anything_is_retrieved() {
        val c = KeywordIntentClassifier()
        assertEquals(ReflectionIntent.COMMITMENT_ANALYSIS, c.classify("What am I forgetting?"))
        assertEquals(ReflectionIntent.MOOD_ANALYSIS, c.classify("How have I been feeling lately?"))
        assertEquals(ReflectionIntent.PROJECT_ANALYSIS, c.classify("What projects am I working on?"))
        assertEquals(ReflectionIntent.THEME_DISCOVERY, c.classify("What have I been thinking about?"))
        assertEquals(ReflectionIntent.TIMELINE_SUMMARY, c.classify("What did I do this week?"))
        // Nothing matches: retrieves broadly rather than guessing wrong.
        assertEquals(ReflectionIntent.OPEN_QUESTION, c.classify("Tell me about the blue folder"))
    }

    @Test
    fun the_window_follows_the_words_of_the_question() {
        val today = KeywordIntentClassifier.windowFor("what did I do today", ReflectionIntent.TIMELINE_SUMMARY)
        assertEquals("today", today.label)

        // "What am I forgetting" should reach back past a week — a commitment
        // made a month ago is exactly the thing being asked about.
        val forgetting = KeywordIntentClassifier.windowFor("what am I forgetting", ReflectionIntent.COMMITMENT_ANALYSIS)
        assertTrue(forgetting.since.isBefore(LocalDateTime.now().minusDays(30)))
    }
}
