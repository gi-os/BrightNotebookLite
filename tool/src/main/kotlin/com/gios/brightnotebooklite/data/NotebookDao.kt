package com.gios.brightnotebooklite.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    /* ---------- notes ---------- */

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun observeNote(id: String): Flow<NoteEntity?>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun note(id: String): NoteEntity?

    /** Notes written or returned to inside a window, for the day view. */
    @Query(
        "SELECT * FROM notes WHERE (createdAt >= :fromMs AND createdAt < :toMs) " +
            "OR (updatedAt >= :fromMs AND updatedAt < :toMs)",
    )
    fun observeNotesTouched(fromMs: Long, toMs: Long): Flow<List<NoteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNote(id: String)

    /* ---------- calendars ---------- */

    @Query("SELECT * FROM calendars ORDER BY createdAt")
    fun observeCalendars(): Flow<List<CalendarEntity>>

    @Query("SELECT * FROM calendars ORDER BY createdAt")
    suspend fun calendars(): List<CalendarEntity>

    @Query("SELECT * FROM calendars WHERE id = :id")
    suspend fun calendar(id: String): CalendarEntity?

    @Query("SELECT * FROM calendars WHERE url = :url LIMIT 1")
    suspend fun calendarByUrl(url: String): CalendarEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCalendar(calendar: CalendarEntity)

    @Query("DELETE FROM calendars WHERE id = :id")
    suspend fun deleteCalendarRow(id: String)

    @Query("DELETE FROM entries WHERE calendarId = :calendarId")
    suspend fun deleteEntriesOf(calendarId: String)

    @Transaction
    suspend fun deleteCalendar(id: String) {
        deleteEntriesOf(id)
        deleteCalendarRow(id)
    }

    /**
     * A refresh swaps a feed's rows in one transaction, so the calendar is never seen half
     * empty and a refresh that fails part way leaves the previous copy in place.
     */
    @Transaction
    suspend fun replaceEntriesOf(calendar: CalendarEntity, entries: List<EntryEntity>) {
        deleteEntriesOf(calendar.id)
        putEntries(entries)
        putCalendar(calendar)
    }

    /* ---------- entries ---------- */

    /**
     * Everything that can touch the window [from, to]: rows that start before its end and
     * finish after its start, plus every repeating series that has begun by then. Series are
     * expanded in Kotlin by `Recurrence`, bounded by the same window.
     */
    @Query(
        "SELECT * FROM entries WHERE epochDay <= :to AND " +
            "(rrule IS NOT NULL OR COALESCE(endEpochDay, epochDay) >= :from)",
    )
    fun observeRange(from: Long, to: Long): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries WHERE id = :id")
    fun observeEntry(id: String): Flow<EntryEntity?>

    @Query("SELECT * FROM entries WHERE id = :id")
    suspend fun entry(id: String): EntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntry(entry: EntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putEntries(entries: List<EntryEntity>)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: String)
}
