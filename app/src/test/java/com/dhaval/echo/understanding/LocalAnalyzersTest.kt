package com.dhaval.echo.understanding

import com.dhaval.echo.data.understanding.LocalPersonAnalyzer
import com.dhaval.echo.data.understanding.LocalPlaceAnalyzer
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Stronger local extraction: catch people and places from informal notes, not
 * just the one "call X about Y" shape. Pure analyzers → plain JVM test.
 */
class LocalAnalyzersTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 17, 22, 0)

    private fun content(text: String) = NormalizedContent(
        memoryId = "m", userId = "u", text = text,
        sourceKinds = setOf(SourceKind.TEXT), capturedAt = now
    )

    private fun people(text: String): Set<String> = runBlocking {
        LocalPersonAnalyzer().analyze(content(text))
            .filter { it.kind == EvidenceKind.PERSON }.map { it.value }.toSet()
    }

    private fun places(text: String): Set<String> = runBlocking {
        LocalPlaceAnalyzer().analyze(content(text))
            .filter { it.kind == EvidenceKind.PLACE }.map { it.value }.toSet()
    }

    @Test
    fun people_are_found_by_relationship_and_subject_context() {
        assertTrue("relationship", people("Spent the evening with my brother Sam.").contains("Sam"))
        assertTrue("subject-said", people("Meera said the launch went well.").contains("Meera"))
        assertTrue("subject-and-I", people("Raj and I grabbed coffee.").contains("Raj"))
        assertTrue("interaction still works", people("Need to call Prabir tomorrow.").contains("Prabir"))
        assertTrue("intro'd full name", people("Hello, this is Komal Patel.").contains("Komal Patel"))
        assertTrue("with-me full name", people("Recording with me Komal Trigger.").contains("Komal Trigger"))
    }

    @Test
    fun people_do_not_swallow_common_words() {
        // "Today" / "Tomorrow" etc. must never be claimed as people.
        assertFalse(people("Tomorrow I feel ready.").contains("Tomorrow"))
        assertEquals("no false person", emptySet<String>(), people("Bought rice for the shipment."))
    }

    @Test
    fun places_are_found_by_movement_and_spatial_context() {
        assertTrue("travel verb", places("We went to Goa for the weekend.").contains("Goa"))
        assertTrue("two-word place", places("Landed in New Delhi this morning.").contains("New Delhi"))
        assertTrue("back from", places("Just got back from Mumbai.").contains("Mumbai"))
        assertTrue("place before travel-noun", places("Planning the Goa trip with family.").contains("Goa"))
    }

    @Test
    fun places_ignore_temporal_and_filler_capitals() {
        // "in July" / "at Monday" style — the month/day stoplist blocks them.
        assertFalse(places("Met them in July.").contains("July"))
    }
}
