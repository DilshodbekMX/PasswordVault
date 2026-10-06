package pv.formats

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** RFC 4180 CSV, written the way Chromium writes it and read tolerantly. */
object Csv {

    /**
     * Chromium's rule (components/password_manager/core/browser/export/csv_writer.cc): quote a field
     * that contains CR, LF, '"' or ','; double the quotes inside. We also quote a field that starts or
     * ends with a space or tab, because Chromium's reader trims leading "\r \t" from every row and some
     * readers trim fields; quoting is valid RFC 4180 and changes nothing for readers that do not trim.
     */
    fun appendField(sb: StringBuilder, value: String) {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' } ||
            (value.isNotEmpty() && (value[0] == ' ' || value[0] == '\t' || value.last() == ' ' || value.last() == '\t'))
        if (!needsQuotes) { sb.append(value); return }
        sb.append('"')
        for (c in value) { if (c == '"') sb.append("\"\"") else sb.append(c) }
        sb.append('"')
    }

    /** Rows end in LF, as Chrome writes them everywhere except Windows. No byte order mark. */
    fun write(header: List<String>, rows: Iterable<List<String>>, lineEnd: String = "\n"): String {
        val sb = StringBuilder()
        fun row(r: List<String>) {
            r.forEachIndexed { i, v -> if (i > 0) sb.append(','); appendField(sb, v) }
            sb.append(lineEnd)
        }
        row(header)
        rows.forEach { row(it) }
        return sb.toString()
    }

    class ParseResult(val rows: List<List<String>>, val problems: List<String>)

    /**
     * Tolerant RFC 4180 reader. Line ends: CRLF, LF or a lone CR. A quoted field may hold the
     * delimiter, quotes ("" is one quote) and line breaks. Text after a closing quote is kept, a quote
     * inside an unquoted field is literal (both as Excel does), an unterminated quote at the end of the
     * input is reported and the rest of the input is taken as the field. Blank lines are skipped.
     */
    fun parse(text: String, delimiter: Char = ','): ParseResult {
        val rows = ArrayList<List<String>>()
        val problems = ArrayList<String>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        var line = 1
        var rowStartLine = 1
        val n = text.length
        var fieldStarted = false // true once the current row has any content or delimiter

        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty() && !fieldStarted)) rows.add(row)
            row = ArrayList(); fieldStarted = false
        }

        while (i < n) {
            val c = text[i]
            if (c == '"' && field.isEmpty() && !fieldInProgress(text, i, delimiter)) {
                // Quoted field.
                fieldStarted = true
                val quoteLine = line
                i++
                var closed = false
                while (i < n) {
                    val q = text[i]
                    if (q == '"') {
                        if (i + 1 < n && text[i + 1] == '"') { field.append('"'); i += 2; continue }
                        i++; closed = true; break
                    }
                    if (q == '\n' || (q == '\r' && (i + 1 >= n || text[i + 1] != '\n'))) line++
                    field.append(q); i++
                }
                if (!closed) problems.add("Line $quoteLine: a quoted field is not closed; the rest of the file was read as one field.")
                continue
            }
            when (c) {
                delimiter -> { fieldStarted = true; endField(); i++ }
                '\r' -> { endRow(); i++; if (i < n && text[i] == '\n') i++; line++; rowStartLine = line }
                '\n' -> { endRow(); i++; line++; rowStartLine = line }
                else -> { fieldStarted = true; field.append(c); i++ }
            }
        }
        if (field.isNotEmpty() || row.isNotEmpty() || fieldStarted) endRow()
        return ParseResult(rows, problems)
    }

    /** A quote is an opening quote only at the start of a field. */
    private fun fieldInProgress(text: String, i: Int, delimiter: Char): Boolean =
        !(i == 0 || text[i - 1] == delimiter || text[i - 1] == '\n' || text[i - 1] == '\r')

    /** Picks ',', ';' or tab: the one that splits the header line (outside quotes) into most fields. */
    fun detectDelimiter(text: String): Char {
        val counts = IntArray(3)
        val cands = charArrayOf(',', ';', '\t')
        var inQuotes = false
        for (c in text) {
            if (c == '"') inQuotes = !inQuotes
            if (!inQuotes && (c == '\n' || c == '\r')) break
            if (!inQuotes) for (k in cands.indices) if (c == cands[k]) counts[k]++
        }
        var best = 0
        for (k in 1 until 3) if (counts[k] > counts[best]) best = k
        return cands[best]
    }
}

/** Turns imported bytes into text: BOM-aware, strict UTF-8 first, then Windows-1251. */
object TextDecoding {
    class Decoded(val text: String, val charset: String, val hadBom: Boolean)

    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Decoded(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8", true)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE", true)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return Decoded(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE", true)
        strict(bytes, Charsets.UTF_8)?.let { return Decoded(it, "UTF-8", false) }
        // Russian-language Excel saves "CSV (comma delimited)" in the ANSI code page, 1251.
        val cp1251 = Charset.forName("windows-1251")
        return Decoded(String(bytes, cp1251), "windows-1251", false)
    }

    private fun strict(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) { null }
}
