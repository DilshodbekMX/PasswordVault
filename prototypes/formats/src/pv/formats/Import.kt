package pv.formats

import java.util.Locale
import java.util.UUID

/**
 * Reads an import file into entries, without touching the vault. Recognises Google/Chrome CSV (also
 * our own CSV, which is the same layout), Bitwarden CSV, KeePassXC CSV, any CSV whose header names
 * the columns (Chromium's aliases plus English, Russian and Uzbek words), and Bitwarden JSON (also
 * our own JSON, which is Bitwarden JSON plus the "passwordVault" extension).
 */
object Importer {
    enum class Format { GOOGLE_CSV, BITWARDEN_CSV, KEEPASSXC_CSV, GENERIC_CSV, BITWARDEN_JSON, OWN_JSON }

    class Preview(
        val format: Format, val entries: List<Entry>, val charset: String, val delimiter: Char?,
        /** Header text and recognised meaning per column, to prefill the column-mapping screen (null = extra field). */
        val columns: List<Pair<String, String?>>,
        val problems: List<String>, val emptyRowsSkipped: Int, val skipped: List<String>, val controlCharsRemoved: Int,
    )

    class Unsupported(val reason: Reason, detail: String = "") : Exception("$reason $detail")
    enum class Reason { EMPTY, TOO_BIG, TOO_MANY_ROWS, OUR_BACKUP, SPREADSHEET, KEEPASS, BINARY, ENCRYPTED_JSON, BAD_JSON, NO_KNOWN_COLUMNS }

    const val MAX_BYTES = 10 * 1024 * 1024   // ux.md §5.21
    const val MAX_ROWS = 10_000

    private enum class F { TITLE, URL, USERNAME, PASSWORD, NOTES, TOTP, GROUP, FAVOURITE, CREATED, MODIFIED, BW_FIELDS, BW_TYPE, IGNORE }

    /** Header words after normalise(): lower case, BOM, spaces, '_', '-', '.' removed. */
    private val ALIASES: Map<String, F> = buildMap {
        fun f(field: F, vararg names: String) = names.forEach { put(normalise(it), field) }
        // Chromium csv_password_sequence.cc: url|website|origin|hostname|login_uri, username|user|login|account|
        // login_username, password|login_password, note|notes|comment|comments. Chrome ignores "name".
        f(F.URL, "url", "website", "origin", "hostname", "login_uri", "uri", "web site", "site", "address", "link", "web address",
            "сайт", "адрес", "адрес сайта", "ссылка", "веб-сайт", "sayt", "manzil", "havola", "veb-sayt", "veb sayt")
        f(F.USERNAME, "username", "user", "login", "account", "login_username", "user name", "email", "e-mail", "email address",
            "login name", "userid", "user id", "логин", "имя пользователя", "пользователь", "почта", "электронная почта", "эл. почта",
            "foydalanuvchi", "foydalanuvchi nomi", "pochta", "elektron pochta")
        f(F.PASSWORD, "password", "login_password", "pass", "passwd", "pwd", "пароль", "parol")
        f(F.NOTES, "note", "notes", "comment", "comments", "extra", "description", "remarks",
            "заметка", "заметки", "примечание", "примечания", "комментарий", "описание", "izoh", "eslatma", "qayd", "sharh")
        f(F.TITLE, "name", "title", "item name", "entry", "site name", "название", "наименование", "имя записи", "nomi", "nom", "sarlavha")
        f(F.TOTP, "totp", "login_totp", "otp", "otpauth")
        f(F.GROUP, "group", "folder", "grouping", "папка", "группа", "papka", "guruh")
        f(F.FAVOURITE, "favorite", "favourite", "fav", "starred", "избранное", "sevimli")
        f(F.CREATED, "created", "creationdate", "creation time", "created at", "создано", "yaratilgan")
        f(F.MODIFIED, "last modified", "modified", "revisiondate", "updated", "lastmodified", "изменено", "o'zgartirilgan")
        f(F.BW_FIELDS, "fields")
        f(F.BW_TYPE, "type")
        f(F.IGNORE, "icon", "reprompt", "archiveddate", "collections")
    }

