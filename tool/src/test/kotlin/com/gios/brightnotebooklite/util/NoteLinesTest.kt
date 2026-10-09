package com.gios.brightnotebooklite.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NoteLinesTest {

    @Test
    fun classifiesEachLine() {
        val lines = NoteLines.parse("title\n- [ ] milk\n- [x] eggs\n- bread\n1. first\n  - nested")
        assertTrue(lines[0] is NoteLine.Plain)
        assertEquals(NoteLine.Checkbox(1, 0, false, "milk"), lines[1])
        assertEquals(NoteLine.Checkbox(2, 0, true, "eggs"), lines[2])
        assertEquals(NoteLine.Bullet(3, 0, "bread"), lines[3])
        assertEquals(NoteLine.Numbered(4, 0, "1", "first"), lines[4])
        assertEquals(NoteLine.Bullet(5, 1, "nested"), lines[5])
    }

    @Test
    fun lineIndicesMatchTheChecklistToggle() {
        val note = "- [ ] milk\n- [ ] milk"
        val second = NoteLines.parse(note)[1] as NoteLine.Checkbox
        assertEquals("- [ ] milk\n- [x] milk", NoteChecklist.toggle(note, second.index))
    }

    @Test
    fun boldRunsAlternate() {
        assertEquals(listOf("buy " to false, "milk" to true, " now" to false), NoteLines.boldRuns("buy **milk** now"))
    }

    @Test
    fun unmatchedMarkerStaysLiteral() {
        assertEquals(listOf("a " to false, "b" to true, " c ** d" to false), NoteLines.boldRuns("a **b** c ** d"))
        assertEquals(listOf("half **open" to false), NoteLines.boldRuns("half **open"))
    }

    @Test
    fun checkboxButtonAddsAndRemoves() {
        val added = NoteLines.toggleCheckbox("milk", 4)
        assertEquals("- [ ] milk", added.text)
        assertEquals(10, added.selStart)
        assertEquals("milk", NoteLines.toggleCheckbox(added.text, added.selStart).text)
        assertEquals("a\n- [ ] b", NoteLines.toggleCheckbox("a\n- b", 5).text)
    }
}
