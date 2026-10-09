package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.EntryEntity
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.data.Planner
import com.gios.brightnotebooklite.data.Settings
import com.gios.brightnotebooklite.util.DayPlan
import com.gios.brightnotebooklite.util.JournalDay
import com.gios.brightnotebooklite.util.NoteDates
import com.gios.brightnotebooklite.util.NoteMarkdown
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

class DayViewModel(
    private val dao: NotebookDao,
    startDay: Long,
    showHolidays: Flow<Boolean>,
) : WheelViewModel<Unit>() {

    val day = MutableStateFlow(startDay)

    /** Ticks once a minute so the now line moves. */
    private val clock: Flow<Pair<Long, Int>> = flow {
        while (true) {
            emit(NoteDates.today() to NoteDates.nowMinutes())
            delay(60_000L - System.currentTimeMillis() % 60_000L)
        }
    }

    data class State(
        val day: Long,
        val today: Long,
        val nowMinutes: Int,
        val rows: List<DayPlan.Row>,
        val calendars: Map<String, String>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State?> = day.flatMapLatest { d ->
        val zone = ZoneId.systemDefault()
        val window = JournalDay.windowMs(d, zone)
        val notes = dao.observeNotesTouched(window.first, window.last + 1)
        combine(
            Planner.entries(dao, d, d),
            notes,
            showHolidays,
            dao.observeCalendars(),
            clock,
        ) { entries, touched, holidays, calendars, now ->
            val plannedNotes = touched.map {
                DayPlan.Note(it.id, NoteMarkdown.firstLine(it.body).ifBlank { "Untitled" }, it.createdAt, it.updatedAt)
            }
            State(
                day = d,
                today = now.first,
                nowMinutes = now.second,
                rows = DayPlan.rows(d, entries, plannedNotes, holidays, zone),
                calendars = calendars.associate { it.id to it.label },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun step(days: Long) {
        day.value = day.value + days
    }

    /** "9:30 dentist" files itself at half past nine; anything else is all day. */
    fun add(typed: String) {
        val (minutes, text) = NoteDates.splitLeadingTime(typed)
        if (text.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            dao.putEntry(
                EntryEntity(
                    id = UUID.randomUUID().toString(),
                    epochDay = day.value,
                    text = text.trim(),
                    startMinutes = minutes,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}

/**
 * One day. A day that has gone reads as a diary of what happened; a day ahead reads as a plan;
 * today is both, split by a NOW line wherever the clock is.
 */
class DayScreen(sealedActivity: SealedLightActivity, private val startDay: Long) :
    LightScreen<Unit, DayViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = DayViewModel::class.java
    override fun createViewModel() =
        DayViewModel(dao, startDay, Settings.showHolidays(lightContext.dataStore))

    private fun add() {
        val day = viewModel.day.value
        navigateTo({
            TextEditScreen(it, title = "Add to ${NoteDates.shortDate(day)}", initial = "", singleLine = true, submitLabel = "ADD")
        }) { typed -> viewModel.add(typed) }
    }

    private fun open(row: DayPlan.Row) {
        val id = row.refId ?: return
        when (row.kind) {
            DayPlan.Kind.ENTRY -> navigateTo({ EntryScreen(it, id, row.occurrenceStart ?: viewModel.day.value) })
            DayPlan.Kind.WROTE, DayPlan.Kind.CAME_BACK -> navigateTo({ NoteScreen(it, id) })
            DayPlan.Kind.HOLIDAY -> Unit
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val day by viewModel.day.collectAsState()
        val scroll = rememberScrollState()
        val s = state
        val tense = s?.let { DayPlan.tense(it.day, it.today) }
        Page(
            center = LightTopBarCenter.TwoLineDetail(
                NoteDates.dayTitle(day),
                when (tense) {
                    DayPlan.Tense.PAST -> "what happened"
                    DayPlan.Tense.TODAY -> "today"
                    DayPlan.Tense.FUTURE -> "ahead"
                    null -> ""
                },
            ),
            left = backButton { goBack(null) },
            bottom = listOf(
                LightBarButton.LightIcon(icon = LightIcons.UP, onClick = { viewModel.step(-1) }, contentDescription = "Previous day"),
                LightBarButton.Text(text = "ADD", onClick = ::add),
                LightBarButton.LightIcon(icon = LightIcons.DOWN, onClick = { viewModel.step(1) }, contentDescription = "Next day"),
            ),
            wheel = viewModel.wheel,
            scrollState = scroll,
        ) {
            if (s == null || s.day != day) return@Page
            if (s.rows.isEmpty()) {
                EmptyLine(if (tense == DayPlan.Tense.PAST) "Nothing written on this day." else "Nothing planned. ADD puts something here; start with a time, like 9:30 dentist.")
            }
            if (tense == DayPlan.Tense.TODAY) {
                val (before, after) = DayPlan.splitAtNow(s.rows, s.nowMinutes)
                before.forEach { DayRowView(it, s, past = it.sortMinutes != null) }
                NowLine(JournalDay.clockMinutes(s.nowMinutes))
                after.forEach { DayRowView(it, s, past = false) }
            } else {
                s.rows.forEach { DayRowView(it, s, past = tense == DayPlan.Tense.PAST) }
            }
        }
    }

    @Composable
    private fun DayRowView(row: DayPlan.Row, s: DayViewModel.State, past: Boolean) {
        val time = NoteDates.clock(row.clockMinutes)
        val end = NoteDates.clock(row.endClockMinutes)
        val detail = when (row.kind) {
            DayPlan.Kind.HOLIDAY -> "US holiday"
            DayPlan.Kind.WROTE -> listOfNotNull("Wrote this", time).joinToString(" · ")
            DayPlan.Kind.CAME_BACK -> listOfNotNull("Came back to this", time).joinToString(" · ")
            DayPlan.Kind.ENTRY -> listOfNotNull(
                when {
                    time != null && end != null -> "$time – $end"
                    time != null -> time
                    row.spanLength > 1 -> "Day ${row.dayOfSpan + 1} of ${row.spanLength}"
                    else -> null
                },
                row.calendarId?.let { s.calendars[it] },
            ).joinToString(" · ").ifBlank { null }
        }
        ListRow(
            title = row.title,
            detail = detail,
            lighten = past && row.kind != DayPlan.Kind.HOLIDAY,
            onClick = if (row.refId != null) ({ open(row) }) else null,
        )
    }
}

@Composable
private fun NowLine(clockMinutes: Int) {
    val unit = 1f.gridUnitsAsDp()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = unit / 3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LightText(
            text = "NOW ${NoteDates.clock(clockMinutes).orEmpty()}",
            variant = LightTextVariant.Fine,
            modifier = Modifier.padding(end = unit / 2),
        )
        Rule(color = LightThemeTokens.colors.content)
    }
}
