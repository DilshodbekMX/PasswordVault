package pv.formats

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal SpreadsheetML (.xlsx) writer on java.util.zip, no library.
 * Seven parts: [Content_Types].xml, _rels/.rels, xl/workbook.xml, xl/_rels/workbook.xml.rels,
 * xl/styles.xml, xl/worksheets/sheet1.xml, xl/sharedStrings.xml.
 * Every text cell is a shared string (t="s"): never a formula, and readable by the small readers that
 * skip inline strings. Text cells use number format 49 ("@") so an edited cell stays text in Excel.
 */
object Xlsx {
    const val MAX_CELL_CHARS = 32_767 // Excel's limit for text in one cell
    const val MAX_LINE_FEEDS = 253    // Excel's "maximum number of line feeds per cell"

    class Column(val header: String, val width: Double)
    class Stats(val rows: Int, val sharedStrings: Int, val truncatedCells: Int)

    /** Every value is text; null or "" leaves the cell empty. */
    fun write(
        out: OutputStream,
        sheetName: String,
        columns: List<Column>,
        rows: List<List<String?>>,
        pageHeader: String,
        a4: Boolean = true,
        truncationNote: (Int) -> String = { n -> "\n[… $n more characters: Excel shows at most 32,767 in one cell]" },
    ): Stats {
        require(sheetName.isNotEmpty() && sheetName.length <= 31 && sheetName.none { it in "[]:*?/\\" }) { "bad sheet name" }
        val zip = ZipOutputStream(out)
        val w = OutputStreamWriter(zip, Charsets.UTF_8).buffered(64 * 1024)
        fun part(name: String, body: (Writer) -> Unit) {
            zip.putNextEntry(ZipEntry(name).apply { time = 946_728_000_000L }) // fixed 2000-01-01 12:00 UTC: same bytes every time, no time leak
            body(w); w.flush(); zip.closeEntry()
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
            it.write("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">${text(quotedSheet)}!\$A\$1:\$$lastCol\$$lastRow</definedName>")
            it.write("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">${text(quotedSheet)}!\$1:\$1</definedName>")
            it.write("</definedNames></workbook>")
        }
        part("xl/_rels/workbook.xml.rels") { it.write(WORKBOOK_RELS) }
        part("xl/styles.xml") { it.write(STYLES) }

        val strings = LinkedHashMap<String, Int>()
        var refs = 0
        var truncated = 0
        fun sst(s: String): Int { refs++; return strings.getOrPut(s) { strings.size } }

        part("xl/worksheets/sheet1.xml") { s ->
            s.write(XML_DECL)
            s.write("<worksheet xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_R\"><sheetPr><pageSetUpPr fitToPage=\"1\"/></sheetPr>")
            s.write("<dimension ref=\"$range\"/>")
            s.write("<sheetViews><sheetView tabSelected=\"1\" workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
            s.write("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews>")
            s.write("<sheetFormatPr defaultRowHeight=\"15\"/><cols>")
            columns.forEachIndexed { i, c -> s.write("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"${num(c.width)}\" customWidth=\"1\"/>") }
            s.write("</cols><sheetData><row r=\"1\">")
            columns.forEachIndexed { i, c -> s.write("<c r=\"${colName(i)}1\" s=\"1\" t=\"s\"><v>${sst(c.header)}</v></c>") }
            s.write("</row>")
            rows.forEachIndexed { ri, row ->
                val r = ri + 2
                s.write("<row r=\"$r\">")
                for (ci in columns.indices) {
                    var t = limitLineFeeds(normalizeNewlines(row.getOrNull(ci) ?: continue))
                    if (t.isEmpty()) continue
                    if (t.length > MAX_CELL_CHARS) { t = truncate(t, truncationNote); truncated++ }
                    s.write("<c r=\"${colName(ci)}$r\" s=\"2\" t=\"s\"><v>${sst(t)}</v></c>")
                }
                s.write("</row>")
            }
            s.write("</sheetData><autoFilter ref=\"$range\"/><printOptions gridLines=\"1\"/>")
            s.write("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.6\" bottom=\"0.6\" header=\"0.3\" footer=\"0.3\"/>")
            s.write("<pageSetup${if (a4) " paperSize=\"9\"" else ""} orientation=\"landscape\" fitToWidth=\"1\" fitToHeight=\"0\"/>")
            // Header/footer codes: &L &C &R sections, &P page, &N pages, &D date; a literal & is &&.
            s.write("<headerFooter><oddHeader>${text("&L" + pageHeader.replace("&", "&&") + "&R&D")}</oddHeader>")
            s.write("<oddFooter>${text("&C&P / &N")}</oddFooter></headerFooter>")
            s.write("<ignoredErrors><ignoredError sqref=\"$range\" numberStoredAsText=\"1\"/></ignoredErrors></worksheet>")
        }
        part("xl/sharedStrings.xml") { s ->
            s.write(XML_DECL)
            s.write("<sst xmlns=\"$NS_MAIN\" count=\"$refs\" uniqueCount=\"${strings.size}\">")
            for (str in strings.keys) { s.write("<si><t xml:space=\"preserve\">"); s.write(text(str)); s.write("</t></si>") }
            s.write("</sst>")
        }
        zip.finish()
        zip.flush()
        return Stats(rows.size, strings.size, truncated)
    }

    /** Cells show LF as a line break; CR (from Windows text) would show as a box or nothing. */
    fun normalizeNewlines(s: String): String = if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    /** Keeps the first 253 line breaks; later ones become " \u21B5 " so no text is lost. */
    fun limitLineFeeds(s: String): String {
        var count = 0
        val sb = StringBuilder(s.length)
        for (c in s) if (c == '\n' && ++count > MAX_LINE_FEEDS) sb.append(" \u21B5 ") else sb.append(c)
        return sb.toString()
    }

    fun truncate(s: String, note: (Int) -> String): String {
        val probe = note(s.length) // the note's own length barely depends on the number
        var keep = MAX_CELL_CHARS - probe.length - 8
        if (Character.isHighSurrogate(s[keep - 1])) keep-- // never split a surrogate pair
        return s.substring(0, keep) + note(s.length - keep)
    }

    fun colName(i: Int): String {
        var n = i + 1; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    private fun num(d: Double): String {
        val s = String.format(Locale.ROOT, "%.8f", d).trimEnd('0')
        return if (s.endsWith('.')) s.dropLast(1) else s
    }

    /**
     * Escapes text for element content. XML 1.0 cannot carry U+0000-U+0008, U+000B, U+000C,
     * U+000E-U+001F, U+FFFE, U+FFFF or unpaired surrogates even as character references, so they use
     * OOXML's ST_Xstring escape _xHHHH_; a literal "_xHHHH_" in the data gets its underscore escaped
     * as _x005F_, exactly as Excel writes it.
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
                c == '_' && looksLikeEscape(s, i) -> sb.append("_x005F_")
                c == '\t' || c == '\n' -> sb.append(c)
                c == '\r' -> sb.append("_x000D_")
                c < ' ' || c == '\uFFFE' || c == '\uFFFF' -> sb.append(hexEscape(c))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> sb.append(hexEscape(c))
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun attr(s: String) = text(s).replace("\"", "&quot;")
    private fun hexEscape(c: Char) = String.format(Locale.ROOT, "_x%04X_", c.code)
    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    private fun looksLikeEscape(s: String, i: Int) =
        i + 6 < s.length && s[i + 1] == 'x' && isHex(s[i + 2]) && isHex(s[i + 3]) && isHex(s[i + 4]) && isHex(s[i + 5]) && s[i + 6] == '_'

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
        "<xf numFmtId=\"49\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"49\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf></cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>"
}

/** The vault sheet: columns of ux.md §5.20.1, labels localised in the app, dates as text in the phone's zone. */
object VaultXlsx {
    class Labels(
        val sheet: String = "Passwords", val name: String = "Name", val username: String = "Username",
        val password: String = "Password", val website: String = "Website", val otherWebsites: String = "Other websites",
        val notes: String = "Notes", val fields: String = "Extra fields", val favourite: String = "Favourite",
        val added: String = "Added", val lastChanged: String = "Last changed", val passwordChanged: String = "Password changed",
        val yes: String = "Yes", val hidden: String = "hidden", val pageHeader: String = "PasswordVault — private",
    )

    fun columns(l: Labels) = listOf(
        Xlsx.Column(l.name, 24.0), Xlsx.Column(l.username, 26.0), Xlsx.Column(l.password, 22.0), Xlsx.Column(l.website, 30.0),
        Xlsx.Column(l.otherWebsites, 24.0), Xlsx.Column(l.notes, 40.0), Xlsx.Column(l.fields, 30.0), Xlsx.Column(l.favourite, 10.0),
        Xlsx.Column(l.added, 16.0), Xlsx.Column(l.lastChanged, 16.0), Xlsx.Column(l.passwordChanged, 16.0),
    )

    /** Android app links are shown as "app: <package>"; web addresses as written. */
    fun displayUrl(u: String): String = when {
        u.startsWith("android://") && u.contains('@') -> "app: " + u.substringAfter('@').trimEnd('/')
        u.startsWith("androidapp://") -> "app: " + u.removePrefix("androidapp://").trimEnd('/')
        else -> u
    }

    /** "2026-10-06 14:30" in the given zone, without java.time (API 26). */
    fun localTime(ms: Long, zone: TimeZone): String {
        val local = ms + zone.getOffset(ms)
        val d = IsoTime.civilFromDays(Math.floorDiv(local, 86_400_000L))
        val minutes = Math.floorMod(local, 86_400_000L) / 60_000
        return String.format(Locale.ROOT, "%04d-%02d-%02d %02d:%02d", d[0], d[1], d[2], minutes / 60, minutes % 60)
    }

    fun row(e: Entry, zone: TimeZone, l: Labels = Labels()): List<String?> = listOf(
        e.title, e.username, e.password, e.urls.firstOrNull()?.let(::displayUrl),
        e.urls.drop(1).joinToString("\n", transform = ::displayUrl),
        e.notes, e.fields.joinToString("\n") { f -> f.name + (if (f.secret) " (${l.hidden})" else "") + ": " + f.value },
        if (e.favourite) l.yes else null, localTime(e.createdAt, zone), localTime(e.updatedAt, zone),
        e.passwordChangedAt?.let { localTime(it, zone) },
    )

    fun write(out: OutputStream, entries: List<Entry>, zone: TimeZone, l: Labels = Labels()): Xlsx.Stats =
        Xlsx.write(out, l.sheet, columns(l), entries.map { row(it, zone, l) }, l.pageHeader)
}
