package com.dhaval.echo.understanding

import com.dhaval.echo.data.understanding.CategoryHeuristic
import com.dhaval.echo.data.understanding.LocalActivityAnalyzer
import com.dhaval.echo.data.understanding.LocalObjectAnalyzer
import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.MemoryTypeHeuristic
import com.dhaval.echo.data.understanding.PriorityHeuristic
import com.dhaval.echo.data.understanding.SummaryHeuristic
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The memory that started this redesign.
 *
 * "I think next week I need to take Prabir for swimming glasses." used to
 * produce: no people at all, and — because nothing was extracted — the keyword
 * tagger's "Need, Next, Take, Think, Week". The task and its date were the only
 * things that worked.
 *
 * This pins what the *rules alone* must now get, with no model installed and no
 * network. It is the floor, so it is also the promise: this is the worst Echo
 * is allowed to do on this sentence.
 */
class PrabirMemoryTest {

    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 8, 2, 14, 57)
    private val memory = "I think next week I need to take Prabir for swimming glasses."

    private fun content(text: String = memory) = NormalizedContent(
        memoryId = "m", userId = "u", text = text,
        sourceKinds = setOf(SourceKind.VOICE), capturedAt = capturedAt
    )

    /** Run the grounding analyzers the way the registry wires them. */
    private fun ground(text: String = memory): List<Evidence> = runBlocking {
        val c = content(text)
        LocalPersonAnalyzer().analyze(c) +
            LocalActivityAnalyzer().analyze(c) +
            LocalObjectAnalyzer().analyze(c) +
            LocalTaskAnalyzer().analyze(c)
    }

    private fun ctx(text: String = memory) = ExtractionContext(content(text), ground(text))

    @Test
    fun person_is_found_through_a_caretaking_verb() {
        // The exact regression: "take" was not a person trigger, so Prabir —
        // the whole point of the memory — was invisible.
        val people = ground().filter { it.kind == EvidenceKind.PERSON }.map { it.value }
        assertTrue("expected Prabir, got $people", people.contains("Prabir"))
    }

    @Test
    fun activity_and_object_are_extracted() {
        val evidence = ground()
        val activities = evidence.filter { it.kind == EvidenceKind.ACTIVITY }.map { it.value }
        val objects = evidence.filter { it.kind == EvidenceKind.OBJECT }.map { it.value }

        assertTrue("expected Swimming, got $activities", activities.contains("Swimming"))
        // The modifier has to survive: "Swimming glasses" is retrievable later,
        // bare "Glasses" is not.
        assertTrue(
            "expected a swimming-glasses object, got $objects",
            objects.any { it.equals("Swimming glasses", ignoreCase = true) }
        )
    }

    @Test
    fun the_commitment_is_still_found_with_its_date() {
        val tasks = ground().filter { it.kind == EvidenceKind.TASK }
        assertEquals(1, tasks.size)
        assertNotNull("the task must keep its resolved due date", tasks.first().dueAtMillis)
    }

    @Test
    fun facets_are_interpreted_from_the_grounded_facts() = runBlocking {
        val c = ctx()

        assertEquals(
            "a memory with a task is a Commitment",
            "Commitment",
            MemoryTypeHeuristic().answer(c).first().value
        )
        assertEquals(
            "a commitment about a child's activity is Family",
            "Family",
            CategoryHeuristic().answer(ctx("I need to take my son Prabir for swimming glasses next week.")).first().value
        )
        // Someone else is depending on it, and it falls inside a week.
        assertEquals("High", PriorityHeuristic().answer(c).first().value)
    }

    @Test
    fun the_summary_leads_with_the_commitment_rather_than_echoing_the_sentence() = runBlocking {
        val summary = SummaryHeuristic().answer(ctx())
        assertNotNull("a memory with a task must get a summary", summary)
        assertTrue(
            "the summary must not just hand the sentence back, got: $summary",
            !summary!!.trim().equals(memory.trim(), ignoreCase = true)
        )
        assertTrue("the summary should name the commitment, got: $summary", summary.contains("Prabir"))
    }

    @Test
    fun a_memory_with_nothing_in_it_produces_nothing() = runBlocking {
        // The other half of deleting the keyword floor: silence, not filler.
        val empty = ExtractionContext(content("Anyway."), emptyList())
        assertEquals(null, SummaryHeuristic().answer(empty))
        assertTrue(ground("Anyway.").isEmpty())
    }
}
