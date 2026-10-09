package com.gios.brightnotebooklite.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.NoteEntity
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.util.NoteDates
import com.gios.brightnotebooklite.util.NoteMarkdown
import com.gios.brightnotebooklite.work.scheduleRefresh
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightTopBarCenter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

class NotesViewModel(private val dao: NotebookDao) : WheelViewModel<Unit>() {
    val notes: StateFlow<List<NoteEntity>?> = dao.observeNotes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Saves a new note and hands back its id, or null when nothing was typed. */
    fun create(body: String, then: (String) -> Unit) {
        if (body.isBlank()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val note = NoteEntity(UUID.randomUUID().toString(), body.trimEnd(), now, now)
            dao.putNote(note)
            then(note.id)
        }
    }
}

/** Every note, most recently touched first. The calendar and the subscriptions are a tap away. */
@InitialScreen
class NotesScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, NotesViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = NotesViewModel::class.java
    override fun createViewModel() = NotesViewModel(dao)

    override fun willShow() {
        scheduleRefresh(lightContext)
    }

    private fun openNote(id: String) {
        navigateTo({ NoteScreen(it, id) })
    }

    private fun newNote() {
        navigateTo({ TextEditScreen(it, title = "New note", initial = "", noteTools = true) }) { body ->
            viewModel.create(body) { id -> openNote(id) }
        }
    }

    @Composable
    override fun Content() {
        val notes by viewModel.notes.collectAsState()
        Page(
            center = LightTopBarCenter.Text("Notebook"),
            left = LightBarButton.LightIcon(
                icon = LightIcons.SETTINGS,
                onClick = { navigateTo(::CalendarsScreen) },
                contentDescription = "Calendars",
            ),
            right = LightBarButton.Text(text = "CALENDAR", onClick = { navigateTo({ CalendarScreen(it) }) }),
            bottom = listOf(LightBarButton.Text(text = "NEW NOTE", onClick = ::newNote)),
            wheel = viewModel.wheel,
        ) {
            val list = notes
            when {
                list == null -> Unit
                list.isEmpty() -> EmptyLine("No notes yet. NEW NOTE starts one.")
                else -> list.forEach { note ->
                    val day = java.time.Instant.ofEpochMilli(note.updatedAt)
                        .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
                    ListRow(
                        title = NoteMarkdown.firstLine(note.body).ifBlank { "Untitled" },
                        detail = NoteDates.shortDate(day) + previewTail(note.body),
                        onClick = { openNote(note.id) },
                    )
                }
            }
        }
    }

    private fun previewTail(body: String): String {
        val first = NoteMarkdown.firstLine(body, maxChars = 10_000)
        val rest = NoteMarkdown.preview(body, maxChars = 400).removePrefix(first).trim()
        if (rest.isEmpty()) return ""
        return "  " + if (rest.length <= 40) rest else rest.take(40).trimEnd() + "…"
    }
}
