package pv.formats

/**
 * Export in the layout Google Password Manager and Chrome write and read:
 * header "name,url,username,password,note", one row per website (Chrome's exporter also writes one
 * row per affiliated site), UTF-8 without BOM, CRLF row ends (RFC 4180; Chrome writes CRLF on
 * Windows and LF elsewhere, and every importer checked accepts both).
 * Extra fields go to the end of the note in a block this app reads back (FieldsBlock).
 */
object GoogleCsv {
    val HEADER = listOf("name", "url", "username", "password", "note")

    // Chromium components/password_manager/core/common/password_manager_constants.h and
    // .../import/password_importer.cc, read 2026-10-06. Lengths are UTF-8 bytes (std::string).
    const val CHROME_MAX_NOTE_BYTES = 1000
    const val CHROME_MAX_PASSWORD_BYTES = 1000
    const val CHROME_MAX_USERNAME_BYTES = 1000
    const val CHROME_MAX_URL_CHARS = 2048
    const val CHROME_MAX_ROWS = 3000
    const val CHROME_MAX_FILE_BYTES = 1000 * 1024

    private val HOST_LIKE = Regex("""^[^\s/:]+\.[^\s]+$""")

    /** "gmail.com" -> "https://gmail.com"; anything with a scheme, or not host-like, is left alone. */
    fun normalizeUrl(raw: String): String {
        val u = raw.trim()
        if (u.isEmpty() || u.contains("://")) return u
        return if (HOST_LIKE.matches(u)) "https://$u" else u
    }

    fun rows(entries: List<Entry>): List<List<String>> = entries.flatMap { e ->
        val urls = e.urls.map(::normalizeUrl).filter { it.isNotEmpty() }.ifEmpty { listOf("") }
        val note = FieldsBlock.append(e.notes, e.fields)
        urls.map { u -> listOf(e.title, u, e.username, e.password, note) }
    }

    fun export(entries: List<Entry>): String = Csv.write(HEADER, rows(entries), "\r\n")

    /**
     * Files Google accepts: at most 3,000 rows and 1,000 KB each. Rows of one entry stay in one part,
     * so this app's importer can merge them back. Suggested names: name-part1.csv, name-part2.csv ...
     */
    fun exportParts(entries: List<Entry>): List<String> {
        val parts = ArrayList<String>()
        var current = ArrayList<List<String>>()
        var bytes = 0
        val headerBytes = Csv.write(HEADER, emptyList(), "\r\n").length
        for (e in entries) {
            val r = rows(listOf(e))
            val b = Csv.write(HEADER, r, "\r\n").toByteArray(Charsets.UTF_8).size - headerBytes
            if (current.isNotEmpty() && (current.size + r.size > CHROME_MAX_ROWS || headerBytes + bytes + b > CHROME_MAX_FILE_BYTES)) {
                parts.add(Csv.write(HEADER, current, "\r\n")); current = ArrayList(); bytes = 0
            }
            current.addAll(r); bytes += b
        }
        if (current.isNotEmpty() || parts.isEmpty()) parts.add(Csv.write(HEADER, current, "\r\n"))
        return parts
    }

    /** Counts the app shows before writing, so the user knows what Google will refuse. */
    data class Report(
        val rows: Int, val bytes: Int, val noUrl: Int, val badUrl: Int, val noPassword: Int,
        val longNote: Int, val longUsername: Int, val longPassword: Int, val longUrl: Int,
        val parts: Int, val entriesWithFields: Int,
    )

    fun report(entries: List<Entry>): Report {
        val r = rows(entries)
        fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size
        return Report(
            rows = r.size,
            bytes = utf8(export(entries)),
            noUrl = r.count { it[1].isEmpty() },
            badUrl = r.count { it[1].isNotEmpty() && !(it[1].startsWith("https://") || it[1].startsWith("http://") || it[1].startsWith("android://")) },
            noPassword = r.count { it[3].isEmpty() },
            longNote = r.count { utf8(it[4]) > CHROME_MAX_NOTE_BYTES },
            longUsername = r.count { utf8(it[2]) > CHROME_MAX_USERNAME_BYTES },
            longPassword = r.count { utf8(it[3]) > CHROME_MAX_PASSWORD_BYTES },
            longUrl = r.count { it[1].length > CHROME_MAX_URL_CHARS },
            parts = exportParts(entries).size,
            entriesWithFields = entries.count { it.fields.isNotEmpty() },
        )
    }
}

/**
 * Extra fields written at the end of a CSV note so nothing is lost in Google Password Manager and this
 * app can read them back:
 *
 *     <the user's note>
 *
 *     --- PasswordVault ---
 *     PIN code (hidden): 1234
 *     Address: first line
 *       second line          <- continuation lines of a multi-line value are indented by two spaces
 *
 * The marker and "(hidden)" stay in English in every language: they are read by the importer.
 */
object FieldsBlock {
    const val MARKER = "--- PasswordVault ---"
    private const val HIDDEN = " (hidden)"

    fun append(notes: String, fields: List<CustomField>): String {
        if (fields.isEmpty()) return notes
        val sb = StringBuilder(notes)
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(MARKER)
        for (f in fields) {
            sb.append('\n').append(f.name.replace('\n', ' ')).append(if (f.secret) HIDDEN else "").append(": ")
            sb.append(f.value.replace("\r\n", "\n").replace("\n", "\n  "))
        }
        return sb.toString()
    }

    /** Splits a note into the user's text and the fields block, if the note ends with one. */
    fun split(note: String): Pair<String, List<CustomField>> {
        val n = note.replace("\r\n", "\n")
        val at = when {
            n.startsWith("$MARKER\n") -> 0
            else -> n.lastIndexOf("\n\n$MARKER\n").let { if (it < 0) return note to emptyList() else it + 2 }
        }
        val fields = ArrayList<CustomField>()
        for (line in n.substring(at + MARKER.length + 1).split('\n')) {
            if (line.startsWith("  ") && fields.isNotEmpty()) {
                val last = fields.removeAt(fields.size - 1)
                fields.add(last.copy(value = last.value + "\n" + line.substring(2))); continue
            }
            val p = line.indexOf(": ")
            if (p < 0) return note to emptyList() // not our block after all: keep the note untouched
            val label = line.substring(0, p)
            val hidden = label.endsWith(HIDDEN)
            fields.add(CustomField(label.removeSuffix(HIDDEN), line.substring(p + 2), hidden))
        }
        return n.substring(0, maxOf(0, at - 2)).let { if (at == 0) "" else it } to fields
    }
}
