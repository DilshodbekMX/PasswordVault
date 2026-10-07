package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene

/**
 * The block at the end of the note column of our CSV, in both directions (formats.md §4.4,
 * architecture.md D-10a, formats-review P8). It carries what Google's layout has no column for: the
 * account's name (Google ignores the `name` column), the extra fields with their hidden flag, and the
 * website's old password while a change is pending (D-10d). Every later version must read what
 * version 1 writes, so the block has a grammar:
 *
 * ```
 * note    = user-notes [ LF LF block ]                 ; written only when the block has at least one line
 * block   = marker 1*( LF line )
 * marker  = "--- PasswordVault (" count " lines) ---"  ; count: the lines after the marker, ASCII digits
 * line    = label [ " (hidden)" ] ": " text *( LF "  " text )    ; later lines of a value: two spaces
 * ```
 *
 * **Writing** ([note]).
 * - The lines, in this order: `Title: <title>` when the title differs from the host of the first
 *   website (`WebAddress.hostOf`, compared exactly; an entry without a website has no host to differ
 *   from and gets none); one line per extra field; `Old password (hidden): <password>` while a change
 *   is pending.
 * - A label is the field's name, trimmed, with `\` written `\\`, `:` written `\:` and `(` written
 *   `\(`; a line break in a name becomes a space. A user's own field named `Title` or `Old password`
 *   gets a backslash in front (`\Title`), so that it reads back as an ordinary field.
 * - **The user's text never becomes fields.** When the block would have no line and the notes alone
 *   would read as notes and a block, a block with the `Title:` line is written all the same, whatever
 *   the host and also without a website: the reader then takes the writer's marker, and the user's
 *   lines stay in the notes.
 * - The two line feeds are written before the marker also when the notes are empty. Line breaks in a
 *   value are written as line feeds.
 *
 * **Reading** ([read]). A note has a block only when it is ours: a marker line that follows `LF LF`
 * and whose count equals exactly the number of lines after it, to the end of the note. The last such
 * marker is taken. If a line after it is neither a `line` nor a continuation, the whole note is text:
 * nothing of a note is ever lost to the block. A backslash makes the next character literal, so the
 * first `: ` without one ends the label, and a ` (hidden)` without one at its end is the flag. The
 * labels `Title` and `Old password` are reserved only when written with no backslash.
 *
 * **What the grammar leaves open, as this reader settles it** (a later version must read the same):
 * - The count is written as a number from 1 on without a leading zero, and only that spelling is a
 *   marker.
 * - CRLF and a lone CR in a note count as line feeds, so a file that another program saved with
 *   other line ends still reads; the notes come back with line feeds.
 * - A reserved label is reserved with or without the flag, and wherever its line stands in the
 *   block. The first `Title` line gives the name and the first `Old password` line the old password;
 *   a second line with the same label is read as an ordinary field, so that nothing is dropped.
 * - A label may be empty, and so may a value.
 *
 * The marker, `(hidden)` and the two reserved labels stay in English in every language, because the
 * importer reads them. Nothing here trims or cleans what it reads: the importer's rules do that
 * (formats.md §8.4).
 */
object FieldsBlock {
    /** The reserved label of the account's name. */
    const val TITLE_LABEL = "Title"

    /** The reserved label of the password the website still has while a change is pending (D-10d). */
    const val OLD_PASSWORD_LABEL = "Old password"

    private const val MARKER_START = "--- PasswordVault ("
    private const val MARKER_END = " lines) ---"
    private const val HIDDEN = " (hidden)"
    private const val SEPARATOR = ": "
    private const val CONTINUATION = "  "

    /** A count has at most this many digits: more lines than that fit in no note. */
    private const val MAX_COUNT_DIGITS = 9

