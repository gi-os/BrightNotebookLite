package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.lifecycle.viewModelScope
import com.gios.brightnotebooklite.data.NoteEntity
import com.gios.brightnotebooklite.data.Notebook
import com.gios.brightnotebooklite.data.NotebookDao
import com.gios.brightnotebooklite.util.NoteChecklist
import com.gios.brightnotebooklite.util.NoteLine
import com.gios.brightnotebooklite.util.NoteLines
import com.gios.brightnotebooklite.util.NoteMarkdown
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NoteViewModel(private val dao: NotebookDao, private val id: String) : WheelViewModel<Unit>() {
    val note: StateFlow<NoteEntity?> = dao.observeNote(id)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ticks one line, addressed by its index, never by its text. */
    fun toggle(lineIndex: Int) = update { NoteChecklist.toggle(it, lineIndex) }

    fun replace(body: String) = update { body.trimEnd() }

    private fun update(change: (String) -> String) {
        viewModelScope.launch {
            val current = dao.note(id) ?: return@launch
            val body = change(current.body)
            if (body != current.body) {
                dao.putNote(current.copy(body = body, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun delete(then: () -> Unit) {
        viewModelScope.launch {
            dao.deleteNote(id)
            then()
        }
    }
}

/** One note, read with its checkboxes live. EDIT opens the keyboard on the whole text. */
class NoteScreen(sealedActivity: SealedLightActivity, private val noteId: String) :
    LightScreen<Unit, NoteViewModel>(sealedActivity) {

    private val dao = Notebook.dao(lightContext)

    override val viewModelClass = NoteViewModel::class.java
    override fun createViewModel() = NoteViewModel(dao, noteId)

    private fun edit(body: String) {
        navigateTo({ TextEditScreen(it, title = "Edit note", initial = body, noteTools = true) }) { text ->
            if (text.isBlank()) confirmDelete() else viewModel.replace(text)
        }
    }

    private fun confirmDelete() {
        navigateTo({ ChoiceScreen(it, "Delete note", listOf("Delete it", "Keep it"), "This cannot be undone.") }) { pick ->
            if (pick == 0) viewModel.delete { goBack(null) }
        }
    }

    @Composable
    override fun Content() {
        val note by viewModel.note.collectAsState()
        val current = note
        Page(
            center = LightTopBarCenter.Text(current?.let { NoteMarkdown.firstLine(it.body, 24) } ?: ""),
            left = backButton { goBack(null) },
            bottom = listOf(
                LightBarButton.LightIcon(icon = LightIcons.DELETE, onClick = ::confirmDelete, contentDescription = "Delete"),
                LightBarButton.Text(text = "EDIT", onClick = { edit(current?.body.orEmpty()) }),
                null,
            ),
            wheel = viewModel.wheel,
        ) {
            current?.let { n ->
                NoteLines.parse(n.body).forEach { line ->
                    NoteLineView(line, onToggle = viewModel::toggle)
                }
            }
        }
    }
}

@Composable
private fun NoteLineView(line: NoteLine, onToggle: (Int) -> Unit) {
    val unit = 1f.gridUnitsAsDp()
    when (line) {
        is NoteLine.Checkbox -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = unit * line.indent, top = unit / 8, bottom = unit / 8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The box is its own tap target: ticking does not open the editor.
            Box(
                modifier = Modifier
                    .lightClickable { onToggle(line.index) }
                    .padding(end = unit * 0.6f, top = unit / 4, bottom = unit / 4),
            ) {
                CheckGlyph(line.checked)
            }
            RichText(line.text, lighten = line.checked, strike = line.checked)
        }
        is NoteLine.Bullet -> Row(modifier = Modifier.padding(start = unit * line.indent)) {
            RichText("•", modifier = Modifier.width(unit * 1.2f))
            RichText(line.text)
        }
        is NoteLine.Numbered -> Row(modifier = Modifier.padding(start = unit * line.indent)) {
            RichText("${line.number}.", modifier = Modifier.width(unit * 1.6f))
            RichText(line.text)
        }
        is NoteLine.Plain -> RichText(line.text.ifEmpty { " " })
    }
}

@Composable
private fun CheckGlyph(checked: Boolean) {
    val colors = LightThemeTokens.colors
    val side = 1f.gridUnitsAsDp()
    Box(
        modifier = Modifier
            .size(side)
            .border(width = side / 10, color = colors.content),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(side * 0.55f)
                    .background(colors.content),
            )
        }
    }
}

/** A line of note copy with its `**bold**` runs drawn bold. */
@Composable
private fun RichText(
    text: String,
    modifier: Modifier = Modifier,
    lighten: Boolean = false,
    strike: Boolean = false,
) {
    val colors = LightThemeTokens.colors
    val annotated = buildAnnotatedString {
        NoteLines.boldRuns(text).forEach { (run, bold) ->
            if (bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(run) } else append(run)
        }
    }
    Text(
        text = annotated,
        modifier = modifier,
        style = scaled(LightThemeTokens.typography.copy),
        color = if (lighten) colors.contentSecondary else colors.content,
        textDecoration = if (strike) TextDecoration.LineThrough else null,
    )
}
