package com.dhaval.echo.understanding

import com.dhaval.echo.domain.understanding.ExtractorRegistry
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.ProcessingRouter
import com.dhaval.echo.domain.understanding.SourceKind
import com.dhaval.echo.domain.understanding.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Router 1 decides which questions are worth asking, never what a memory is.
 *
 * Its bias has to be one-directional: skipping a question that would have found
 * something is a permanent, silent loss, while running a pointless one costs
 * only time. So these tests care far more about what it *runs* than about what
 * it skips.
 */
class ProcessingRouterTest {

    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 8, 2, 14, 57)

    private fun memory(text: String, images: List<String> = emptyList()) = NormalizedContent(
        memoryId = "m", userId = "u", text = text,
        sourceKinds = setOf(SourceKind.VOICE), capturedAt = capturedAt,
        imagePaths = images
    )

    private fun runsFor(text: String): Set<String> =
        ProcessingRouter.route(ExtractorRegistry.all, memory(text)).toRun.map { it.id }.toSet()

    @Test
    fun a_terse_shopping_note_skips_the_questions_it_cannot_answer() {
        val run = runsFor("Buy milk tomorrow.")

        // No capitalised word other than the sentence start — there is no
        // candidate name here, so these are provably empty.
        assertFalse("people", "people" in run)
        assertFalse("places", "places" in run)
        assertFalse("organizations", "organizations" in run)

        // But the things it genuinely contains must still be asked.
        assertTrue("tasks", "tasks" in run)
        assertTrue("temporal", "temporal" in run)
    }

    @Test
    fun a_memory_naming_someone_still_gets_the_proper_noun_questions() {
        val run = runsFor("I need to take Prabir for swimming glasses next week.")
        assertTrue("people", "people" in run)
        assertTrue("tasks", "tasks" in run)
        assertTrue("temporal", "temporal" in run)
        assertTrue("objects", "objects" in run)
    }

    @Test
    fun narration_is_never_routed_away() {
        // A memory should end up with a summary and a title whatever else was
        // found — otherwise routing could leave it unnamed in the list.
        val run = runsFor("Hmm.")
        assertTrue("summary", "summary" in run)
        assertTrue("title", "title" in run)
    }

    @Test
    fun a_photo_memory_with_no_text_still_asks_what_the_image_can_answer() {
        val routing = ProcessingRouter.route(ExtractorRegistry.all, memory("", listOf("/a.jpg")))
        val run = routing.toRun.map { it.id }.toSet()
        // Text length would skip all of these; the image is the reason not to.
        assertTrue("people", "people" in run)
        assertTrue("objects", "objects" in run)
        assertTrue("places", "places" in run)
    }

    @Test
    fun feelings_and_decisions_are_only_asked_when_something_hints_at_them() {
        assertFalse("emotion", "emotion" in runsFor("Buy milk tomorrow."))
        assertTrue("emotion", "emotion" in runsFor("Felt really proud of him today."))

        assertFalse("decisions", "decisions" in runsFor("Buy milk tomorrow."))
        assertTrue("decisions", "decisions" in runsFor("We decided to go with the smaller flat."))
    }

    @Test
    fun routing_partitions_the_registry_without_losing_a_question() {
        // Every question must end up either asked or explicitly skipped —
        // anything dropped silently would make the progress count never reach
        // its total, and the memory would look permanently half-understood.
        val routing = ProcessingRouter.route(ExtractorRegistry.all, memory("Buy milk tomorrow."))
        assertEquals(
            ExtractorRegistry.size,
            routing.toRun.size + routing.toSkip.size
        )
    }

    @Test
    fun prepare_and_enrich_are_never_gated() {
        val prepare = ExtractorRegistry.stage(Stage.PREPARE)
        assertTrue(prepare.isNotEmpty())
        prepare.forEach {
            assertTrue(it.id, ProcessingRouter.shouldRun(it, memory("")))
        }
    }
}
