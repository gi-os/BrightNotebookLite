package com.gios.brightnotebooklite.util

import java.time.ZoneId

/**
 * What a day holds, worked out from plain values so it can be tested off-device.
 *
 * A day is a column of rows: holidays and all-day entries first, then everything with a time in
 * the order it happens. Notes appear too, at the moment you wrote them or came back to them, so a
 * day that has gone reads as a diary of what happened on it. Today is split by a now line.
 */
object DayPlan {

    /** An entry as the planner needs it: no Room, no Android. */
    data class Entry(
        val id: String,
        val epochDay: Long,
        val endEpochDay: Long? = null,
        val text: String,
        val startMinutes: Int? = null,
        val endMinutes: Int? = null,
        val rrule: String? = null,
        val exDays: Set<Long> = emptySet(),
        val calendarId: String? = null,
    ) {
        val spanDays: Long get() = ((endEpochDay ?: epochDay) - epochDay).coerceAtLeast(0)
    }

    data class Note(val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

    enum class Kind { HOLIDAY, ENTRY, WROTE, CAME_BACK }

    /**
     * One row on the day screen.
     *
     * [sortMinutes] is minutes into the *journal* day (from the 4am cutover), which is the one
     * axis typed times and note timestamps can share. Null means all day.
     * [occurrenceStart] is the day this occurrence of a series began, which is what "just this
     * one" edits are addressed by.
     */
    data class Row(
        val kind: Kind,
        val refId: String?,
        val title: String,
        val sortMinutes: Int?,
        val clockMinutes: Int?,
        val endClockMinutes: Int? = null,
        val occurrenceStart: Long? = null,
        val dayOfSpan: Int = 0,
        val spanLength: Int = 1,
        val calendarId: String? = null,
    )

    /**
     * The day each occurrence of [entry] that covers [day] started on, or null if none does.
     * A multi-day series counts on every day it covers, not only its first.
     */
    fun occurrenceCovering(entry: Entry, day: Long): Long? {
        val span = entry.spanDays
        return Recurrence.expand(entry.rrule, entry.epochDay, day - span, day, entry.exDays)
            .lastOrNull()
    }

    /** Which days in [from, to] have at least one entry on them, for the month grid's dots. */
    fun busyDays(entries: List<Entry>, from: Long, to: Long): Set<Long> {
        val out = HashSet<Long>()
        for (e in entries) {
            val span = e.spanDays
            for (start in Recurrence.expand(e.rrule, e.epochDay, from - span, to, e.exDays)) {
                var d = maxOf(start, from)
                val last = minOf(start + span, to)
                while (d <= last) {
                    out.add(d)
                    d++
                }
            }
        }
        return out
    }

    fun rows(
        day: Long,
        entries: List<Entry>,
        notes: List<Note>,
        showHolidays: Boolean,
        zone: ZoneId,
    ): List<Row> {
        val out = mutableListOf<Row>()

        if (showHolidays) {
            Holidays.inRange(day, day).forEach { h ->
                out.add(Row(Kind.HOLIDAY, null, h.label, sortMinutes = null, clockMinutes = null))
            }
        }

        for (e in entries) {
            val start = occurrenceCovering(e, day) ?: continue
            val dayOfSpan = (day - start).toInt()
            // On the middle days of a trip the time means nothing; it belongs to the first day.
            val timed = dayOfSpan == 0 && e.startMinutes != null
            out.add(
                Row(
                    kind = Kind.ENTRY,
                    refId = e.id,
                    title = e.text,
                    sortMinutes = if (timed) JournalDay.fromClockMinutes(e.startMinutes!!) else null,
                    clockMinutes = if (timed) e.startMinutes else null,
                    endClockMinutes = if (timed) e.endMinutes else null,
                    occurrenceStart = start,
                    dayOfSpan = dayOfSpan,
                    spanLength = e.spanDays.toInt() + 1,
                    calendarId = e.calendarId,
                ),
            )
        }

        val window = JournalDay.windowMs(day, zone)
        for (n in notes) {
            // One row per note per day: written and returned to on the same day is the writing.
            val (kind, at) = when {
                n.createdAt in window -> Kind.WROTE to n.createdAt
                n.updatedAt in window -> Kind.CAME_BACK to n.updatedAt
                else -> continue
            }
            val minutes = JournalDay.minutesInto(at, day, zone)
            out.add(
                Row(
                    kind = kind,
                    refId = n.id,
                    title = n.title,
                    sortMinutes = minutes,
                    clockMinutes = JournalDay.clockMinutes(minutes),
                ),
            )
        }

        return out.sortedWith(
            compareBy<Row>({ it.sortMinutes != null }, { it.sortMinutes ?: 0 }, { it.kind.ordinal }, { it.title }),
        )
    }

    /** Where a day stands against now. */
    enum class Tense { PAST, TODAY, FUTURE }

    fun tense(day: Long, today: Long): Tense = when {
        day < today -> Tense.PAST
        day > today -> Tense.FUTURE
        else -> Tense.TODAY
    }

    /**
     * Today's rows split by the now line: everything that has happened, then everything still to
     * come. All-day rows belong to the day as a whole and sit above the line with what happened.
     */
    fun splitAtNow(rows: List<Row>, nowMinutes: Int): Pair<List<Row>, List<Row>> {
        val before = rows.filter { it.sortMinutes == null || it.sortMinutes <= nowMinutes }
        val after = rows.filter { it.sortMinutes != null && it.sortMinutes > nowMinutes }
        return before to after
    }
}
