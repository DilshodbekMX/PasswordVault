package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene

/**
 * RFC 4180 CSV: written the way Chromium writes it, read the way Excel does (formats.md §4.4, §8.2;
 * formats-review P4, P13).
 *
 * **Writing.** A value is quoted when it holds the delimiter, `"`, CR or LF (Chromium's rule) or
 * starts or ends with a space or a tab (Chromium's reader trims the start of every row); quotes
 * inside are doubled. Rows end in CRLF unless told otherwise; no byte-order mark. Every value passes
 * through `TextHygiene.replaceUnpairedSurrogates` first (formats.md §2 rule 8).
 *
 * **Reading.**
 * - A first line `sep=X`, which Excel writes and reads, names the delimiter and is skipped
 *   (formats-review P13); quoted as `"sep=X"` too. Otherwise the delimiter is whichever of `,`, `;`
 *   and tab splits the header row into the most values (ties in that order).
 * - Rows end at CRLF, LF or a lone CR. A quoted value may hold the delimiter, line breaks (kept as
 *   they are) and `""` for one quote. As in Excel, text after a closing quote is kept, and a quote
 *   inside an unquoted value is an ordinary character. A quote that is never closed takes the rest of
 *   the text as its value and is reported with the line it opened on.
 * - Lines with nothing but spaces and tabs are skipped (and counted).
 * - Every row has the line it starts on, counted from 1 over the whole text (the `sep=` line
 *   included; CRLF, LF and a lone CR each end a line, as `TextDecoding` counts them).
 * - **Row length** (formats-review P4): every row whose number of values differs from the header's
 *   is reported, with its line, so that the importer can list it and leave it out unless the user
 *   confirms it ("Line 2 has 7 values; the header has 5.").
 */
object Csv {
    const val CRLF = "\r\n"

    /** The delimiters [detectDelimiter] chooses from, in the order that breaks ties. */
    val DELIMITERS = charArrayOf(',', ';', '\t')

    /** One row as read: the line it starts on (1-based) and its values. */
    class Row(val line: Int, val values: List<String>)

    /** A row of [Table.rows] whose number of values differs from the header's (formats-review P4). */
    class WrongLength(
        /** Its index in [Table.rows]. */
        val index: Int,
        val line: Int,
        val values: Int,
        val expected: Int,
    )

    /** Rows without header semantics, as [parse] reads them. */
    class Parsed(
        val rows: List<Row>,
        /** The line on which a quote opened that was never closed, or 0. */
        val unclosedQuoteLine: Int,
        /** Lines skipped because they held nothing but spaces and tabs. */
        val blankLines: Int,
        /** True when the reading stopped at `maxRows` and more rows follow. */
        val moreRows: Boolean,
    )

    enum class DelimiterFrom { SEP_LINE, DETECTED }

    /** A file as [read] reads it: a header and the rows under it. */
    class Table(
        val delimiter: Char,
        val delimiterFrom: DelimiterFrom,
        /** The first row, or null when the text has none. */
        val header: Row?,
        /** The rows after the header. */
        val rows: List<Row>,
        /** The rows whose number of values differs from the header's, in order. */
        val wrongLength: List<WrongLength>,
        /** The line on which a quote opened that was never closed, or 0. */
        val unclosedQuoteLine: Int,
        val blankLines: Int,
        /** True when the reading stopped after `maxRows` rows under the header and more follow. */
        val moreRows: Boolean,
    )

    // ------------------------------------------------------------------ writing

    /** Whether [value] must be quoted. */
    fun needsQuotes(value: String, delimiter: Char = ','): Boolean {
        if (value.isEmpty()) return false
        val first = value[0]
        val last = value[value.length - 1]
        if (first == ' ' || first == '\t' || last == ' ' || last == '\t') return true
        for (c in value) if (c == delimiter || c == '"' || c == '\r' || c == '\n') return true
        return false
    }