    /** A note as [read] understands it. */
    class Block(
        /**
         * The user's notes: everything before the `LF LF` in front of the marker, or the whole note
         * when it has no block. Line breaks are line feeds.
         */
        val notes: String,
        /** The text of the `Title:` line, or null when the block has none: the name column holds the name then. */
        val title: String?,
        /** The extra fields, in the order of their lines. */
        val fields: List<CustomField>,
        /**
         * The text of the `Old password` line, or null when the block has none. Not null: a change is
         * pending, and this is `pendingOldPassword` (D-10d).
         */
        val oldPassword: String?,
        /** Whether the note ends with a block of ours. */
        val found: Boolean,
    ) {
        override fun toString(): String =
            "Block(found=$found, title=${title != null}, fields=${fields.size}, oldPassword=${oldPassword != null}, texts hidden)"
    }

    /** The marker line of a block of [count] lines. */
    fun marker(count: Int): String {
        require(count >= 1) { "a block has at least one line" }
        return MARKER_START + count + MARKER_END
    }

    /**
     * The note column of [entry]: its notes and, when there is something to carry, the block. The
     * first website is the first one the CSV writes: the first that is not blank.
     */
    fun note(entry: Entry): String {
        val firstWebsite = entry.urls.asSequence().map(EntryRules::normalizeUrl).firstOrNull { it.isNotEmpty() }
        return note(
            notes = entry.notes,
            title = entry.title,
            host = firstWebsite?.let(WebAddress::hostOf),
            fields = entry.fields,
            oldPassword = if (entry.changePending) entry.pendingOldPassword else null,
        )
    }

    /**
     * The note column of an account with these [notes], this [title], [host] as the host of its first
     * website (null without a website), these extra [fields] and [oldPassword] as the website's
     * password while a change is pending (null otherwise).
     */
    fun note(notes: String, title: String, host: String?, fields: List<CustomField>, oldPassword: String?): String {
        var withTitle = host != null && title != host
        if (!withTitle && fields.isEmpty() && oldPassword == null) {
            // Nothing to carry. But notes that would read as a block get one of ours behind them.
            if (!read(notes).found) return notes
            withTitle = true
        }
        val lines = StringBuilder()
        var count = 0
        if (withTitle) count += appendLine(lines, TITLE_LABEL, hidden = false, text = title)
        for (field in fields) count += appendLine(lines, label(field.name), field.secret, field.value)
        if (oldPassword != null) count += appendLine(lines, OLD_PASSWORD_LABEL, hidden = true, text = oldPassword)
        return StringBuilder(notes.length + lines.length + 48)
            .append(notes).append("\n\n").append(marker(count)).append(lines)
            .toString()
    }

    /** Appends `LF label[ (hidden)]: text`, later lines of [text] indented; returns the lines written. */
    private fun appendLine(out: StringBuilder, label: String, hidden: Boolean, text: String): Int {
        out.append('\n').append(label)
        if (hidden) out.append(HIDDEN)
        out.append(SEPARATOR)
        var lines = 1
        for (c in TextHygiene.normalizeLineBreaks(text)) {
            out.append(c)
            if (c == '\n') {
                out.append(CONTINUATION)
                lines++
            }
        }
        return lines
    }

    /** The label of a field named [name]: one line, trimmed, escaped, and never a reserved one. */
    private fun label(name: String): String {
        val plain = TextHygiene.normalizeLineBreaks(name).replace('\n', ' ').trim()
        val out = StringBuilder(plain.length + 4)
        if (plain == TITLE_LABEL || plain == OLD_PASSWORD_LABEL) out.append('\\')
        for (c in plain) {
            if (c == '\\' || c == ':' || c == '(') out.append('\\')
            out.append(c)
        }
        return out.toString()
    }

    // ------------------------------------------------------------------ reading

    /**
     * Splits [note] into the user's notes and the block, if the note ends with one that is ours.
     * CRLF and a lone CR count as line feeds, and the notes come back with line feeds.
     */
    fun read(note: String): Block {
        val text = TextHygiene.normalizeLineBreaks(note)
        val totalLines = 1 + text.count { it == '\n' }
        // The last marker line that follows LF LF and whose count is the number of lines after it.
        var markerStart = -1
        var blockLines = 0
        var lineNumber = 0
        var lineStart = 0
        var previousEmpty = false
        while (true) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            if (lineNumber >= 2 && previousEmpty) {
                val count = markerCount(text, lineStart, lineEnd)
                if (count > 0 && count == totalLines - lineNumber - 1) {
                    markerStart = lineStart
                    blockLines = count
                }
            }
            previousEmpty = lineEnd == lineStart
            if (lineEnd == text.length) break
            lineStart = lineEnd + 1
            lineNumber++
        }
        if (markerStart < 0) return Block(text, null, emptyList(), null, found = false)

