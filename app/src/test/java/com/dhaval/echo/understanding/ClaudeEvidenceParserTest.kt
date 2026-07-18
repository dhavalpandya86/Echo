package com.dhaval.echo.understanding

import com.dhaval.echo.data.understanding.ClaudeEvidenceParser
import com.dhaval.echo.domain.understanding.EvidenceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * MU-1: the Claude structured-output contract, verified offline. The parser is
 * the risky seam (a JSON shape from the model → typed Evidence); the network
 * call is not exercised here.
 */
class ClaudeEvidenceParserTest {

    private val capturedAt = LocalDateTime.of(2026, 7, 17, 22, 0)
    private val zone = ZoneId.systemDefault()

    private fun dayOfMonth(epochMillis: Long) =
        Instant.ofEpochMilli(epochMillis).atZone(zone).dayOfMonth

    private fun hour(epochMillis: Long) =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    @Test
    fun vision_example_fans_out_into_typed_evidence() {
        val raw = """
            {"evidence":[
              {"kind":"PERSON","value":"Raj","quote":"call Raj","confidence":0.95},
              {"kind":"PROJECT","value":"Oceanis","quote":"the Oceanis logo","confidence":0.9},
              {"kind":"TASK","value":"Call Raj about the Oceanis logo","quote":"I need to call Raj","confidence":0.9,"due":"2026-07-18"}
            ]}
        """.trimIndent()

        val evidence = ClaudeEvidenceParser.parse(raw, capturedAt)
        assertEquals(3, evidence.size)

        val raj = evidence.first { it.kind == EvidenceKind.PERSON }
        assertEquals("Raj", raj.value)
        assertEquals("call Raj", raj.evidenceText)
        assertEquals(0.95f, raj.confidence, 0.0001f)

        val project = evidence.first { it.kind == EvidenceKind.PROJECT }
        assertEquals("Oceanis", project.value)

        val task = evidence.first { it.kind == EvidenceKind.TASK }
        assertNotNull("task should carry a due date", task.dueAtMillis)
        assertEquals("due should resolve to tomorrow (the 18th)", 18, dayOfMonth(task.dueAtMillis!!))
        assertEquals("date-only due defaults to 09:00", 9, hour(task.dueAtMillis!!))
    }

    @Test
    fun strips_markdown_fences_and_surrounding_prose() {
        val raw = """
            Here is the extraction:
            ```json
            {"evidence":[{"kind":"MOOD","value":"Excited","quote":"so excited","confidence":0.8}]}
            ```
        """.trimIndent()

        val evidence = ClaudeEvidenceParser.parse(raw, capturedAt)
        assertEquals(1, evidence.size)
        assertEquals("Excited", evidence.single().value)
        assertEquals(EvidenceKind.MOOD, evidence.single().kind)
    }

    @Test
    fun datetime_due_keeps_its_time() {
        val raw = """{"evidence":[{"kind":"REMINDER","value":"Standup","confidence":0.7,"due":"2026-07-18T14:30"}]}"""
        val evidence = ClaudeEvidenceParser.parse(raw, capturedAt)
        val due = evidence.single().dueAtMillis!!
        assertEquals(18, dayOfMonth(due))
        assertEquals(14, hour(due))
    }

    @Test
    fun bad_elements_are_skipped_but_good_ones_survive() {
        val raw = """
            {"evidence":[
              {"kind":"NONSENSE","value":"x","confidence":0.9},
              {"kind":"PERSON","confidence":0.9},
              {"kind":"PERSON","value":"   ","confidence":0.9},
              {"kind":"PERSON","value":"Priya","quote":"met Priya","confidence":0.9}
            ]}
        """.trimIndent()

        val evidence = ClaudeEvidenceParser.parse(raw, capturedAt)
        assertEquals("only the well-formed Priya survives", 1, evidence.size)
        assertEquals("Priya", evidence.single().value)
    }

    @Test
    fun confidence_is_clamped_and_defaulted() {
        val raw = """
            {"evidence":[
              {"kind":"TOPIC","value":"packaging","confidence":5.0},
              {"kind":"TOPIC","value":"logistics"}
            ]}
        """.trimIndent()

        val evidence = ClaudeEvidenceParser.parse(raw, capturedAt)
        assertEquals(2, evidence.size)
        assertTrue("out-of-range confidence is clamped to <= 1", evidence.all { it.confidence in 0f..1f })
        val defaulted = evidence.first { it.value == "logistics" }
        assertTrue("missing confidence gets a sane default", defaulted.confidence in 0.5f..1f)
    }

    @Test
    fun empty_board_is_honest_not_an_error() {
        val evidence = ClaudeEvidenceParser.parse("""{"evidence":[]}""", capturedAt)
        assertTrue(evidence.isEmpty())
    }

    @Test
    fun a_missing_due_stays_null() {
        val raw = """{"evidence":[{"kind":"TASK","value":"Buy rice","confidence":0.8}]}"""
        assertNull(ClaudeEvidenceParser.parse(raw, capturedAt).single().dueAtMillis)
    }

    @Test
    fun no_json_object_is_treated_as_a_failure() {
        assertThrows(IllegalArgumentException::class.java) {
            ClaudeEvidenceParser.parse("I could not extract anything, sorry.", capturedAt)
        }
    }
}
