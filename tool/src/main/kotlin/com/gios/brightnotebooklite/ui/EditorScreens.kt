package com.gios.brightnotebooklite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextInputEditor
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBarCenter

/**
 * Full-screen typing with the LP3 keyboard. Returns the text on SAVE; going back returns nothing,
 * and the on-screen back arrow asks first when the text was changed.
 *
 * The system back pops the screen without asking it, and tools may not intercept it. Text left
 * that way is kept as a draft and comes back the next time the same editor opens.
 *
 * [validate] runs on SAVE. A message it returns is shown and the editor stays open with the text
 * as typed.
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
    private val validate: ((String) -> String?)? = null,
) : SimpleLightScreen<String>(sealedActivity) {

    private val draftKey = "$title\u0000$initial"
    private val startText = drafts[draftKey] ?: initial

    // Kept here, not remembered, so the text survives the discard and error pages below and can
    // be saved as a draft.
    private val state = TextFieldState(startText, TextRange(startText.length))
    private var discarding by mutableStateOf(false)
    private var problem by mutableStateOf<String?>(null)
    private var finished = false

    private fun changed(): Boolean = state.text.toString() != initial

    private fun finish(result: String?) {
        finished = true
        goBack(result)
    }

    private fun back() {
        if (changed()) discarding = true else finish(null)
    }

    override fun onScreenDestroy() {
        if (!finished && changed()) drafts[draftKey] = state.text.toString() else drafts.remove(draftKey)
    }

    @Composable
    override fun Content() {
        val keyboardOptions = rememberKeyboardOptions()
        if (noteTools) ListContinuation(state)
        if (discarding) {
            DiscardPage(
                title = "Discard changes?",
                onDiscard = { finish(null) },
                onKeep = { discarding = false },
            )
            return
        }
        val message = problem
        if (message != null) {
            Page(
                center = LightTopBarCenter.Text(title),
                left = backButton { problem = null },
                bottom = listOf(LightBarButton.Text(text = "TRY AGAIN", onClick = { problem = null })),
            ) {
                LightText(text = message, variant = LightTextVariant.Copy)
            }
            return
        }
        Themed {
            LightTextInputEditor(
                title = title,
                state = state,
                onSubmit = {
                    val text = it.toString()
                    val issue = validate?.invoke(text)
                    if (issue != null) problem = issue else finish(text)
                },
                onBack = ::back,
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

    private companion object {
        /** Unsaved text left by the system back, by editor. Lives as long as the tool's process. */
        val drafts = mutableMapOf<String, String>()
    }
}

/** Asks whether to throw away typed text. Back keeps editing. */
@Composable
fun DiscardPage(title: String, onDiscard: () -> Unit, onKeep: () -> Unit) {
    Page(
        center = LightTopBarCenter.Text(title),
        left = backButton(onKeep),
    ) {
        ListRow(title = "Discard", onClick = onDiscard)
        ListRow(title = "Keep editing", onClick = onKeep)
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
