package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A minimal SpreadsheetML (`.xlsx`) writer on `java.util.zip`, with no library (formats.md §6).
 *
 * Seven parts: `[Content_Types].xml`, `_rels/.rels`, `xl/workbook.xml`, `xl/_rels/workbook.xml.rels`,
 * `xl/styles.xml`, `xl/worksheets/sheet1.xml`, `xl/sharedStrings.xml`; no `docProps`, so no author
 * and no time. Every cell is a shared string (`t="s"`) in text format 49 (`@`): never a formula, and
 * readable by the small readers that skip inline strings.
 *
 * - **The same bytes on every phone** (formats-review P9): every zip entry carries the one local time
 *   1980-01-01 00:00 and no extra field. `ZipEntry.setTime` turns an instant into a DOS time through
 *   the default time zone, so a fixed *instant* would come out as a different hour in each zone and
 *   tell the phone's offset; a fixed *local* time is the same everywhere.
 * - **Unpaired surrogates become U+FFFD** through `TextHygiene` and are counted in [Stats]
 *   (formats.md §2 rule 8, formats-review P7), instead of `_xD800_`, at which LibreOffice cuts the
 *   cell and which openpyxl shows as it is.
 * - Characters XML 1.0 cannot carry (U+0000 to U+0008, U+000B, U+000C, U+000E to U+001F, U+FFFE,
 *   U+FFFF) are written as OOXML's `_xHHHH_`, and a literal `_xHHHH_` in the data gets its
 *   underscore written as `_x005F_` (ECMA-376 Part 1 §22.4.2.4).
 * - Excel's limits are guards (formats.md §6.2): a cell over 32,767 characters is cut with a note,
 *   line feeds after the 253rd become ` ↵ `, the page header is cut to fit 255 characters.
 */
object Xlsx {
    /** Excel's limit for the text of one cell. */
    const val MAX_CELL_CHARS = 32_767

    /** Excel's "maximum number of line feeds per cell". */
    const val MAX_LINE_FEEDS = 253

    /** Excel's limit for a sheet name. */
    const val MAX_SHEET_NAME_CHARS = 31

    /** Excel's limit for a header or footer, codes included. */
    const val MAX_HEADER_FOOTER_CHARS = 255

    class Column(val header: String, val width: Double)

    class Stats(
        val rows: Int,
        val sharedStrings: Int,
        val truncatedCells: Int,
        /** Unpaired surrogates written as U+FFFD (formats-review P7), in cells, headers and the page header. */
        val replacedSurrogates: Int,
    )

    /** The default note at the end of a cut cell; the app passes its own, in the user's language. */
    val DEFAULT_TRUNCATION_NOTE: (Int) -> String =
        { n -> "\n[\u2026 $n more characters: Excel shows at most 32,767 in one cell]" }

    /**
     * Excel's rules for a sheet name: 1 to 31 characters, none of `[ ] : * ? / \`, not starting or
     * ending with an apostrophe, not "History" (Excel's own), and no unpaired surrogate.
     */
    fun isValidSheetName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_SHEET_NAME_CHARS && name.none { it in "[]:*?/\\" } &&
            !name.startsWith("'") && !name.endsWith("'") && !name.equals("History", ignoreCase = true) &&
            !TextHygiene.hasUnpairedSurrogate(name)

    /**
     * The local time every zip entry carries: 1980-01-01 00:00 in the default time zone, the
     * earliest DOS time, the same DOS fields in every zone (formats.md §6.1). Without `java.time`.
     */
    fun fixedZipTime(): Long = GregorianCalendar(1980, Calendar.JANUARY, 1).timeInMillis

    /**
     * Writes one sheet named [sheetName] with a bold header row of [columns] and [rows] under it;
     * a row's values are taken in column order, and null or "" leaves a cell empty. [pageHeader] is
     * printed at the top left of every page, with the date at the right. Does not close [out].
     */
    fun write(
        out: OutputStream,
        sheetName: String,
        columns: List<Column>,
        rows: List<List<String?>>,
        pageHeader: String,
        a4: Boolean = true,
        truncationNote: (Int) -> String = DEFAULT_TRUNCATION_NOTE,
    ): Stats {
        require(isValidSheetName(sheetName)) { "not a valid sheet name" }
        require(columns.isNotEmpty()) { "no columns" }
        val replaced = TextHygiene.Counter()
        val time = fixedZipTime()
        val zip = ZipOutputStream(out)
        val w = OutputStreamWriter(zip, Charsets.UTF_8).buffered(64 * 1024)
        fun part(name: String, body: (Writer) -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { this.time = time })
            body(w)
            w.flush()
            zip.closeEntry()
        }
        val lastCol = colName(columns.size - 1)
        val lastRow = rows.size + 1
        val range = "A1:$lastCol$lastRow"
        val quotedSheet = "'" + sheetName.replace("'", "''") + "'"

