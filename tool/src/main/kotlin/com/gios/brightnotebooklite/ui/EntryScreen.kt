package com.gios.brightnotebooklite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.EntryEntity
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.util.NoteDates
import com.gios.brightnotebooklite.util.Recurrence
import com.gios.brightnotebooklite.util.Repeats
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class EntryViewModel(
    private val dao: NotebookDao,
    entryId: String,
    /** The day the occurrence that was tapped began on. */
    val occurrence: Long,
) : WheelViewModel<Unit>() {

    private val id = MutableStateFlow(entryId)

    data class State(val entry: EntryEntity?, val calendarLabel: String?)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State?> = id.flatMapLatest { current ->
        combine(dao.observeEntry(current), dao.observeCalendars()) { entry, calendars ->
            State(entry, entry?.calendarId?.let { cid -> calendars.firstOrNull { it.id == cid }?.label })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Applies [change] to the whole series (or the single entry). */
    fun updateAll(change: (EntryEntity) -> EntryEntity) {
        viewModelScope.launch {
            val current = dao.entry(id.value) ?: return@launch
            dao.putEntry(change(current).copy(updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * "Just this one": the occurrence becomes an entry of its own and the series skips that day,
     * then [change] is applied to the new entry only. This screen follows the new entry from here.
     */
    fun updateOne(change: (EntryEntity) -> EntryEntity) {
        viewModelScope.launch {
            val series = dao.entry(id.value) ?: return@launch
            val now = System.currentTimeMillis()
            val single = series.copy(
                id = UUID.randomUUID().toString(),
                epochDay = occurrence,
                endEpochDay = series.endEpochDay?.let { occurrence + series.spanDays },
                rrule = null,
                exDays = null,
                createdAt = now,
                updatedAt = now,
            )
            dao.putEntry(skip(series, occurrence, now))
            dao.putEntry(change(single))
            id.value = single.id
        }
    }

    fun deleteAll(then: () -> Unit) {
        viewModelScope.launch {
            dao.deleteEntry(id.value)
            then()
        }
    }

    /** "Just this one" for a delete: an exception date on the series, nothing else. */
    fun deleteOne(then: () -> Unit) {
        viewModelScope.launch {
            val series = dao.entry(id.value) ?: return@launch
            dao.putEntry(skip(series, occurrence, System.currentTimeMillis()))
            then()
        }
    }

    private fun skip(series: EntryEntity, day: Long, now: Long): EntryEntity = series.copy(
        exDays = Recurrence.formatExDays(Recurrence.parseExDays(series.exDays) + day),
        updatedAt = now,
    )
}

/** One calendar entry: what, when, how long, whether it repeats. */
class EntryScreen(
    sealedActivity: SealedLightActivity,
    private val entryId: String,
    private val occurrence: Long,
) : LightScreen<Unit, EntryViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = EntryViewModel::class.java
    override fun createViewModel() = EntryViewModel(dao, entryId, occurrence)

    /** Once answered, "just this one" or "all of them" holds for the rest of this visit. */
    private var scopeAll: Boolean? = null

    private fun current(): EntryEntity? = viewModel.state.value?.entry

    /**
     * Runs [change] on the right rows: straight away for a one-off entry, after asking for a
     * series.
     */
    private fun withScope(change: (EntryEntity) -> EntryEntity) {
        val entry = current() ?: return
        // After "just this one" the screen follows the detached entry, which does not repeat.
        if (!entry.repeats || scopeAll == true) {
            viewModel.updateAll(change)
            return
        }
        navigateTo({ ChoiceScreen(it, "This repeats", listOf("Just this one", "All of them"), "Change which?") }) { pick ->
            if (pick == 0) {
                viewModel.updateOne(change)
            } else {
                scopeAll = true
                viewModel.updateAll(change)
            }
        }
    }

    private fun editText() {
        val entry = current() ?: return
        navigateTo({ TextEditScreen(it, title = "Edit", initial = entry.text, singleLine = true) }) { typed ->
            val (minutes, text) = NoteDates.splitLeadingTime(typed)
            if (text.isBlank()) return@navigateTo
            withScope { e -> e.copy(text = text.trim(), startMinutes = minutes ?: e.startMinutes, endMinutes = if (minutes != null) null else e.endMinutes) }
        }
    }

    private fun editTime() {
        val entry = current() ?: return
        val shown = listOfNotNull(NoteDates.clock(entry.startMinutes), NoteDates.clock(entry.endMinutes)).joinToString(" - ")
        navigateTo({ TextEditScreen(it, title = "Time (empty for all day)", initial = shown, singleLine = true) }) { typed ->
            val parts = typed.split("-", "–", " to ").map { it.trim() }.filter { it.isNotEmpty() }
            val start = parts.getOrNull(0)?.let { NoteDates.parseClock(it) }
            val end = parts.getOrNull(1)?.let { NoteDates.parseClock(it) }?.takeIf { start != null && it > start }
            if (parts.isNotEmpty() && start == null) return@navigateTo
            withScope { e -> e.copy(startMinutes = start, endMinutes = end) }
        }
    }

    private fun editLength() {
        val lengths = listOf(1, 2, 3, 4, 5, 6, 7, 10, 14)
        navigateTo({ ChoiceScreen(it, "Lasts", lengths.map { n -> if (n == 1) "One day" else "$n days" }) }) { pick ->
            val days = lengths[pick].toLong()
            withScope { e -> e.copy(endEpochDay = if (days == 1L) null else e.epochDay + days - 1) }
        }
    }

    private fun editRepeat() {
        val entry = current() ?: return
        val presets = Repeats.presets(entry.epochDay)
        navigateTo({ ChoiceScreen(it, "Repeats", presets.map { p -> p.label }) }) { pick ->
            val rule = presets[pick].rrule
            viewModel.updateAll { e -> e.copy(rrule = Repeats.withEnd(rule, e.rrule), exDays = if (rule == null) null else e.exDays) }
        }
    }

    private fun editUntil() {
        val months = listOf(0L, 1L, 3L, 6L, 12L)
        val from = NoteDates.of(occurrence)
        val labels = months.map { m ->
            if (m == 0L) "Forever" else "Until " + NoteDates.shortDate(from.plusMonths(m).toEpochDay()) + " " + from.plusMonths(m).year
        }
        navigateTo({ ChoiceScreen(it, "Ends", labels) }) { pick ->
            val m = months[pick]
            val until = if (m == 0L) null else from.plusMonths(m).toEpochDay()
            viewModel.updateAll { e -> e.copy(rrule = Repeats.endingOn(e.rrule, until)) }
        }
    }

    private fun delete() {
        val entry = current() ?: return
        if (entry.repeats) {
            navigateTo({ ChoiceScreen(it, "Delete", listOf("Just this one", "All of them", "Keep it"), "This repeats. Delete which?") }) { pick ->
                when (pick) {
                    0 -> viewModel.deleteOne { goBack(null) }
                    1 -> viewModel.deleteAll { goBack(null) }
                }
            }
        } else {
            navigateTo({ ChoiceScreen(it, "Delete", listOf("Delete it", "Keep it")) }) { pick ->
                if (pick == 0) viewModel.deleteAll { goBack(null) }
            }
        }
    }

    @Composable
    override fun Content() {
        val state by viewModel.state.collectAsState()
        val entry = state?.entry
        val readOnly = entry?.isImported == true
        Page(
            center = LightTopBarCenter.Text(NoteDates.dayTitle(occurrence)),
            left = backButton { goBack(null) },
            bottom = if (entry == null || readOnly) {
                null
            } else {
                listOf(LightBarButton.LightIcon(icon = LightIcons.DELETE, onClick = ::delete, contentDescription = "Delete"))
            },
            wheel = viewModel.wheel,
        ) {
            if (state != null && entry == null) {
                EmptyLine("This entry is gone.")
                return@Page
            }
            if (entry == null) return@Page
            val edit: (() -> Unit) -> (() -> Unit)? = { action -> if (readOnly) null else action }

            SectionLabel("What")
            ListRow(title = entry.text, onClick = edit(::editText))

            SectionLabel("When")
            val time = NoteDates.clock(entry.startMinutes)
            val end = NoteDates.clock(entry.endMinutes)
            ListRow(
                title = when {
                    time != null && end != null -> "$time – $end"
                    time != null -> time
                    else -> "All day"
                },
                onClick = edit(::editTime),
            )

            SectionLabel("Lasts")
            val span = entry.spanDays + 1
            ListRow(
                title = if (span == 1L) "One day" else "$span days, to ${NoteDates.dayTitle(occurrence + span - 1)}",
                onClick = edit(::editLength),
            )

            SectionLabel("Repeats")
            ListRow(
                title = Recurrence.describe(entry.rrule, entry.epochDay) ?: "Never",
                onClick = edit(::editRepeat),
            )
            if (entry.repeats) {
                SectionLabel("Ends")
                ListRow(title = Repeats.describeEnd(entry.rrule), onClick = edit(::editUntil))
            }

            entry.location?.let {
                SectionLabel("Where")
                ListRow(title = it)
            }

            if (readOnly) {
                SectionLabel("From")
                ListRow(
                    title = state?.calendarLabel ?: "A subscribed calendar",
                    detail = "Refreshed hourly from the feed. Change it in the calendar it comes from.",
                )
            }
        }
    }
}
