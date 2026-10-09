package com.gios.brightnotebooklite.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A note is its text. Bold, bullets, numbers and checkboxes are characters inside [body], so
 * there is nothing to migrate and nothing to lose when the note is read anywhere else.
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A calendar entries can belong to. Entries typed on the phone have no calendar at all; this
 * table only holds subscribed feeds, so it is also the list the hourly refresh walks.
 */
@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey val id: String,
    val label: String,
    /** The feed address, already normalised by `CalendarUrl.feedIn`. */
    val url: String,
    val visible: Boolean = true,
    val lastSyncAt: Long? = null,
    /** Why the last refresh failed, or null when it worked. */
    val lastError: String? = null,
    val eventCount: Int = 0,
    val createdAt: Long,
)

/**
 * One thing on the calendar. A repeating series is one row: [rrule] holds the RFC 5545 rule and
 * the days it lands on are worked out for whatever window is on screen.
 */
@Entity(
    tableName = "entries",
    indices = [Index("epochDay"), Index("calendarId")],
)
data class EntryEntity(
    @PrimaryKey val id: String,
    val epochDay: Long,
    /** Last day of a multi-day entry, inclusive. Null for an entry on one day. */
    val endEpochDay: Long? = null,
    val text: String,
    /** Minutes from midnight; null means all day. */
    val startMinutes: Int? = null,
    val endMinutes: Int? = null,
    /** Null for entries written on the phone; the subscription's id for imported ones. */
    val calendarId: String? = null,
    /** The feed's UID, so a refresh can tell its own rows apart. */
    val sourceUid: String? = null,
    val rrule: String? = null,
    /** Comma-separated epoch days the series skips ("just this one" deletes). */
    @ColumnInfo(name = "exDays") val exDays: String? = null,
    val location: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val isImported: Boolean get() = calendarId != null
    val repeats: Boolean get() = !rrule.isNullOrBlank()
    val spanDays: Long get() = ((endEpochDay ?: epochDay) - epochDay).coerceAtLeast(0)
}