        part("[Content_Types].xml") { it.write(CONTENT_TYPES) }
        part("_rels/.rels") { it.write(ROOT_RELS) }
        part("xl/workbook.xml") {
            it.write(XML_DECL)
            it.write("<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_R\"><bookViews><workbookView activeTab=\"0\"/></bookViews>")
            it.write("<sheets><sheet name=\"${attr(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets><definedNames>")
            it.write("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">")
            it.write("${text(quotedSheet)}!\$A\$1:\$$lastCol\$$lastRow</definedName>")
            it.write("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">${text(quotedSheet)}!\$1:\$1</definedName>")
            it.write("</definedNames></workbook>")
        }
        part("xl/_rels/workbook.xml.rels") { it.write(WORKBOOK_RELS) }
        part("xl/styles.xml") { it.write(STYLES) }

        val strings = LinkedHashMap<String, Int>()
        var refs = 0
        var truncated = 0
        fun sst(s: String): Int {
            refs++
            return strings.getOrPut(s) { strings.size }
        }

        part("xl/worksheets/sheet1.xml") { s ->
            s.write(XML_DECL)
            s.write("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_R\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
            s.write("<dimension ref=\"$range\"/>")
            s.write("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\">")
            s.write("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            s.write("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>")
            s.write("<sheetFormatPr defaultRowHeight=\"15\"/><cols>")
            columns.forEachIndexed { i, c ->
                s.write("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${num(c.width)}\" customWidth=\"1\"/>")
            }
            s.write("</cols><sheetData><row r=\"1\">")
            columns.forEachIndexed { i, c ->
                val header = TextHygiene.replaceUnpairedSurrogates(c.header, replaced)
                s.write("<c r=\"${colName(i)}1\" s=\"1\" t=\"s\"><v>${sst(header)}</v></c>")
            }
            s.write("</row>")
            rows.forEachIndexed { ri, row ->
                val r = ri + 2
                s.write("<row r=\"$r\">")
                for (ci in columns.indices) {
                    val raw = row.getOrNull(ci) ?: continue
                    var t = limitLineFeeds(normalizeNewlines(TextHygiene.replaceUnpairedSurrogates(raw, replaced)))
                    if (t.isEmpty()) continue
                    if (t.length > MAX_CELL_CHARS) {
                        t = truncate(t, truncationNote)
                        truncated++
                    }
                    s.write("<c r=\"${colName(ci)}$r\" s=\"2\" t=\"s\"><v>${sst(t)}</v></c>")
                }
                s.write("</row>")
            }
            s.write("</sheetData><autoFilter ref=\"$range\"/><printOptions gridLines=\"1\"/>")
            s.write("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
            s.write("<pageSetup${if (a4) " paperSize=\"9\"" else ""} orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
            // Header and footer codes: &L, &C and &R start a section, &P is the page, &N the pages,
            // &D the date; a literal & is &&.
            s.write("<headerFooter><oddHeader>${text(headerCodes(pageHeader, replaced))}</oddHeader>")
            s.write("<oddFooter>${text("&C&P / &N")}</oddFooter></headerFooter>")
            s.write("<ignoredErrors><ignoredError sqref=\"$range\" numberStoredAsText=\"1\"/></ignoredErrors></worksheet>")
        }
        part("xl/sharedStrings.xml") { s ->
            s.write(XML_DECL)
            s.write("<sst xmlns=\"$NS_MAIN\" count=\"$refs\" uniqueCount=\"${strings.size}\">")
            for (str in strings.keys) {
                s.write("<si><t xml:space=\"preserve\">")
                s.write(text(str))
                s.write("</t></si>")
            }
            s.write("</sst>")
        }
        zip.finish()
        zip.flush()
        return Stats(rows.size, strings.size, truncated, replaced.count)
    }

    /** `&L<page header>&R&D`, the page header cut so that the whole stays within 255 characters. */
    private fun headerCodes(pageHeader: String, replaced: TextHygiene.Counter): String {
        val clean = TextHygiene.replaceUnpairedSurrogates(normalizeNewlines(pageHeader), replaced)
        val room = MAX_HEADER_FOOTER_CHARS - "&L&R&D".length
        val sb = StringBuilder("&L")
        var used = 0
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            val pair = Character.isHighSurrogate(c) && i + 1 < clean.length && Character.isLowSurrogate(clean[i + 1])
            val width = if (c == '&' || pair) 2 else 1 // a literal & is written &&
            if (used + width > room) break
            if (c == '&') sb.append("&&") else sb.append(c)
            if (pair) sb.append(clean[i + 1])
            used += width
            i += if (pair) 2 else 1
        }
        return sb.append("&R&D").toString()
    }

    /** Cells show LF as a line break; CR (from Windows text) would show as a box or nothing. */
    fun normalizeNewlines(s: String): String = TextHygiene.normalizeLineBreaks(s)

    /** Keeps the first 253 line breaks; later ones become " ↵ " so that no text is lost. */
    fun limitLineFeeds(s: String): String {
        val total = s.count { it == '\n' }
        if (total <= MAX_LINE_FEEDS) return s
        val sb = StringBuilder(s.length + 2 * (total - MAX_LINE_FEEDS))
        var count = 0
        for (c in s) {
            if (c == '\n' && ++count > MAX_LINE_FEEDS) sb.append(" \u21B5 ") else sb.append(c)
        }
        return sb.toString()
    }

    /** Cuts [s] to fit [MAX_CELL_CHARS] with [note] (given the number of characters cut) at its end. */
    fun truncate(s: String, note: (Int) -> String): String {
        if (s.length <= MAX_CELL_CHARS) return s
        val probe = note(s.length) // the note's own length barely depends on the number in it
        var keep = (MAX_CELL_CHARS - probe.length - 8).coerceIn(0, s.length)
        if (keep > 0 && Character.isHighSurrogate(s[keep - 1])) keep-- // never split a surrogate pair
        return s.substring(0, keep) + note(s.length - keep)
    }

    /** The column letters of the 0-based column [i]: A, B, …, Z, AA, … */
    fun colName(i: Int): String {
        require(i >= 0) { "negative column" }
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun num(d: Double): String {
        val s = String.format(Locale.ROOT, "%.8f", d).trimEnd('0')
        return if (s.endsWith('.')) s.dropLast(1) else s
    }

    /** XML 1.0 cannot carry these, even as character references. */
    private fun isXmlForbidden(c: Char) =
        (c < ' ' && c != '\t' && c != '\n' && c != '\r') || c == '\uFFFE' || c == '\uFFFF'

    /** Whether [c] is written as an `_xHHHH_` escape by [text]. */
    private fun isEscaped(c: Char) = isXmlForbidden(c) || c == '\r'

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /**
     * Whether the `_` at [i] would be read back as the start of an `_xHHHH_` escape: it is followed
     * by `x` and four hex digits, and then by `_` or by a character that is itself written as an
     * escape (which starts with `_`).
     */
    private fun startsEscape(s: String, i: Int): Boolean =
        i + 6 < s.length && s[i + 1] == 'x' && isHex(s[i + 2]) && isHex(s[i + 3]) && isHex(s[i + 4]) &&
            isHex(s[i + 5]) && (s[i + 6] == '_' || isEscaped(s[i + 6]))

    /**
     * Escapes [s] for element content: `&`, `<`, `>` as entities; tab and LF as they are; CR and the
     * characters XML 1.0 forbids as `_xHHHH_`; an underscore that would start such an escape as
     * `_x005F_`; an unpaired surrogate as U+FFFD (the cells have been through `TextHygiene` already).
     */
    fun text(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '_' && startsEscape(s, i) -> sb.append("_x005F_")
                c == '\t' || c == '\n' -> sb.append(c)
                isEscaped(c) -> sb.append(hexEscape(c))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> sb.append(TextHygiene.REPLACEMENT_CHARACTER)
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun attr(s: String) = text(s).replace("\"", "&quot;")

    private fun hexEscape(c: Char) = String.format(Locale.ROOT, "_x%04X_", c.code)

    private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
    private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val CT = "application/vnd.openxmlformats-officedocument.spreadsheetml"

    private const val CONTENT_TYPES = XML_DECL +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$CT.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"$CT.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"$CT.styles+xml\"/>" +
        "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"$CT.sharedStrings+xml\"/></Types>"

    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val ROOT_RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"
    private const val WORKBOOK_RELS = XML_DECL +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"$REL/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"$REL/styles\" Target=\"styles.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"$REL/sharedStrings\" Target=\"sharedStrings.xml\"/></Relationships>"

    /** Style 0 default, 1 header (bold, shaded, text), 2 data (text "@", wrapped, top-aligned). */
    private const val STYLES = XML_DECL +
        "<styleSheet xmlns=\"$NS_MAIN\">" +
        "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>" +
        "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E2F3\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>" +
        "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"3\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"49\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" " +
        "applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\">" +
        "<alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
}
