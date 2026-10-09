package com.gios.brightnotebooklite.util

/**
 * How one line of a note is drawn. The text is never changed by reading it this way; this only
 * decides which glyph goes in front and which runs are bold.
 */
sealed class NoteLine {
    abstract val index: Int

    data class Checkbox(override val index: Int, val indent: Int, val checked: Boolean, val text: String) : NoteLine()
    data class Bullet(override val index: Int, val indent: Int, val text: String) : NoteLine()
    data class Numbered(override val index: Int, val indent: Int, val number: String, val text: String) : NoteLine()
    data class Plain(override val index: Int, val text: String) : NoteLine()
}

object NoteLines {
    private val CHECKBOX = Regex("^([ \t]*)[-*] \\[([ xX])\\]([ \t][\\s\\S]*|)$")
    private val BULLET = Regex("^([ \t]*)[-*] (.*)$")
    private val NUMBER = Regex("^([ \t]*)(\\d+)\\. (.*)$")

    fun parse(body: String): List<NoteLine> = body.split("\n").mapIndexed { i, raw ->
        val line = raw.removeSuffix("\r")
        CHECKBOX.matchEntire(line)?.let { m ->
            return@mapIndexed NoteLine.Checkbox(i, indentOf(m.groupValues[1]), m.groupValues[2] != " ", m.groupValues[3].trim())
        }
        NUMBER.matchEntire(line)?.let { m ->
            return@mapIndexed NoteLine.Numbered(i, indentOf(m.groupValues[1]), m.groupValues[2], m.groupValues[3])
        }
        BULLET.matchEntire(line)?.let { m ->
            return@mapIndexed NoteLine.Bullet(i, indentOf(m.groupValues[1]), m.groupValues[2])
        }
        NoteLine.Plain(i, line)
    }

    /** Indent depth: a tab or two spaces is one level. */
    private fun indentOf(ws: String): Int = ws.fold(0) { acc, c -> acc + if (c == '\t') 2 else 1 } / 2

    /**
     * `**bold**` runs as (text, isBold) pairs. An unmatched `**` is kept as typed, so a half
     * finished line never swallows the rest of itself.
     */
    fun boldRuns(text: String): List<Pair<String, Boolean>> {
        val parts = text.split("**").toMutableList()
        if (parts.size % 2 == 0) {
            val tail = parts.removeAt(parts.lastIndex)
            parts[parts.lastIndex] = parts.last() + "**" + tail
        }
        return parts.mapIndexedNotNull { i, s -> if (s.isEmpty()) null else s to (i % 2 == 1) }
    }

    /**
     * Puts a checkbox on the line holding [cursor], or takes it off again. Used by the editor's
     * checkbox button; ticking happens on the reading view by line number.
     */
    fun toggleCheckbox(text: String, cursor: Int): Edit {
        val at = cursor.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0)).let { if (at == 0) 0 else it + 1 }
        val lineEnd = text.indexOf('\n', at).let { if (it == -1) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        val rest = line.substring(indent.length)
        val box = Regex("^[-*] \\[[ xX]\\] ?")
        val bullet = Regex("^[-*] ")
        val replaced = when {
            box.containsMatchIn(rest) -> indent + rest.replaceFirst(box, "")
            bullet.containsMatchIn(rest) -> indent + rest.replaceFirst(bullet, "- [ ] ")
            else -> "$indent- [ ] $rest"
        }
        val updated = text.replaceRange(lineStart, lineEnd, replaced)
        return Edit(updated, (at + replaced.length - line.length).coerceIn(lineStart, lineStart + replaced.length))
    }
}
