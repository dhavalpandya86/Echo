package com.dhaval.echo.data.understanding

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Resolves natural-language date hints ("tomorrow", "next monday", "in 3 days")
 * against the moment a memory was captured — a deliberate, small, deterministic
 * vocabulary rather than a general date parser. Unmatched text simply yields
 * no date; a wrong guess on a reminder is worse than none.
 */
object DateHintParser {

    /** The phrase found and the resolved instant, or null when no hint exists. */
    data class DateHint(val phrase: String, val atMillis: Long)

    private val WEEKDAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY
    )

    private val IN_N = Regex("""\bin\s+(\d{1,2})\s+(day|days|week|weeks|hour|hours)\b""", RegexOption.IGNORE_CASE)
    private val NEXT_ON_WEEKDAY = Regex("""\b(next|on|by)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""", RegexOption.IGNORE_CASE)

    fun firstHint(text: String, capturedAt: LocalDateTime): DateHint? {
        val lower = text.lowercase()

        // Fixed vocabulary first — most common in spoken notes.
        simpleHint(lower, capturedAt)?.let { return it }

        IN_N.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            val base = when {
                m.groupValues[2].startsWith("hour") -> capturedAt.plusHours(n.toLong())
                m.groupValues[2].startsWith("week") -> capturedAt.plusWeeks(n.toLong()).withHour(DEFAULT_HOUR).withMinute(0)
                else -> capturedAt.plusDays(n.toLong()).withHour(DEFAULT_HOUR).withMinute(0)
            }
            return DateHint(m.value, base.toMillis())
        }

        NEXT_ON_WEEKDAY.find(lower)?.let { m ->
            val day = WEEKDAYS[m.groupValues[2]] ?: return@let
            val next = capturedAt.with(TemporalAdjusters.next(day)).withHour(DEFAULT_HOUR).withMinute(0)
            return DateHint(m.value, next.toMillis())
        }

        return null
    }

    private fun simpleHint(lower: String, capturedAt: LocalDateTime): DateHint? {
        val candidates = listOf(
            "day after tomorrow" to capturedAt.plusDays(2),
            "tomorrow morning" to capturedAt.plusDays(1).withHour(9).withMinute(0),
            "tomorrow evening" to capturedAt.plusDays(1).withHour(18).withMinute(0),
            "tomorrow" to capturedAt.plusDays(1).withHour(DEFAULT_HOUR).withMinute(0),
            "tonight" to capturedAt.withHour(20).withMinute(0),
            "this evening" to capturedAt.withHour(18).withMinute(0),
            "next week" to capturedAt.plusWeeks(1).withHour(DEFAULT_HOUR).withMinute(0),
            "next month" to capturedAt.plusMonths(1).withHour(DEFAULT_HOUR).withMinute(0),
            "today" to capturedAt.withHour(DEFAULT_HOUR.coerceAtLeast(capturedAt.hour + 1)).withMinute(0)
        )
        for ((phrase, resolved) in candidates) {
            if (phrase in lower) return DateHint(phrase, resolved.toMillis())
        }
        return null
    }

    private fun LocalDateTime.toMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private const val DEFAULT_HOUR = 9
}