    /** Appends [value] as one CSV value, quoted when it must be. The caller has cleaned it. */
    fun appendField(sb: StringBuilder, value: String, delimiter: Char = ',') {
        if (!needsQuotes(value, delimiter)) {
            sb.append(value)
            return
        }
        sb.append('"')
        for (c in value) {
            if (c == '"') sb.append("\"\"") else sb.append(c)
        }
        sb.append('"')
    }

    /**
     * The header and the rows as CSV text, each row ended by [lineEnd]. Unpaired surrogates become
     * U+FFFD and are counted in [replaced] (formats.md §2 rule 8).
     */
    fun write(
        header: List<String>,
        rows: Iterable<List<String>>,
        lineEnd: String = CRLF,
        delimiter: Char = ',',
        replaced: TextHygiene.Counter? = null,
    ): String {
        require(delimiter != '"' && delimiter != '\r' && delimiter != '\n') { "not a delimiter" }
        val sb = StringBuilder()
        fun row(values: List<String>) {
            values.forEachIndexed { i, v ->
                if (i > 0) sb.append(delimiter)
                appendField(sb, TextHygiene.replaceUnpairedSurrogates(v, replaced), delimiter)
            }
            sb.append(lineEnd)
        }
        row(header)
        rows.forEach { row(it) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ reading

    /**
     * Reads [text] as a CSV file with a header: the `sep=` line, the delimiter, the rows and the
     * row-length report. At most [maxRows] rows under the header are read; `moreRows` says whether
     * more follow. Leading U+FEFF characters are ignored.
     */
    fun read(text: String, maxRows: Int = Int.MAX_VALUE): Table {
        require(maxRows >= 0) { "maxRows must not be negative" }
        var start = 0
        while (start < text.length && text[start] == '\uFEFF') start++
        val sep = sepLine(text, start)
        val delimiter = sep?.delimiter ?: detectDelimiter(text, start)
        val from = if (sep != null) sep.next else start
        val firstLine = if (sep != null) sep.lineAfter else 1
        val rowsWanted = if (maxRows == Int.MAX_VALUE) Int.MAX_VALUE else maxRows + 1
        val parsed = parseFrom(text, from, firstLine, delimiter, rowsWanted)
        val header = parsed.rows.firstOrNull()
        val rows = if (parsed.rows.size > 1) parsed.rows.subList(1, parsed.rows.size) else emptyList()
        val wrong = ArrayList<WrongLength>()
        if (header != null) {
            rows.forEachIndexed { i, r ->
                if (r.values.size != header.values.size) wrong.add(WrongLength(i, r.line, r.values.size, header.values.size))
            }
        }
        return Table(
            delimiter, if (sep != null) DelimiterFrom.SEP_LINE else DelimiterFrom.DETECTED,
            header, rows, wrong, parsed.unclosedQuoteLine, parsed.blankLines, parsed.moreRows,
        )
    }

    /**
     * Reads [text] as rows of values separated by [delimiter], with no header and no `sep=` line:
     * for a list inside one value too, such as Bitwarden's `login_uri` (formats.md §8.3). At most
     * [maxRows] rows are read.
     */
    fun parse(text: String, delimiter: Char = ',', maxRows: Int = Int.MAX_VALUE): Parsed {
        require(delimiter != '"' && delimiter != '\r' && delimiter != '\n') { "not a delimiter" }
        require(maxRows >= 0) { "maxRows must not be negative" }
        return parseFrom(text, 0, 1, delimiter, maxRows)
    }

    /**
     * Whichever of `,`, `;` and tab splits the first row of [text] (outside quotes) into the most
     * values; ties go to the earlier one in [DELIMITERS].
     */
    fun detectDelimiter(text: String): Char = detectDelimiter(text, 0)

    private fun detectDelimiter(text: String, start: Int): Char {
        var best = DELIMITERS[0]
        var bestCount = -1
        for (d in DELIMITERS) {
            val count = parseFrom(text, start, 1, d, 1).rows.firstOrNull()?.values?.size ?: 0
            if (count > bestCount) {
                best = d
                bestCount = count
            }
        }
        return best
    }

    private class SepLine(val delimiter: Char, val next: Int, val lineAfter: Int)

    /** Excel's first line `sep=X` or `"sep=X"`, starting at [start]; null when the text has none. */
    private fun sepLine(text: String, start: Int): SepLine? {
        var i = start
        val quoted = i < text.length && text[i] == '"'
        if (quoted) i++
        if (!text.regionMatches(i, "sep=", 0, 4, ignoreCase = true)) return null
        i += 4
        if (i >= text.length) return null
        val d = text[i]
        if (d == '"' || d == '\r' || d == '\n') return null
        i++
        if (quoted) {
            if (i >= text.length || text[i] != '"') return null
            i++
        }
        return when {
            i >= text.length -> SepLine(d, i, 2)
            text[i] == '\r' -> SepLine(d, if (i + 1 < text.length && text[i + 1] == '\n') i + 2 else i + 1, 2)
            text[i] == '\n' -> SepLine(d, i + 1, 2)
            else -> null
        }
    }

    /** Whether [text] holds anything from [from] on that would make a row (not only blanks). */
    private fun hasRowAfter(text: String, from: Int): Boolean {
        for (i in from until text.length) {
            val c = text[i]
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') return true
        }
        return false
    }

    /** The reader: rows from [from], the first of them on line [firstLine]; at most [maxRows]. */
    private fun parseFrom(text: String, from: Int, firstLine: Int, delimiter: Char, maxRows: Int): Parsed {
        val rows = ArrayList<Row>()
        if (maxRows == 0) return Parsed(rows, 0, 0, hasRowAfter(text, from))
        var unclosedQuoteLine = 0
        var blankLines = 0
        var values = ArrayList<String>()
        val field = StringBuilder()
        var line = firstLine
        var rowLine = firstLine
        var atFieldStart = true
        var structure = false // a delimiter or a quote was seen in this row
        var i = from
        val n = text.length

        fun endField() {
            values.add(if (field.isEmpty()) "" else field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            val blank = !structure && values.size == 1 && values[0].all { it == ' ' || it == '\t' }
            if (blank) blankLines++ else rows.add(Row(rowLine, values))
            values = ArrayList()
            atFieldStart = true
            structure = false
        }

        while (i < n) {
            val c = text[i]
            if (c == '"' && atFieldStart) {
                structure = true
                atFieldStart = false
                val quoteLine = line
                i++
                var closed = false
                while (i < n) {
                    val q = text[i]
                    if (q == '"') {
                        if (i + 1 < n && text[i + 1] == '"') {
                            field.append('"')
                            i += 2
                            continue
                        }
                        i++
                        closed = true
                        break
                    }
                    if (q == '\n' || (q == '\r' && (i + 1 >= n || text[i + 1] != '\n'))) line++
                    field.append(q)
                    i++
                }
                if (!closed && unclosedQuoteLine == 0) unclosedQuoteLine = quoteLine
                continue
            }
            when (c) {
                delimiter -> {
                    structure = true
                    endField()
                    atFieldStart = true
                    i++
                }
                '\r', '\n' -> {
                    endRow()
                    i += if (c == '\r' && i + 1 < n && text[i + 1] == '\n') 2 else 1
                    line++
                    rowLine = line
                    if (rows.size >= maxRows) return Parsed(rows, unclosedQuoteLine, blankLines, hasRowAfter(text, i))
                }
                else -> {
                    field.append(c)
                    atFieldStart = false
                    i++
                }
            }
        }
        if (field.isNotEmpty() || values.isNotEmpty() || structure) endRow()
        return Parsed(rows, unclosedQuoteLine, blankLines, moreRows = false)
    }
}