    private fun normalise(h: String) =
        h.replace("\uFEFF", "").trim().lowercase(Locale.ROOT).filterNot { it == ' ' || it == '_' || it == '-' || it == '.' }

    private val NOTE_PRIORITY = listOf("note", "notes", "comment", "comments")
    /** ux.md §2: extra fields are hidden unless the label is known to be harmless. */
    private val NOT_SECRET = Regex("(?i)(e-?mail|phone|telefon|телефон|почта|pochta|question|вопрос|savol|folder|group|tags?|categor|папка|guruh|url|site|сайт)")

    fun read(bytes: ByteArray, now: Long, newId: () -> String = { UUID.randomUUID().toString() }): Preview {
        if (bytes.isEmpty()) throw Unsupported(Reason.EMPTY)
        if (bytes.size > MAX_BYTES) throw Unsupported(Reason.TOO_BIG)
        if (startsWith(bytes, "PVAULT01".toByteArray())) throw Unsupported(Reason.OUR_BACKUP)
        if (startsWith(bytes, byteArrayOf(0x50, 0x4B, 3, 4)) || startsWith(bytes, byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())))
            throw Unsupported(Reason.SPREADSHEET)
        if (startsWith(bytes, byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte()))) throw Unsupported(Reason.KEEPASS)
        val decoded = TextDecoding.decode(bytes)
        val text = decoded.text
        if (text.take(4096).contains('\u0000')) throw Unsupported(Reason.BINARY)
        val isJson = text.trimStart().startsWith("{")
        val parsed = if (isJson) {
            val r = try { BitwardenJson.import(text, now, newId) } catch (e: BitwardenJson.EncryptedFile) {
                throw Unsupported(Reason.ENCRYPTED_JSON, e.message ?: "")
            } catch (e: Exception) { throw Unsupported(Reason.BAD_JSON, e.message ?: "") }
            Parsed(if (r.ownFormat) Format.OWN_JSON else Format.BITWARDEN_JSON, r.entries, emptyList(),
                if (r.newerVersion) listOf("Made by a newer version of the app; unknown fields are kept as they are.") else emptyList(), 0, r.skipped)
        } else readCsv(text, now, newId)
        // Model rules (ux.md §2): control characters removed and counted; entries over a limit listed, not imported.
        val removed = IntArray(1)
        val skipped = ArrayList(parsed.skipped)
        val entries = parsed.entries.map { Limits.clean(it, removed) }.filter { e ->
            val p = Limits.problems(e)
            if (p.isNotEmpty()) skipped.add("${e.title}: ${p.joinToString()}")
            p.isEmpty()
        }
        return Preview(parsed.format, entries, decoded.charset, if (isJson) null else Csv.detectDelimiter(text),
            parsed.columns, parsed.problems, parsed.empty, skipped, removed[0])
    }

    private class Parsed(val format: Format, val entries: List<Entry>, val columns: List<Pair<String, String?>>,
                         val problems: List<String>, val empty: Int, val skipped: List<String>)

    private fun readCsv(text: String, now: Long, newId: () -> String): Parsed {
        val delimiter = Csv.detectDelimiter(text)
        val parsed = Csv.parse(text, delimiter)
        val header = parsed.rows.firstOrNull() ?: throw Unsupported(Reason.EMPTY)
        if (parsed.rows.size - 1 > MAX_ROWS) throw Unsupported(Reason.TOO_MANY_ROWS)
        val keys = header.map(::normalise)
        val keySet = keys.toSet()
        val format = when {
            "loginuri" in keySet || "loginusername" in keySet || "loginpassword" in keySet -> Format.BITWARDEN_CSV
            header.map { it.trim().removePrefix("\uFEFF") }.containsAll(listOf("Group", "Title", "Username", "Password", "URL", "Notes")) -> Format.KEEPASSXC_CSV
            keySet.containsAll(listOf("name", "url", "username", "password")) -> Format.GOOGLE_CSV
            else -> Format.GENERIC_CSV
        }
        // Column -> meaning. First column wins for a meaning; Chromium's note priority decides between
        // note columns; a second column with a taken meaning becomes an extra field instead.
        // "fields" and "type" mean something only in Bitwarden's CSV; elsewhere they are extra fields.
        val meaning = arrayOfNulls<F>(header.size)
        val taken = HashSet<F>()
        val noteCol = keys.indices.filter { ALIASES[keys[it]] == F.NOTES }
            .minByOrNull { NOTE_PRIORITY.indexOf(keys[it]).let { p -> if (p < 0) 99 else p } }
        keys.forEachIndexed { i, k ->
            val f = ALIASES[k] ?: return@forEachIndexed
            if (f == F.NOTES && i != noteCol) return@forEachIndexed
            if ((f == F.BW_FIELDS || f == F.BW_TYPE) && format != Format.BITWARDEN_CSV) return@forEachIndexed
            if (taken.add(f) || f == F.IGNORE) meaning[i] = f
        }
        if (taken.none { it in setOf(F.TITLE, F.URL, F.USERNAME, F.PASSWORD, F.NOTES) }) throw Unsupported(Reason.NO_KNOWN_COLUMNS, header.joinToString())
        val asFields = header.indices.filter { meaning[it] == null && header[it].isNotBlank() }

        val out = ArrayList<Entry>()
        var empty = 0
        for (row in parsed.rows.drop(1)) {
            fun v(f: F): String = meaning.indices.firstOrNull { meaning[it] == f }?.let { row.getOrNull(it) } ?: ""
            if (row.all { it.isBlank() }) { empty++; continue }
            val (notesText, blockFields) = FieldsBlock.split(v(F.NOTES))
            val fields = ArrayList(blockFields)
            for (i in asFields) row.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let {
                val label = header[i].trim().removePrefix("\uFEFF")
                fields.add(CustomField(label, it, !NOT_SECRET.containsMatchIn(label)))
            }
            if (format == Format.BITWARDEN_CSV) v(F.BW_FIELDS).split(Regex("\r\n|\r|\n")).forEach { line ->
                val p = line.lastIndexOf(": ")   // Bitwarden writes "name: value" per line and splits at the last ": "
                if (p >= 0) fields.add(CustomField(line.substring(0, p), line.substring(p + 2), !NOT_SECRET.containsMatchIn(line.substring(0, p))))
            }
            v(F.TOTP).takeIf { it.isNotBlank() }?.let { fields.add(CustomField("TOTP", it, true)) }
            val group = v(F.GROUP).trim().let { g ->
                if (format == Format.KEEPASSXC_CSV) g.split('/').drop(1).joinToString("/") else g // drop KeePassXC's root group
            }
            if (group.isNotEmpty()) fields.add(CustomField("Folder", group, false)) // v1 has no folders or tags
            val urls = when (format) {
                Format.BITWARDEN_CSV -> Csv.parse(v(F.URL)).rows.firstOrNull().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                else -> listOf(v(F.URL).trim()).filter { it.isNotEmpty() }
            }
            val isNote = format == Format.BITWARDEN_CSV && v(F.BW_TYPE).equals("note", ignoreCase = true)
            val created = IsoTime.parse(v(F.CREATED)) ?: now
            val notes = Xlsx.normalizeNewlines(notesText)
            val username = if (isNote) "" else v(F.USERNAME)
            val password = if (isNote) "" else v(F.PASSWORD)
            if (listOf(v(F.TITLE), username, password, notes).all { it.isBlank() } && urls.isEmpty() && fields.isEmpty()) { empty++; continue }
            out.add(Entry(
                id = newId(), title = v(F.TITLE).trim().ifEmpty { deriveTitle(urls, username) },
                username = username, password = password, urls = urls, notes = notes, fields = fields,
                favourite = v(F.FAVOURITE).trim().let { it == "1" || it.equals("true", true) || it.equals("yes", true) },
                createdAt = created, updatedAt = IsoTime.parse(v(F.MODIFIED)) ?: created,
            ))
        }
        val merged = if (format == Format.GOOGLE_CSV || format == Format.GENERIC_CSV) mergeAdjacentSameCredential(out) else out
        return Parsed(format, merged, header.indices.map { header[it] to meaning[it]?.name }, parsed.problems, empty, emptyList())
    }

    /** Our CSV and Chrome's write one row per website; adjacent rows that differ only in url are one entry. */
    private fun mergeAdjacentSameCredential(list: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        for (e in list) {
            val last = out.lastOrNull()
            if (last != null && last.title == e.title && last.username == e.username && last.password == e.password &&
                last.notes == e.notes && last.fields == e.fields && e.urls.size == 1 && e.urls[0] !in last.urls
            ) out[out.size - 1] = last.copy(urls = last.urls + e.urls)
            else out.add(e)
        }
        return out
    }

    fun deriveTitle(urls: List<String>, username: String): String =
        urls.firstOrNull()?.let(::host)?.removePrefix("android:")?.ifEmpty { null } ?: username.ifBlank { "(no title)" }

    /** "https://www.Example.com:443/x" -> "example.com"; "android://hash@com.app/" -> "android:com.app". */
    fun host(url: String): String {
        val u = url.trim().lowercase(Locale.ROOT)
        if (u.startsWith("android://")) return "android:" + u.removePrefix("android://").substringAfter('@').trimEnd('/')
        if (u.startsWith("androidapp://")) return "android:" + u.removePrefix("androidapp://").trimEnd('/')
        val rest = if (u.contains("://")) u.substringAfter("://") else u
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').removePrefix("www.")
    }

    private fun startsWith(b: ByteArray, p: ByteArray) = b.size >= p.size && p.indices.all { b[it] == p[it] }
}

