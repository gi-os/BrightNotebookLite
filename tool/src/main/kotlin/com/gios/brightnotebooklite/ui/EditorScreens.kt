package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import com.gios.brightnotebooklite.util.Edit
import com.gios.brightnotebooklite.util.NoteLines
import com.gios.brightnotebooklite.util.NoteMarkdown
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * Full-screen typing with the LP3 keyboard. Returns the text on SAVE; going back returns nothing.
 *
 * With [noteTools] on, the editor behaves like a note: a new line after a list item carries the
 * marker down (and an empty item ends the list), and the bottom bar has bold and checkbox keys.
 */
class TextEditScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val initial: String,
    private val singleLine: Boolean = false,
    private val noteTools: Boolean = false,
    private val submitLabel: String = "SAVE",
) : SimpleLightScreen<String>(sealedActivity) {

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        val state = rememberTextFieldState(initial, TextRange(initial.length))
        if (noteTools) ListContinuation(state)
        Themed {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = { goBack(it.toString()) },
                onBack = { goBack(null) },
                keyboardOptionsFlow = keyboardOptions,
                modifier = Modifier.background(LightThemeTokens.colors.background),
                submitLabel = submitLabel,
                singleLine = singleLine,
                initialCaps = initial.isEmpty() && !singleLine,
                leftBottomBarItem = if (noteTools) {
                    LightBarButton.Text(text = "B", contentDescription = "Bold") {
                        val sel = state.selection
                        applyEdit(state, NoteMarkdown.toggleBold(state.text.toString(), sel.min, sel.max))
                    }
                } else {
                    null
                },
                rightBottomBarItem = if (noteTools) {
                    LightBarButton.Text(text = "[ ]", contentDescription = "Checkbox") {
                        applyEdit(state, NoteLines.toggleCheckbox(state.text.toString(), state.selection.min))
                    }
                } else {
                    null
                },
            )
        }
    }
}

private fun applyEdit(state: TextFieldState, edit: Edit) {
    state.edit {
        replace(0, length, edit.text)
        selection = TextRange(edit.selStart.coerceIn(0, edit.text.length), edit.selEnd.coerceIn(0, edit.text.length))
    }
}

/**
 * Watches for a newline typed at the caret and lets [NoteMarkdown.continueList] carry a bullet,
 * number or checkbox down to the new line.
 */
@Composable
private fun ListContinuation(state: TextFieldState) {
    LaunchedEffect(state) {
        var previous = state.text.toString()
        snapshotFlow { state.text.toString() to state.selection }.collect { (text, sel) ->
            val typedNewline = text.length == previous.length + 1 &&
                sel.collapsed && sel.start > 0 && text[sel.start - 1] == '\n'
            previous = text
            if (!typedNewline) return@collect
            val edit = continueChecklist(text, sel.start) ?: NoteMarkdown.continueList(text, sel.start)
            if (edit != null) {
                previous = edit.text
                applyEdit(state, edit)
            }
        }
    }
}

private val OPEN_BOX = Regex("^([ \t]*)[-*] \\[[ xX]\\] ")

/**
 * A checkbox line carries an empty box down; an empty checkbox line is taken away instead, the
 * same way an empty bullet ends a list.
 */
private fun continueChecklist(text: String, cursor: Int): Edit? {
    val prevEnd = cursor - 1
    val prevStart = text.lastIndexOf('\n', prevEnd - 1) + 1
    val prev = text.substring(prevStart, prevEnd)
    val m = OPEN_BOX.find(prev) ?: return null
    if (prev.length == m.value.length || prev.substring(m.value.length).isBlank()) {
        return Edit(text.removeRange(prevStart, prevEnd), prevStart + 1)
    }
    val marker = m.groupValues[1] + "- [ ] "
    return Edit(text.replaceRange(cursor, cursor, marker), cursor + marker.length)
}

/**
 * A short list to pick one thing from. Returns the index picked; going back returns nothing.
 */
class ChoiceScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val options: List<String>,
    private val prompt: String? = null,
) : SimpleLightScreen<Int>(sealedActivity) {

    @Composable
    override fun Content() {
        Page(
            center = com.thelightphone.sdk.ui.LightTopBarCenter.Text(title),
            left = backButton { goBack(null) },
        ) {
            if (prompt != null) SectionLabel(prompt)
            options.forEachIndexed { i, option ->
                ListRow(title = option, onClick = { goBack(i) })
            }
        }
    }
}
