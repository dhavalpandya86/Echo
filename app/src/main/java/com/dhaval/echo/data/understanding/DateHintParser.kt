package com.dhaval.echo.data.understanding

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Resolves natural-language date hints ("tomorrow", "next monday", "in 3 days")
 * against the moment a memory was captured — a deliberate, small, deterministic
 * vocabulary rather than a general date parser. Unmatched text simply yields
 * no date; a wrong guess on a reminder is worse than none.
 *
 * Resolution happens in two independent passes: which *day* the hint points at,
 * and what *time* on that day. They are separate because the day vocabulary and
 * the clock vocabulary appear in any combination ("next friday at 4pm"), and
 * because the time is the part that ends up in an AlarmManager alarm — landing on
 * the right day at the wrong hour is what makes someone miss the appointment.
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

    /**
     * "4pm", "at 4 pm", "4:30pm", "at 16:00". Either a meridiem or a colon is
     * required: a bare number is far more likely to be a quantity ("3 days",
     * "2026") than a clock time, and a wrong alarm is worse than none.
     */
    private val CLOCK = Regex("""\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b|\b(?:at\s+)?(\d{1,2}):(\d{2})\b""")

    /**
     * Coarse parts of the day, used only when no clock time is stated.
     *
     * Matched on word boundaries, not as substrings: "afternoon" contains "noon"
     * and "tonight" contains "night", so a plain `in` test resolves "tomorrow
     * afternoon" to midday.
     */
    private val PERIODS = listOf(
        "midnight" to LocalTime.of(0, 0),
        "noon" to LocalTime.of(12, 0),
        "morning" to LocalTime.of(9, 0),
        "afternoon" to LocalTime.of(14, 0),
        "evening" to LocalTime.of(18, 0),
        "tonight" to LocalTime.of(20, 0),
        "night" to LocalTime.of(20, 0)
    ).map { (word, time) -> Regex("""\b$word\b""") to time }

    /** A resolved day, plus whether its time is already exact and must not be overridden. */
    private data class DayHint(val phrase: String, val at: LocalDateTime, val exact: Boolean = false)

    fun firstHint(text: String, capturedAt: LocalDateTime): DateHint? {
        val lower = text.lowercase()
        val day = dayHint(lower, capturedAt) ?: return null

        // "in 3 hours" is already an instant; overlaying a wall-clock time would
        // throw away the offset the user actually asked for.
        val resolved = if (day.exact) day.at else timeOfDay(lower)?.let(day.at::with) ?: day.at
        return DateHint(day.phrase, resolved.toMillis())
    }

    private fun dayHint(lower: String, capturedAt: LocalDateTime): DayHint? {
        simpleHint(lower, capturedAt)?.let { return it }

        IN_N.find(lower)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return@let
            return when {
                m.groupValues[2].startsWith("hour") ->
                    DayHint(m.value, capturedAt.plusHours(n.toLong()), exact = true)
                m.groupValues[2].startsWith("week") ->
                    DayHint(m.value, capturedAt.plusWeeks(n.toLong()).atDefaultHour())
                else ->
                    DayHint(m.value, capturedAt.plusDays(n.toLong()).atDefaultHour())
            }
        }

        NEXT_ON_WEEKDAY.find(lower)?.let { m ->
            val day = WEEKDAYS[m.groupValues[2]] ?: return@let
            return DayHint(m.value, capturedAt.with(TemporalAdjusters.next(day)).atDefaultHour())
        }

        return null
    }

    private fun simpleHint(lower: String, capturedAt: LocalDateTime): DayHint? {
        val candidates = listOf(
            "day after tomorrow" to capturedAt.plusDays(2).atDefaultHour(),
            "tomorrow" to capturedAt.plusDays(1).atDefaultHour(),
            "tonight" to capturedAt.atDefaultHour(),
            "next week" to capturedAt.plusWeeks(1).atDefaultHour(),
            "next month" to capturedAt.plusMonths(1).atDefaultHour(),
            // "today" with no stated time means the rest of today, not this morning.
            "today" to capturedAt.withHour(DEFAULT_HOUR.coerceAtLeast(capturedAt.hour + 1))
                .withMinute(0).withSecond(0).withNano(0)
        )
        for ((phrase, resolved) in candidates) {
            if (phrase in lower) return DayHint(phrase, resolved)
        }
        return null
    }

    /**
     * The time of day stated anywhere in the text, if any. An explicit clock
     * reading beats a coarse period, so "tomorrow evening at 7" resolves to 19:00
     * rather than the generic 18:00.
     */
    private fun timeOfDay(lower: String): LocalTime? {
        CLOCK.find(lower)?.let { m ->
            // Group 1-3 are the meridiem form; 4-5 the 24-hour form.
            val meridiem = m.groupValues[3]
            return if (meridiem.isNotEmpty()) {
                val h = m.groupValues[1].toIntOrNull() ?: return@let
                val min = m.groupValues[2].toIntOrNull() ?: 0
                if (h !in 1..12 || min !in 0..59) return@let
                val hour = when {
                    meridiem == "pm" && h < 12 -> h + 12
                    meridiem == "am" && h == 12 -> 0
                    else -> h
                }
                LocalTime.of(hour, min)
            } else {
                val h = m.groupValues[4].toIntOrNull() ?: return@let
                val min = m.groupValues[5].toIntOrNull() ?: return@let
                if (h !in 0..23 || min !in 0..59) return@let
                LocalTime.of(h, min)
            }
        }

        return PERIODS.firstOrNull { (word, _) -> word.containsMatchIn(lower) }?.second
    }

    private fun LocalDateTime.atDefaultHour(): LocalDateTime =
        withHour(DEFAULT_HOUR).withMinute(0).withSecond(0).withNano(0)

    private fun LocalDateTime.toMillis(): Long =
        atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private const val DEFAULT_HOUR = 9
}