/**
 * Decides, against the current vault, what an import adds (ux.md §5.21). Nothing is written until the
 * user confirms; the whole import is one save, and "Undo this import" removes exactly the added ids.
 */
object ImportPlanner {
    class Conflict(val incoming: Entry, val existing: Entry)
    class Plan(val add: List<Entry>, val alreadyInVault: Int, val duplicateInFile: Int, val conflicts: List<Conflict>, val withoutPassword: Int)

    private fun hosts(e: Entry): Set<String> = e.urls.map(Importer::host).filter { it.isNotEmpty() }.toSet()
    private fun sameLogin(a: Entry, b: Entry): Boolean {
        if (a.username.trim() != b.username.trim()) return false
        val ha = hosts(a); val hb = hosts(b)
        return if (ha.isEmpty() && hb.isEmpty()) a.title.trim().equals(b.title.trim(), ignoreCase = true) else ha.any { it in hb }
    }
    /** Same login and same password, and the incoming copy brings nothing new (notes or fields). */
    private fun covers(existing: Entry, incoming: Entry) = sameLogin(existing, incoming) && existing.password == incoming.password &&
        (incoming.notes.isBlank() || existing.notes.contains(incoming.notes.trim())) && existing.fields.containsAll(incoming.fields)

    /** Conflicts (same website and username, other password) are added too, "so you can compare" (ux.md). */
    fun plan(vault: List<Entry>, incoming: List<Entry>): Plan {
        val add = ArrayList<Entry>(); val conflicts = ArrayList<Conflict>()
        var inVault = 0; var inFile = 0
        for (e in incoming) {
            when {
                vault.any { covers(it, e) } -> inVault++
                add.any { covers(it, e) } -> inFile++
                else -> {
                    vault.firstOrNull { sameLogin(it, e) && it.password != e.password }?.let { conflicts.add(Conflict(e, it)) }
                    add.add(e)
                }
            }
        }
        return Plan(add, inVault, inFile, conflicts, add.count { it.password.isEmpty() })
    }

    /** Optional later action "Use the new password": keep the entry, move the old password into its history. */
    fun replacePassword(existing: Entry, incoming: Entry, now: Long): Entry = existing.copy(
        password = incoming.password, passwordChangedAt = now, updatedAt = now,
        history = (listOf(HistoryItem(existing.password, now)) + existing.history).take(5),
    )
}
