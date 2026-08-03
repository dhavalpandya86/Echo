package com.dhaval.echo.reflection

import com.dhaval.echo.data.reflection.GroundedReflectionValidator
import com.dhaval.echo.domain.reflection.NamedCount
import com.dhaval.echo.domain.reflection.ReflectionBrief
import com.dhaval.echo.domain.reflection.ReflectionFlaw
import com.dhaval.echo.domain.reflection.ReflectionIntent
import com.dhaval.echo.domain.reflection.ReflectionWindow
import com.dhaval.echo.domain.reflection.Theme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The step after the model.
 *
 * Echo decides what is true; the model decides only how to say it. Prompt
 * instructions express that intention but do not enforce it — a model asked to
 * write about someone's life will occasionally add a name that was never there,
 * and in a diary the user cannot tell an invention from something they forgot.
 *
 * These pin the enforcement. The validator has to be quiet enough that ordinary
 * prose passes, and sharp enough that an invented person does not.
 */
class ReflectionValidatorTest {

    private val validator = GroundedReflectionValidator()

    private val brief = ReflectionBrief(
        question = "what did I focus on this week?",
        intent = ReflectionIntent.TIMELINE_SUMMARY,
        window = ReflectionWindow(LocalDateTime.now().minusDays(7), "this week"),
        memoryCount = 10,
        themes = listOf(
            Theme("Family", 5, 0.9f, "mostly Prabir", listOf(NamedCount("Prabir", 4))),
            Theme("Work", 12, 0.7f, "mostly Echo", listOf(NamedCount("Echo", 12)))
        ),
        people = listOf(NamedCount("Prabir", 4)),
        projects = listOf(NamedCount("Echo", 12))
    )

    @Test
    fun ordinary_grounded_prose_passes() {
        val text = "Your week split between family and work. Prabir came up often, " +
            "usually around swimming and school, while Echo took most of your working attention."
        assertTrue(validator.validate(text, brief).detail ?: "", validator.validate(text, brief).isAcceptable)
    }

    @Test
    fun an_invented_person_is_caught() {
        // "Rajesh" is nowhere in the brief. This is the failure that matters:
        // the user cannot distinguish it from a memory they've forgotten.
        val text = "Your week split between family and work. You spent time with Rajesh " +
            "discussing the plans, and Prabir came up often around swimming."
        val verdict = validator.validate(text, brief)
        assertFalse(verdict.isAcceptable)
        assertTrue(verdict.flaws.contains(ReflectionFlaw.INVENTED_SUBJECT))
        assertTrue(verdict.detail!!, verdict.detail!!.contains("Rajesh"))
    }

    @Test
    fun leaked_scaffolding_is_caught() {
        // The exact regression this whole engine replaced.
        val text = "Here is the relevant context from the user's memories: Memory: I think " +
            "next week I need to take Prabir for swimming glasses. Transcript: ..."
        val verdict = validator.validate(text, brief)
        assertFalse(verdict.isAcceptable)
        assertTrue(verdict.flaws.contains(ReflectionFlaw.EXPOSED_INTERNALS))
    }

    @Test
    fun weekdays_and_months_are_not_mistaken_for_people() {
        val text = "Your week split between family and work. On Tuesday you focused on Echo, " +
            "and by Friday most of your attention had moved to Prabir and swimming plans."
        assertTrue(validator.validate(text, brief).isAcceptable)
    }

    @Test
    fun a_reflection_that_says_nothing_is_rejected() {
        assertFalse(validator.validate("Not much.", brief).isAcceptable)
        assertEquals(
            listOf(ReflectionFlaw.EMPTY_OR_REPETITIVE),
            validator.validate("", brief).flaws
        )
    }

    @Test
    fun repetition_is_rejected() {
        val line = "Your week was mostly about family and work commitments. "
        val verdict = validator.validate(line + line + line, brief)
        assertFalse(verdict.isAcceptable)
        assertTrue(verdict.flaws.contains(ReflectionFlaw.EMPTY_OR_REPETITIVE))
    }

    @Test
    fun the_brief_is_the_only_permitted_vocabulary() {
        // The contract the validator enforces: everything nameable is in here,
        // and nothing else is allowed through.
        val vocab = brief.vocabulary()
        assertTrue(vocab.containsAll(listOf("Family", "Work", "Prabir", "Echo")))
        assertFalse(vocab.contains("Rajesh"))
    }
}