        // The lines of the block. One that is neither a line nor a continuation: the note is text.
        var title: String? = null
        var oldPassword: String? = null
        val fields = ArrayList<CustomField>()
        fun close(line: Line) {
            val value = line.text.toString()
            when {
                line.reserved == TITLE_LABEL && title == null -> title = value
                line.reserved == OLD_PASSWORD_LABEL && oldPassword == null -> oldPassword = value
                // A second line with a reserved label is kept as a field: nothing is dropped.
                else -> fields.add(CustomField(line.name, value, line.hidden))
            }
        }
        var current: Line? = null
        var at = text.indexOf('\n', markerStart) + 1 // the marker is never the last line: its count is 1 or more
        repeat(blockLines) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            if (text.startsWith(CONTINUATION, at)) {
                val open = current ?: return Block(text, null, emptyList(), null, found = false)
                open.text.append('\n').append(text, at + CONTINUATION.length, end)
            } else {
                current?.let(::close)
                current = parseLine(text, at, end) ?: return Block(text, null, emptyList(), null, found = false)
            }
            at = end + 1
        }
        current?.let(::close)
        return Block(text.substring(0, markerStart - 2), title, fields, oldPassword, found = true)
    }

    /** One `line` of the grammar while its value is still being read. */
    private class Line(
        /** The field's name: the label without its escapes and without the flag. */
        val name: String,
        /** [TITLE_LABEL] or [OLD_PASSWORD_LABEL] when the label is that word written with no backslash, else null. */
        val reserved: String?,
        val hidden: Boolean,
        val text: StringBuilder,
    )

    /**
     * The count of the marker line `text[start, end)`, or 0 when the line is not a marker: the two
     * fixed parts around ASCII digits that spell a number from 1 on without a leading zero.
     */
    private fun markerCount(text: String, start: Int, end: Int): Int {
        val digitsStart = start + MARKER_START.length
        val digitsEnd = end - MARKER_END.length
        val digits = digitsEnd - digitsStart
        if (digits < 1 || digits > MAX_COUNT_DIGITS) return 0
        if (!text.startsWith(MARKER_START, start) || !text.startsWith(MARKER_END, digitsEnd)) return 0
        if (text[digitsStart] == '0') return 0
        var count = 0
        for (i in digitsStart until digitsEnd) {
            val c = text[i]
            if (c !in '0'..'9') return 0
            count = count * 10 + (c - '0')
        }
        return count
    }

    /** Reads `label[ (hidden)]: text` from `text[start, end)`; null when it is not a `line`. */
    private fun parseLine(text: String, start: Int, end: Int): Line? {
        val name = StringBuilder()
        var escaped = false // whether a backslash was used anywhere in the label
        var plainTail = 0 // how many characters at the end of the label came without a backslash
        var i = start
        while (true) {
            if (i >= end) return null // no ": " that ends a label: not a line
            val c = text[i]
            if (c == '\\') {
                if (i + 1 >= end) return null // a backslash with nothing after it
                name.append(text[i + 1])
                escaped = true
                plainTail = 0
                i += 2
            } else if (c == ':' && i + 1 < end && text[i + 1] == ' ') {
                break
            } else {
                name.append(c)
                plainTail++
                i++
            }
        }
        // " (hidden)" at the end of the label, none of it behind a backslash, is the flag.
        val hidden = plainTail >= HIDDEN.length && name.endsWith(HIDDEN)
        if (hidden) name.setLength(name.length - HIDDEN.length)
        val label = name.toString()
        val reserved = if (!escaped && (label == TITLE_LABEL || label == OLD_PASSWORD_LABEL)) label else null
        return Line(label, reserved, hidden, StringBuilder(end - i).append(text, i + SEPARATOR.length, end))
    }
}
