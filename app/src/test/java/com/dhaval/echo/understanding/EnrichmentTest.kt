package com.dhaval.echo.understanding

import com.dhaval.echo.data.db.EntityType
import com.dhaval.echo.data.understanding.CategoryHeuristic
import com.dhaval.echo.domain.understanding.EntityContext
import com.dhaval.echo.domain.understanding.Enrichment
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.ExtractionContext
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * The ENRICH stage, and why it is the difference between an extraction pipeline
 * and a memory system.
 *
 * "Need to buy swimming goggles for Prabir." says nothing about who Prabir is.
 * Read in isolation it is a shopping note. Read against a graph that has seen
 * Prabir twenty times alongside School and Swimming, it is unmistakably family.
 *
 * Same sentence, same rules, different answer — because the second one knows
 * whose life it landed in.
 */
class EnrichmentTest {

    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 8, 2, 14, 57)
    private val memory = "Need to buy swimming goggles for Prabir."

    private val content = NormalizedContent(
        memoryId = "m", userId = "u", text = memory,
        sourceKinds = setOf(SourceKind.VOICE), capturedAt = capturedAt
    )

    /** Grounding found a person; that is all it can say. */
    private val grounded = listOf(
        Evidence(EvidenceKind.PERSON, "Prabir", memory, 0.6f)
    )

    private fun prabirKnownAs(vararg neighbours: String, seenIn: Int) = Enrichment(
        listOf(
            EntityContext(
                entityId = "e1",
                name = "Prabir",
                type = EntityType.PERSON,
                memoryCount = seenIn,
                firstSeenAt = capturedAt.minusMonths(7),
                lastSeenAt = capturedAt.minusDays(3),
                relatedNames = neighbours.toList()
            )
        )
    )

    @Test
    fun without_context_the_memory_is_only_personal() = runBlocking {
        val ctx = ExtractionContext(content, grounded, Enrichment())
        // No kinship word, no known activity, no organisation — there is nothing
        // here that honestly places it, and it should not pretend otherwise.
        assertEquals("Personal", CategoryHeuristic().answer(ctx).first().value)
    }

    @Test
    fun the_graph_places_a_memory_its_own_words_cannot() = runBlocking {
        val ctx = ExtractionContext(
            content,
            grounded,
            prabirKnownAs("Swimming", "School", seenIn = 20)
        )
        assertEquals("Family", CategoryHeuristic().answer(ctx).first().value)
    }

    @Test
    fun a_barely_known_person_is_not_treated_as_a_pattern() = runBlocking {
        // Seen twice. That is a coincidence, not a life. Concluding "Family"
        // from it would be exactly the confident-but-wrong inference the whole
        // confidence-and-evidence discipline exists to prevent.
        val ctx = ExtractionContext(
            content,
            grounded,
            prabirKnownAs("Swimming", seenIn = 2)
        )
        assertEquals("Personal", CategoryHeuristic().answer(ctx).first().value)
    }

    @Test
    fun enrichment_carries_the_evidence_for_what_it_concluded() = runBlocking {
        val ctx = ExtractionContext(
            content,
            grounded,
            prabirKnownAs("Swimming", "School", seenIn = 20)
        )
        val verdict = CategoryHeuristic().answer(ctx).first()
        // A graph-derived conclusion must say what it rested on, or the user
        // cannot correct it and the next stage cannot weigh it.
        assertEquals(
            "Prabir usually appears with Swimming, School",
            verdict.evidenceText
        )
    }
}
