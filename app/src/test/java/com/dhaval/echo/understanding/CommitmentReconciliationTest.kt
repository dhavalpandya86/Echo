package com.dhaval.echo.understanding

import com.dhaval.echo.data.understanding.LocalReminderAnalyzer
import com.dhaval.echo.data.understanding.LocalTaskAnalyzer
import com.dhaval.echo.data.understanding.reconcileItemEvidence
import com.dhaval.echo.domain.understanding.Evidence
import com.dhaval.echo.domain.understanding.EvidenceKind
import com.dhaval.echo.domain.understanding.NormalizedContent
import com.dhaval.echo.domain.understanding.SourceKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * One commitment must produce one item.
 *
 * The task and reminder analyzers both fire on a sentence like "I must send him
 * the budget file tomorrow" — one claims the obligation, the other the time
 * anchor — which used to surface a real action item beside a junk one titled
 * "tomorrow". Pure analyzers plus a pure reconciliation → plain JVM test.
 */
class CommitmentReconciliationTest {

    private val now: LocalDateTime = LocalDateTime.of(2026, 7, 21, 10, 46)

    private fun content(text: String) = NormalizedContent(
        memoryId = "m", userId = "u", text = text,
        sourceKinds = setOf(SourceKind.TEXT), capturedAt = now
    )

    /** Everything the two commitment analyzers claim about [text], reconciled. */
    private fun items(text: String): List<Evidence> = runBlocking {
        val board = LocalTaskAnalyzer().analyze(content(text)) +
            LocalReminderAnalyzer().analyze(content(text))
        reconcileItemEvidence(board)
    }

    @Test
    fun a_dated_obligation_yields_one_item_not_two() {
        val result = items(
            "Met Prabir at the Titan project review today. " +
                "I must send him the budget file tomorrow."
        )

        assertEquals("one commitment → one item, got $result", 1, result.size)

        val only = result.single()
        assertEquals(EvidenceKind.TASK, only.kind)
        assertTrue("the surviving item is the obligation", only.value.contains("budget file"))
        assertNotNull("and it keeps the due date", only.dueAtMillis)
    }

    @Test
    fun the_bare_time_phrase_never_becomes_an_item() {
        val values = items("I must send him the budget file tomorrow.").map { it.value.lowercase() }
        assertTrue("no item may be titled with just the date, got $values", values.none { it == "tomorrow" })
    }

    @Test
    fun a_reminder_with_no_obligation_survives_and_is_readable() {
        // No "must/need to" here, so no task covers it — this one must be kept,
        // and titled with something a person can act on.
        val result = items("Dentist tomorrow at 4pm.")

        assertEquals("standalone reminder is kept, got $result", 1, result.size)
        val only = result.single()
        assertEquals(EvidenceKind.REMINDER, only.kind)
        assertTrue("titled with the sentence, not the phrase", only.value.contains("Dentist"))
        assertNotNull(only.dueAtMillis)
    }

    @Test
    fun an_undated_task_is_untouched() {
        val result = items("I need to renew the insurance.")

        assertEquals(1, result.size)
        assertEquals(EvidenceKind.TASK, result.single().kind)
    }

    @Test
    fun a_reminder_on_a_different_day_is_not_swallowed() {
        // Distinct instants are distinct commitments; only an exact match is an echo.
        val task = Evidence(
            kind = EvidenceKind.TASK, value = "Send the file",
            evidenceText = null, confidence = 0.65f, dueAtMillis = 1_000L
        )
        val reminder = Evidence(
            kind = EvidenceKind.REMINDER, value = "Standup on Friday",
            evidenceText = null, confidence = 0.7f, dueAtMillis = 2_000L
        )

        assertEquals(2, reconcileItemEvidence(listOf(task, reminder)).size)
    }
}
