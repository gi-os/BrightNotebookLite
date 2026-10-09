package com.gios.brightnotebooklite.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DayPlanTest {

    private val zone = ZoneId.of("America/New_York")
    private fun day(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d).toEpochDay()
    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int) =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun timedEntriesSortAfterAllDayOnes() {
        val d = day(2026, 3, 10)
        val rows = DayPlan.rows(
            d,
            listOf(
                DayPlan.Entry("b", d, text = "dentist", startMinutes = 9 * 60 + 30),
                DayPlan.Entry("a", d, text = "pay rent"),
                DayPlan.Entry("c", d, text = "breakfast", startMinutes = 8 * 60),
            ),
            emptyList(),
            showHolidays = false,
            zone = zone,
        )
        assertEquals(listOf("pay rent", "breakfast", "dentist"), rows.map { it.title })
    }

    @Test
    fun aTimeAfterMidnightBelongsToTheEndOfTheJournalDay() {
        val d = day(2026, 3, 10)
        val rows = DayPlan.rows(
            d,
            listOf(
                DayPlan.Entry("late", d, text = "night bus", startMinutes = 60),
                DayPlan.Entry("early", d, text = "standup", startMinutes = 10 * 60),
            ),
            emptyList(),
            false,
            zone,
        )
        assertEquals(listOf("standup", "night bus"), rows.map { it.title })
    }

    @Test
    fun weeklySeriesLandsOnItsWeekdayOnly() {
        val monday = day(2026, 3, 9)
        val e = DayPlan.Entry("s", monday, text = "gym", rrule = "FREQ=WEEKLY;BYDAY=MO")
        assertEquals(monday + 7, DayPlan.occurrenceCovering(e, monday + 7))
        assertNull(DayPlan.occurrenceCovering(e, monday + 8))
    }

    @Test
    fun anExcludedDayIsSkipped() {
        val monday = day(2026, 3, 9)
        val e = DayPlan.Entry("s", monday, text = "gym", rrule = "FREQ=WEEKLY", exDays = setOf(monday + 7))
        assertNull(DayPlan.occurrenceCovering(e, monday + 7))
        assertEquals(monday + 14, DayPlan.occurrenceCovering(e, monday + 14))
    }

    @Test
    fun multiDayEntryCoversEveryDayAndKeepsItsTimeOnTheFirst() {
        val start = day(2026, 3, 10)
        val trip = DayPlan.Entry("t", start, endEpochDay = start + 2, text = "Lisbon", startMinutes = 7 * 60)
        val first = DayPlan.rows(start, listOf(trip), emptyList(), false, zone).single()
        val middle = DayPlan.rows(start + 1, listOf(trip), emptyList(), false, zone).single()
        assertEquals(7 * 60, first.clockMinutes)
        assertNull(middle.clockMinutes)
        assertEquals(1, middle.dayOfSpan)
        assertEquals(3, middle.spanLength)
        assertTrue(DayPlan.rows(start + 3, listOf(trip), emptyList(), false, zone).isEmpty())
    }

    @Test
    fun busyDaysCoverSeriesAndSpans() {
        val start = day(2026, 3, 1)
        val busy = DayPlan.busyDays(
            listOf(
                DayPlan.Entry("t", start, endEpochDay = start + 1, text = "trip"),
                DayPlan.Entry("s", start, text = "daily", rrule = "FREQ=DAILY;COUNT=3"),
            ),
            start,
            start + 30,
        )
        assertEquals(setOf(start, start + 1, start + 2), busy)
    }

    @Test
    fun aNoteWrittenAndEditedTheSameDayIsOneWroteRow() {
        val d = day(2026, 3, 10)
        val note = DayPlan.Note("n", "groceries", ms(2026, 3, 10, 9, 0), ms(2026, 3, 10, 18, 0))
        val row = DayPlan.rows(d, emptyList(), listOf(note), false, zone).single()
        assertEquals(DayPlan.Kind.WROTE, row.kind)
        assertEquals(9 * 60, row.clockMinutes)
    }

    @Test
    fun aNoteReturnedToLaterAppearsAsCameBack() {
        val d = day(2026, 3, 12)
        val note = DayPlan.Note("n", "groceries", ms(2026, 3, 10, 9, 0), ms(2026, 3, 12, 20, 15))
        val row = DayPlan.rows(d, emptyList(), listOf(note), false, zone).single()
        assertEquals(DayPlan.Kind.CAME_BACK, row.kind)
        assertEquals(20 * 60 + 15, row.clockMinutes)
    }

    @Test
    fun oneAmBelongsToTheNightBefore() {
        val note = DayPlan.Note("n", "late thought", ms(2026, 3, 11, 1, 0), ms(2026, 3, 11, 1, 0))
        assertEquals(1, DayPlan.rows(day(2026, 3, 10), emptyList(), listOf(note), false, zone).size)
        assertTrue(DayPlan.rows(day(2026, 3, 11), emptyList(), listOf(note), false, zone).isEmpty())
    }

    @Test
    fun holidaysAppearWhenAskedFor() {
        val july4 = day(2026, 7, 4)
        assertEquals("Independence Day", DayPlan.rows(july4, emptyList(), emptyList(), true, zone).single().title)
        assertTrue(DayPlan.rows(july4, emptyList(), emptyList(), false, zone).isEmpty())
    }

    @Test
    fun nowLineSplitsTimedRows() {
        val d = day(2026, 3, 10)
        val rows = DayPlan.rows(
            d,
            listOf(
                DayPlan.Entry("a", d, text = "all day"),
                DayPlan.Entry("b", d, text = "morning", startMinutes = 9 * 60),
                DayPlan.Entry("c", d, text = "evening", startMinutes = 19 * 60),
            ),
            emptyList(),
            false,
            zone,
        )
        val now = JournalDay.fromClockMinutes(12 * 60)
        val (before, after) = DayPlan.splitAtNow(rows, now)
        assertEquals(listOf("all day", "morning"), before.map { it.title })
        assertEquals(listOf("evening"), after.map { it.title })
    }

    @Test
    fun tenseComparesDays() {
        assertEquals(DayPlan.Tense.PAST, DayPlan.tense(1, 2))
        assertEquals(DayPlan.Tense.TODAY, DayPlan.tense(2, 2))
        assertEquals(DayPlan.Tense.FUTURE, DayPlan.tense(3, 2))
    }
}
