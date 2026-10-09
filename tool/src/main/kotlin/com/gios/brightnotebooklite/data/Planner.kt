package com.gios.brightnotebooklite.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.gios.brightnotebooklite.util.DayPlan
import com.gios.brightnotebooklite.util.Recurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Glue between Room rows and the Android-free [DayPlan]. */
object Planner {

    /** Entries touching [from, to], minus anything on a hidden calendar. */
    fun entries(dao: NotebookDao, from: Long, to: Long): Flow<List<DayPlan.Entry>> =
        combine(dao.observeRange(from, to), dao.observeCalendars()) { rows, calendars ->
            val hidden = calendars.filterNot { it.visible }.map { it.id }.toSet()
            rows.filter { it.calendarId == null || it.calendarId !in hidden }.map { it.toPlan() }
        }

    fun EntryEntity.toPlan(): DayPlan.Entry = DayPlan.Entry(
        id = id,
        epochDay = epochDay,
        endEpochDay = endEpochDay,
        text = text,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        rrule = rrule,
        exDays = Recurrence.parseExDays(exDays),
        calendarId = calendarId,
    )
}

/** The tool's few settings, in the SDK's shared DataStore. */
object Settings {
    private val SHOW_HOLIDAYS = booleanPreferencesKey("show_us_holidays")

    fun showHolidays(store: DataStore<Preferences>): Flow<Boolean> =
        store.data.map { it[SHOW_HOLIDAYS] ?: true }

    suspend fun setShowHolidays(store: DataStore<Preferences>, show: Boolean) {
        store.edit { it[SHOW_HOLIDAYS] = show }
    }
}
