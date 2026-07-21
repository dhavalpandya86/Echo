package com.dhaval.echo.understanding

import com.dhaval.echo.data.understanding.DateHintParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A reminder that lands on the right day at the wrong hour still makes someone
 * miss the appointment — dueAtMillis becomes a real AlarmManager alarm — so the
 * time half of a hint is worth as much coverage as the day half.
 */
class DateHintParserTest {

    /** A Tuesday, mid-morning. */
    private val capturedAt: LocalDateTime = LocalDateTime.of(2026, 7, 21, 10, 46)

    /** The resolved instant as a local date-time, for readable assertions. */
    private fun resolve(text: String): LocalDateTime? =
        DateHintParser.firstHint(text, capturedAt)?.let {
            Instant.ofEpochMilli(it.atMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }

    private fun assertResolves(text: String, expected: LocalDateTime) =
        assertEquals(text, expected, resolve(text))

    @Test
    fun pm_times_move_into_the_afternoon() {
        val tomorrow4pm = LocalDateTime.of(2026, 7, 22, 16, 0)
        assertResolves("Dentist tomorrow at 4pm.", tomorrow4pm)
        assertResolves("Dentist tomorrow at 4 pm.", tomorrow4pm)
        assertResolves("Dentist tomorrow 4pm.", tomorrow4pm)
    }

    @Test
    fun am_times_stay_in_the_morning() {
        assertResolves("Call the bank tomorrow at 8am.", LocalDateTime.of(2026, 7, 22, 8, 0))
    }

    @Test
    fun midday_and_midnight_are_not_off_by_twelve() {
        // The classic 12-hour clock trap: 12pm is noon, 12am is midnight.
        assertResolves("Lunch tomorrow at 12pm.", LocalDateTime.of(2026, 7, 22, 12, 0))
        assertResolves("Deploy tomorrow at 12am.", LocalDateTime.of(2026, 7, 22, 0, 0))
    }

    @Test
    fun half_hours_are_kept() {
        assertResolves("Standup tomorrow at 9:30am.", LocalDateTime.of(2026, 7, 22, 9, 30))
        assertResolves("Dentist tomorrow at 4:30pm.", LocalDateTime.of(2026, 7, 22, 16, 30))
    }

    @Test
    fun twenty_four_hour_times_are_understood() {
        assertResolves("Flight tomorrow at 16:00.", LocalDateTime.of(2026, 7, 22, 16, 0))
        assertResolves("Flight tomorrow at 06:05.", LocalDateTime.of(2026, 7, 22, 6, 5))
    }

    @Test
    fun a_time_applies_to_whichever_day_was_named() {
        // 21 Jul 2026 is a Tuesday, so "next friday" is the 24th.
        assertResolves("Review next friday at 3pm.", LocalDateTime.of(2026, 7, 24, 15, 0))
        assertResolves("Ship it in 3 days at 5pm.", LocalDateTime.of(2026, 7, 24, 17, 0))
    }

    @Test
    fun coarse_periods_are_used_when_no_clock_time_is_given() {
        assertResolves("Gym tomorrow morning.", LocalDateTime.of(2026, 7, 22, 9, 0))
        assertResolves("Call tomorrow afternoon.", LocalDateTime.of(2026, 7, 22, 14, 0))
        assertResolves("Dinner tomorrow evening.", LocalDateTime.of(2026, 7, 22, 18, 0))
    }

    @Test
    fun a_period_word_hiding_inside_another_is_not_matched() {
        // "afternoon" contains "noon" and "tonight" contains "night"; matching
        // periods as substrings resolved "tomorrow afternoon" to midday.
        assertResolves("Call tomorrow afternoon.", LocalDateTime.of(2026, 7, 22, 14, 0))
        assertResolves("Drinks tonight.", LocalDateTime.of(2026, 7, 21, 20, 0))
    }

    @Test
    fun an_explicit_clock_time_beats_a_coarse_period() {
        assertResolves("Dinner tomorrow evening at 7pm.", LocalDateTime.of(2026, 7, 22, 19, 0))
    }

    @Test
    fun nine_am_remains_the_fallback_when_no_time_is_stated() {
        assertResolves("Send him the budget file tomorrow.", LocalDateTime.of(2026, 7, 22, 9, 0))
        assertResolves("Renew it next week.", LocalDateTime.of(2026, 7, 28, 9, 0))
    }

    @Test
    fun a_relative_hour_offset_is_not_overridden_by_a_clock_time() {
        // "in 2 hours" is already an instant; it must not be rewritten to 09:00
        // or to some unrelated time mentioned elsewhere in the sentence.
        assertResolves("Check the oven in 2 hours.", LocalDateTime.of(2026, 7, 21, 12, 46))
    }

    @Test
    fun bare_numbers_are_never_mistaken_for_times() {
        // Neither a meridiem nor a colon → not a clock reading, so the day's
        // default hour stands rather than "3 o'clock" being invented from "3 days".
        assertResolves("Ship it in 3 days.", LocalDateTime.of(2026, 7, 24, 9, 0))
        assertResolves("Read 40 pages tomorrow.", LocalDateTime.of(2026, 7, 22, 9, 0))
    }

    @Test
    fun text_with_no_date_still_yields_nothing() {
        assertNull(resolve("Had a good coffee with Meera."))
        assertNull(resolve("Meeting at 4pm."))  // a time alone names no day
    }
}
