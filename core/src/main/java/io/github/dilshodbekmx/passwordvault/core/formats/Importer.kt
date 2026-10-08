package io.github.dilshodbekmx.passwordvault.core.formats

import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.search.WebAddress
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.RandomSource
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.util.Locale

/**
 * Reads a file to import into accounts, without touching the vault (formats.md §8, ux.md §5.21).
 * [ImportPlanner] then decides, against the vault, what the import adds; nothing is written before
 * the user taps "Import", and then `VaultEdits.addImported` adds the plan's entries in one save.
 *
 * **The file** ([FileKind]): a backup, a spreadsheet and a KeePass database are told apart by their
 * first bytes and are not read here ([Result.NotForImport]). At most [MAX_BYTES] and [MAX_ROWS] rows
 * (for JSON, [MAX_ROWS] elements of `items`).
 *
 * **JSON** (Bitwarden's or ours, formats.md §5.2) is read by `BitwardenJson.read`: strict UTF-8, no
 * key twice, at most 32 levels deep, and everything it does not import is listed.
 *
 * **CSV** (formats.md §8.2, §8.3):
 * - The bytes become text by `TextDecoding`, which never switches a file's encoding silently; the
 *   preview says which encoding was chosen and why ([ImportDecoding]), and [Choices.encoding] reads
 *   the file again in the other one ("Text looks wrong? Read as …").
 * - `Csv.read`: Excel's `sep=` line, the delimiter, quotes, and every row whose number of values
 *   differs from the header's ([ImportAccount.wrongLength]): the plan leaves those out unless the user
 *   confirms them (formats-review P4). Values beyond the header are kept in the notes.
 * - The layout: Google's and Chrome's (and ours, which is the same with the block of `FieldsBlock` in
 *   the note), Bitwarden's, KeePassXC's (whatever the case of its header), Firefox's, or any CSV with
 *   named columns ([HeaderAliases]). With no password column, the column-mapping screen opens
 *   ([Result.ChooseColumns], formats-review P6), and [Choices.columns] reads the file with the
 *   meanings the user chose.
 * - A column with no meaning becomes an extra field named by its header, hidden unless the label is
 *   harmless. A folder becomes the visible field "Folder", a one-time-code secret the hidden field
 *   "TOTP". A block of ours at the end of a note gives the name, the extra fields and the pending
 *   change's old password (D-10a, D-10d).
 * - Adjacent rows that are equal except for the website are one account with all their websites (ux.md
 *   §5.21 rule (a)), up to 10 websites; then a new account starts (D-10b, formats-review P5). This is
 *   how our own CSV and Chrome's write one account per website. Bitwarden's rows are items with their
 *   own lists of websites and are never joined. Rules (b) and (c) are the planner's.
 *
 * **The rules for every format** (formats.md §8.4, architecture.md D-10f, D-15):
 * - Control characters are removed from names, usernames, websites, notes and fields, and counted
 *   ([ImportFile.removedControls]). A password is never altered: one that holds a control character is
 *   imported as it is and listed ([ImportChange.Kind.PASSWORD_HAS_TAB] …, formats-review P14).
 * - Websites follow the shared rule `EntryRules.normalizeUrl` (formats-review P10); names, usernames,
 *   websites and field names are trimmed. A name that is blank is made from the website's host, the
 *   username, or "Untitled" and the time.
 * - **Nothing is dropped for a limit** (formats-review P5): a name or username that is too long is cut
 *   and kept whole in the notes; websites beyond 10 or longer than 2,048 characters, extra fields
 *   beyond 20 (the visible ones first, so that a hidden value stays hidden), a field whose value is
 *   longer than 1,000 characters and a value without a name move into the notes; notes longer than
 *   10,000 characters are cut. Every case is listed on the account ([ImportAccount.changes]). Only an
 *   account whose password is longer than 1,000 characters is left out, and listed by name.
 * - A broken character (half of a surrogate pair) cannot be stored and becomes U+FFFD
 *   ([ImportFile.replacedCharacters]); in a password that is listed too.
 * - Ids: a CSV row and a foreign item get new ids; our own JSON's ids are kept here, and
 *   `VaultEdits.addImported` keeps one only when it is free (formats-review P1).
 *
 * Nothing here throws for any bytes, and no exception message or `toString` holds a value of the
 * file (architecture.md §5.6).
 */
object Importer {
    /** The largest file the importer reads (ux.md §5.21): 10 MB. */
    const val MAX_BYTES: Int = 10 * 1024 * 1024

    /** The most rows under a CSV header, or elements of a JSON `items`, one file may hold (formats.md §8.1). */
    const val MAX_ROWS: Int = 10_000

    /** The name of the visible field a folder or group becomes. */
    const val FOLDER_FIELD: String = BitwardenJson.FOLDER_FIELD

    /** The name of the hidden field a one-time-code secret becomes. */
    const val TOTP_FIELD: String = BitwardenJson.TOTP_FIELD

    /** The reason of the [ParseError] of a failure of the importer's own, which never happens with correct code. */
    const val REASON_IMPORTER_FAILURE: String = "importer-failure"

    /** The layouts the importer knows (formats.md §8.3). */
    enum class Format {
        /** `name,url,username,password,note`: Google Password Manager, Chrome, and our own CSV. */
        GOOGLE_CSV,

        /** `login_uri`, `login_username`, `login_password`, … */
        BITWARDEN_CSV,

        /** Group, Title, Username, Password, URL, Notes, in any case. */
        KEEPASSXC_CSV,

        /** url, username, password, httpRealm, formActionOrigin, guid, timeCreated, … */
        FIREFOX_CSV,

        /** Any other CSV with named columns. */
        GENERIC_CSV,

        /** Bitwarden's unencrypted JSON. */
        BITWARDEN_JSON,

        /** Our own JSON: Bitwarden's layout with `passwordVault.format` = `passwordvault-export`. */
        OWN_JSON,
        ;

        val isCsv: Boolean get() = this != BITWARDEN_JSON && this != OWN_JSON
    }

    /** What the user chose that changes how a file is read. */
    class Choices(
        /** "Text looks wrong? Read as …" (formats.md §8.2): the encoding of a CSV file; null lets `TextDecoding` choose. */
        val encoding: TextDecoding.Encoding? = null,
        /**
         * The column-mapping screen's answer: one meaning per column of the CSV header, in order. Null
         * takes them from [HeaderAliases]. A meaning given to two columns goes to the first; the other
         * becomes an extra field.
         */
        val columns: List<ColumnMeaning>? = null,
    )

    /** What [read] made of a file. */
    sealed interface Result {
        /** A backup, a spreadsheet or a KeePass database ([FileKind]): not for the importer. */
        class NotForImport(val kind: FileKind) : Result {
            override fun toString(): String = "NotForImport($kind)"
        }

        /** The file has no bytes, or no row at all. */
        data object Empty : Result

        /** Larger than [limit] bytes. */
        class TooBig(val limit: Int) : Result {
            override fun toString(): String = "TooBig(limit=$limit)"
        }

        /** More rows (or JSON items) than [limit]; [rows] is how many, when the reader knows. */
        class TooManyRows(val rows: Int?, val limit: Int) : Result {
            override fun toString(): String = "TooManyRows(rows=$rows, limit=$limit)"
        }

        /** Not text: a file of another kind. */
        data object NotText : Result

        /** JSON that cannot be read: not strict UTF-8, or not JSON as `StrictJson` reads it. Where and why, never what. */
        class Unreadable(val error: ParseError) : Result {
            override fun toString(): String = "Unreadable($error)"
        }

        /**
         * `"encrypted": true`: "This Bitwarden file is encrypted. In Bitwarden, export again and choose
         * '.json', not '.json (Encrypted)'." [passwordProtected] tells Bitwarden's two kinds apart.
         */
        class Encrypted(val passwordProtected: Boolean) : Result {
            override fun toString(): String = "Encrypted(passwordProtected=$passwordProtected)"
        }

        /** JSON without an `items` array: not an export the importer knows. */
        data object NotAnExport : Result

        /**
         * No column of the CSV is a password column: the column-mapping screen opens, pre-filled with
         * [columns] ("We couldn't find columns for website, username and password. Choose them
         * yourself."). Then [read] again with [Choices.columns].
         */
        class ChooseColumns(val columns: ImportColumns, val decoding: ImportDecoding, val rows: Int) : Result {
            override fun toString(): String = "ChooseColumns($columns, $decoding, rows=$rows)"
        }

        /** The file was read: the preview's material, for [ImportPlanner.plan]. */
        class Read(val file: ImportFile) : Result {
            override fun toString(): String = "Read($file)"
        }
    }

    /**
     * Reads [bytes], the whole file (`DocumentIo.read` with [MAX_BYTES]). [nowMs] stands in for a date
     * the file does not have and names an account without a name ([texts]); [random] gives the ids of
     * new accounts. Never throws, and leaves [bytes] as they are.
     */
    fun read(bytes: ByteArray, nowMs: Long, texts: EditTexts, random: RandomSource, choices: Choices = Choices()): Result {
        if (bytes.size > MAX_BYTES) return Result.TooBig(MAX_BYTES)
        return try {
            when (val kind = FileKind.of(bytes)) {
                FileKind.EMPTY -> Result.Empty
                FileKind.BACKUP, FileKind.SPREADSHEET, FileKind.KEEPASS -> Result.NotForImport(kind)
                FileKind.BINARY -> Result.NotText
                FileKind.JSON -> readJson(bytes, nowMs, texts, random)
                FileKind.CSV -> readCsv(bytes, nowMs, texts, random, choices)
            }
        } catch (e: StackOverflowError) { // formats-review P3: never on the readers used here, but never a crash either
            Result.Unreadable(ParseError(0, StrictJson.Reason.TOO_DEEP.code))
        } catch (e: RuntimeException) { // a fault of this code; its message is never kept (§5.6)
            Result.Unreadable(ParseError(0, REASON_IMPORTER_FAILURE))
        }
    }

    // ------------------------------------------------------------------ JSON

    private fun readJson(bytes: ByteArray, nowMs: Long, texts: EditTexts, random: RandomSource): Result {
        val read = BitwardenJson.read(bytes, nowMs, texts, random::uuid, MAX_ROWS)
        val ok = when (read) {
            is BitwardenJson.Read.Encrypted -> return Result.Encrypted(read.passwordProtected)
            is BitwardenJson.Read.Unreadable -> return Result.Unreadable(read.error)
            is BitwardenJson.Read.NotAnExport -> return Result.NotAnExport
            is BitwardenJson.Read.TooMany -> return Result.TooManyRows(read.items, read.limit)
            is BitwardenJson.Read.Ok -> read
        }
        val cleaner = Cleaner(nowMs, texts)
        val leftOut = ArrayList<ImportLeftOut>()
        // Every element of `items` is an account, or one of the three that are not imported; the
        // reader lists the others (passkeys, linked fields, …) by the position of an account it made.
        val notAccounts = HashSet<Int>()
        val notes = HashMap<Int, MutableList<BitwardenJson.NotImported.What>>()
        for (n in ok.notImported) {
            when (n.what) {
                BitwardenJson.NotImported.What.NOT_AN_ITEM -> {
                    notAccounts.add(n.position)
                    leftOut.add(ImportLeftOut(ImportLeftOut.Kind.NOT_AN_ITEM, null, 0, n.position))
                }
                BitwardenJson.NotImported.What.EMPTY -> {
                    notAccounts.add(n.position)
                    leftOut.add(ImportLeftOut(ImportLeftOut.Kind.EMPTY_ITEM, n.name, 0, n.position))
                }
                BitwardenJson.NotImported.What.IN_TRASH -> {
                    notAccounts.add(n.position)
                    leftOut.add(ImportLeftOut(ImportLeftOut.Kind.IN_TRASH, n.name, 0, n.position))
                }
                else -> if (n.position == 0) {
                    leftOut.add(ImportLeftOut(ImportLeftOut.Kind.UNKNOWN_DATA, null, 0, 0))
                } else {
                    notes.getOrPut(n.position) { ArrayList() }.add(n.what)
                }
            }
        }
        val accounts = ArrayList<ImportAccount>(ok.entries.size)
        var position = 0
        for (entry in ok.entries) {
            position++
            while (position in notAccounts) position++
            val changes = ArrayList<ImportChange>()
            for (what in notes[position].orEmpty()) {
                val kind = when (what) {
                    BitwardenJson.NotImported.What.PASSKEYS -> ImportChange.Kind.PASSKEYS_NOT_IMPORTED
                    BitwardenJson.NotImported.What.LINKED_FIELD -> ImportChange.Kind.LINKED_FIELDS_NOT_IMPORTED
                    BitwardenJson.NotImported.What.PREVIOUS_PASSWORDS -> ImportChange.Kind.PREVIOUS_PASSWORDS_CUT
                    else -> ImportChange.Kind.UNKNOWN_DATA_NOT_KEPT
                }
                changes.add(ImportChange(kind, 1))
            }
            when (val c = cleaner.clean(entry, titleFromFile = true, lines = emptyList(), position = position, given = changes)) {
                is Cleaned.Account -> accounts.add(c.account)
                is Cleaned.LeftOut -> leftOut.add(c.leftOut)
            }
        }
        val format = if (ok.ownFormat) Format.OWN_JSON else Format.BITWARDEN_JSON
        return Result.Read(
            ImportFile(
                format = format,
                decoding = null,
                delimiter = null,
                delimiterFromSepLine = false,
                columns = null,
                accounts = accounts,
                leftOut = leftOut,
                emptyRows = 0,
                unclosedQuoteLine = 0,
                removedControls = cleaner.controls.count,
                replacedCharacters = cleaner.surrogates.count,
                newerVersion = ok.newerVersion,
                vaultExtras = ok.vaultExtras,
            ),
        )
    }

    // ------------------------------------------------------------------ CSV

    private fun readCsv(bytes: ByteArray, nowMs: Long, texts: EditTexts, random: RandomSource, choices: Choices): Result {
        val decoded = choices.encoding?.let { TextDecoding.decode(bytes, it) } ?: TextDecoding.decode(bytes)
        if (decoded.text.indexOf('\u0000') >= 0) return Result.NotText
        val decoding = ImportDecoding(
            decoded.encoding, decoded.why, decoded.byteOrderMarks, decoded.replacedBytes, decoded.replacedLines,
            decoded.replacedLineCount,
        )
        val table = Csv.read(decoded.text, MAX_ROWS)
        val header = table.header ?: return Result.Empty
        if (table.moreRows) return Result.TooManyRows(null, MAX_ROWS)
        val headers = header.values
        val keys = headers.map(HeaderAliases::key)
        val format = formatOf(keys.toHashSet())
        val given = choices.columns?.let { g -> headers.indices.map { g.getOrNull(it) ?: ColumnMeaning.SKIP } }
        val columns = columnsOf(format, headers, keys, given, table.rows)
        if (given == null && ColumnMeaning.PASSWORD !in columns.meanings) {
            return Result.ChooseColumns(columns, decoding, table.rows.size)
        }

        val cleaner = Cleaner(nowMs, texts)
        val reader = RowReader(format, headers, columns.meanings, nowMs)
        val leftOut = ArrayList<ImportLeftOut>()
        val accounts = ArrayList<ImportAccount>()
        var emptyRows = 0
        for (row in table.rows) {
            val values = row.values
            if (values.all { it.isBlank() }) {
                emptyRows++
                continue
            }
            val raw = reader.entry(values, random.uuid())
            val wrong = if (values.size != headers.size) ImportWrongLength(values.size, headers.size) else null
            when (val c = cleaner.clean(raw.entry, raw.titleFromFile, listOf(row.line), 0, emptyList(), wrong)) {
                is Cleaned.Account -> accounts.add(c.account)
                is Cleaned.LeftOut -> leftOut.add(c.leftOut)
            }
        }
        val joined = if (format == Format.BITWARDEN_CSV) accounts else joinAdjacent(accounts)
        return Result.Read(
            ImportFile(
                format = format,
                decoding = decoding,
                delimiter = table.delimiter,
                delimiterFromSepLine = table.delimiterFrom == Csv.DelimiterFrom.SEP_LINE,
                columns = columns,
                accounts = joined,
                leftOut = leftOut,
                emptyRows = emptyRows,
                unclosedQuoteLine = table.unclosedQuoteLine,
                removedControls = cleaner.controls.count,
                replacedCharacters = cleaner.surrogates.count,
                newerVersion = false,
                vaultExtras = emptyMap(),
            ),
        )
    }

    private val BITWARDEN_KEYS = listOf("loginuri", "loginusername", "loginpassword")
    private val KEEPASSXC_KEYS = listOf("group", "title", "username", "password", "url", "notes")
    private val FIREFOX_KEYS = listOf("url", "username", "password", "httprealm", "formactionorigin")
    private val GOOGLE_KEYS = listOf("name", "url", "username", "password")

    /** The layout of a header, from its keys (formats.md §8.3; KeePassXC whatever the case, formats-review P6). */
    private fun formatOf(keys: Set<String>): Format = when {
        BITWARDEN_KEYS.any { it in keys } -> Format.BITWARDEN_CSV
        keys.containsAll(KEEPASSXC_KEYS) -> Format.KEEPASSXC_CSV
        keys.containsAll(FIREFOX_KEYS) -> Format.FIREFOX_CSV
        keys.containsAll(GOOGLE_KEYS) -> Format.GOOGLE_CSV
        else -> Format.GENERIC_CSV
    }

    /** The meaning of every column: the user's, or the aliases' with the strongest word winning. */
    private fun columnsOf(
        format: Format,
        headers: List<String>,
        keys: List<String>,
        given: List<ColumnMeaning>?,
        rows: List<Csv.Row>,
    ): ImportColumns {
        val meanings = ArrayList<ColumnMeaning>(headers.size)
        val ranks = IntArray(headers.size)
        for (i in headers.indices) {
            if (given != null) {
                meanings.add(given[i])
                continue
            }
            val alias = HeaderAliases.alias(headers[i])
            var meaning = alias?.meaning ?: ColumnMeaning.FIELD
            // Bitwarden's "fields" and "type" mean something only in Bitwarden's file.
            if (format != Format.BITWARDEN_CSV && (meaning == ColumnMeaning.BITWARDEN_FIELDS || meaning == ColumnMeaning.BITWARDEN_TYPE)) {
                meaning = ColumnMeaning.FIELD
            }
            meanings.add(meaning)
            ranks[i] = alias?.rank ?: 0
        }
        // One column per meaning: the strongest word, then the first; the others become extra fields.
        val ambiguous = ArrayList<Int>()
        val winner = HashMap<ColumnMeaning, Int>()
        for (i in headers.indices) {
            val m = meanings[i]
            if (m == ColumnMeaning.FIELD || m == ColumnMeaning.SKIP) continue
            val held = winner[m]
            when {
                held == null -> winner[m] = i
                ranks[i] < ranks[held] -> {
                    meanings[held] = ColumnMeaning.FIELD
                    ambiguous.add(held)
                    winner[m] = i
                }
                else -> {
                    meanings[i] = ColumnMeaning.FIELD
                    ambiguous.add(i)
                }
            }
        }
        ambiguous.sort()
        val samples = headers.indices.map { i -> rows.firstNotNullOfOrNull { r -> r.values.getOrNull(i)?.takeIf { it.isNotBlank() } } ?: "" }
        return ImportColumns(format, headers, samples, meanings, ambiguous, fromUser = given != null)
    }

    /** One row as an entry, before the rules of every format are applied. */
    private class RawRow(val entry: Entry, val titleFromFile: Boolean)

    /** Turns the values of one row into an entry, by the meanings of the columns. */
    private class RowReader(
        private val format: Format,
        private val headers: List<String>,
        private val meanings: List<ColumnMeaning>,
        private val nowMs: Long,
    ) {
        private val column = HashMap<ColumnMeaning, Int>()
        private val fieldColumns = ArrayList<Int>()

        init {
            meanings.forEachIndexed { i, m ->
                when (m) {
                    ColumnMeaning.FIELD -> fieldColumns.add(i)
                    ColumnMeaning.SKIP -> Unit
                    else -> column.putIfAbsent(m, i)
                }
            }
        }

        private fun value(values: List<String>, meaning: ColumnMeaning): String =
            column[meaning]?.let { values.getOrNull(it) } ?: ""

        fun entry(values: List<String>, id: String): RawRow {
            val note = value(values, ColumnMeaning.NOTES)
            // Our own CSV: a block at the end of the note, recognised only when it is ours (D-10a).
            val block = FieldsBlock.read(note)
            val fields = ArrayList<CustomField>(block.fields)
            for (i in fieldColumns) {
                val v = values.getOrNull(i) ?: continue
                if (v.isEmpty()) continue
                val label = headers[i].replace("﻿", "").trim()
                fields.add(CustomField(label, v, secret = !HeaderAliases.isHarmless(label)))
            }
            if (ColumnMeaning.BITWARDEN_FIELDS in column) {
                for (line in TextHygiene.normalizeLineBreaks(value(values, ColumnMeaning.BITWARDEN_FIELDS)).split('\n')) {
                    if (line.isBlank()) continue
                    // Bitwarden writes "name: value" and splits at the last ": " when it reads.
                    val at = line.lastIndexOf(": ")
                    if (at < 0) {
                        fields.add(CustomField("", line, secret = true))
                    } else {
                        val name = line.substring(0, at)
                        fields.add(CustomField(name, line.substring(at + 2), secret = !HeaderAliases.isHarmless(name)))
                    }
                }
            }
            value(values, ColumnMeaning.TOTP).takeIf { it.isNotBlank() }?.let { fields.add(CustomField(TOTP_FIELD, it, true)) }
            var folder = value(values, ColumnMeaning.FOLDER).trim()
            // KeePassXC writes the path from its root group, whose name is no folder of the user's.
            if (format == Format.KEEPASSXC_CSV) folder = folder.substringAfter('/', "").trim()
            if (folder.isNotEmpty()) fields.add(CustomField(FOLDER_FIELD, folder, false))

            val website = value(values, ColumnMeaning.WEBSITE)
            val urls = ArrayList<String>()
            if (format == Format.BITWARDEN_CSV) {
                // Bitwarden's login_uri is itself a list of values separated by commas.
                Csv.parse(website).rows.firstOrNull()?.values?.forEach { if (it.isNotBlank()) urls.add(it) }
            } else if (website.isNotBlank()) {
                urls.add(website)
            }
            val second = value(values, ColumnMeaning.SECOND_WEBSITE).trim()
            if (isWeb(second) && hostOf(second) != urls.firstOrNull()?.let(::hostOf)) urls.add(second)

            // Values beyond the header's columns (a row with too many values, kept only if the user confirms it).
            val extra = if (values.size > headers.size) values.subList(headers.size, values.size).filter { it.isNotBlank() } else emptyList()
            val notes = if (extra.isEmpty()) block.notes else listOf(block.notes).filter { it.isNotEmpty() }.plus(extra).joinToString("\n")

            val isNote = value(values, ColumnMeaning.BITWARDEN_TYPE).trim().equals("note", ignoreCase = true)
            val created = time(value(values, ColumnMeaning.CREATED)) ?: nowMs
            val titleText = block.title ?: value(values, ColumnMeaning.TITLE)
            return RawRow(
                Entry(
                    id = id,
                    title = titleText,
                    username = if (isNote) "" else value(values, ColumnMeaning.USERNAME),
                    password = if (isNote) "" else value(values, ColumnMeaning.PASSWORD),
                    notes = notes,
                    urls = urls,
                    fields = fields,
                    favourite = isYes(value(values, ColumnMeaning.FAVOURITE)),
                    changePending = block.oldPassword != null,
                    createdAt = created,
                    updatedAt = time(value(values, ColumnMeaning.MODIFIED)) ?: created,
                    passwordChangedAt = time(value(values, ColumnMeaning.PASSWORD_CHANGED)),
                    pendingOldPassword = block.oldPassword,
                ),
                titleFromFile = titleText.isNotBlank(),
            )
        }
    }

    private fun isWeb(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    private fun hostOf(url: String): String = WebAddress.hostOf(EntryRules.normalizeUrl(url))

    private val YES = setOf("1", "true", "yes", "y", "да", "ha", "ҳа")

    private fun isYes(text: String): Boolean = text.trim().lowercase(Locale.ROOT) in YES

    private val EPOCH_MS = Regex("[0-9]{11,15}")

    /** An ISO 8601 time, or milliseconds since 1970 as Firefox writes them (formats-review P13); null for anything else. */
    internal fun time(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (EPOCH_MS.matches(t)) return t.toLong()
        return IsoTime.parse(t)
    }

    /**
     * Rule (a) of ux.md §5.21: an account whose row follows one equal to it except for the website is
     * one account with both websites, up to [EntryRules.URLS_MAX]; then a new account starts
     * (formats-review P5). A row with the wrong number of values is never joined.
     */
    private fun joinAdjacent(accounts: List<ImportAccount>): List<ImportAccount> {
        val out = ArrayList<ImportAccount>(accounts.size)
        for (a in accounts) {
            val last = out.lastOrNull()
            if (last != null && sameAccount(last, a)) out[out.size - 1] = last.joinedWith(a) else out.add(a)
        }
        return out
    }

    private fun sameAccount(a: ImportAccount, b: ImportAccount): Boolean {
        if (a.wrongLength != null || b.wrongLength != null) return false
        val x = a.entry
        val y = b.entry
        if (x.urls.isEmpty() || y.urls.isEmpty() || y.urls.any { it in x.urls }) return false
        if (x.urls.size + y.urls.size > EntryRules.URLS_MAX) return false
        return x.title == y.title && x.username == y.username && x.password == y.password && x.notes == y.notes &&
            x.fields == y.fields && x.favourite == y.favourite && x.changePending == y.changePending &&
            x.pendingOldPassword == y.pendingOldPassword && x.checkIgnored == y.checkIgnored &&
            x.passwordHistory == y.passwordHistory && x.extras == y.extras
    }

    // ------------------------------------------------------------------ the rules of every format

    private sealed interface Cleaned {
        class Account(val account: ImportAccount) : Cleaned

        class LeftOut(val leftOut: ImportLeftOut) : Cleaned
    }

    /** formats.md §8.4 and architecture.md D-10f for one account, counting over the whole file. */
    private class Cleaner(private val nowMs: Long, private val texts: EditTexts) {
        val controls = TextHygiene.Counter()
        val surrogates = TextHygiene.Counter()

        /** One line of text (a name, a username, a website, a field's name): no control character, no line break. */
        private fun line(text: String): String =
            TextHygiene.replaceUnpairedSurrogates(TextHygiene.removeControls(text, lineBreaks = false, counter = controls), surrogates)

        /** Text with line breaks (notes, a field's value): line feeds, no other control character. */
        private fun lines(text: String): String =
            TextHygiene.replaceUnpairedSurrogates(TextHygiene.removeControls(text, lineBreaks = true, counter = controls), surrogates)

        fun clean(
            raw: Entry,
            titleFromFile: Boolean,
            lines: List<Int>,
            position: Int,
            given: List<ImportChange>,
            wrongLength: ImportWrongLength? = null,
        ): Cleaned {
            // The password is never altered (formats-review P14). Only one that is too long leaves the account out.
            if (EntryRules.length(raw.password) > EntryRules.PASSWORD_MAX) {
                return Cleaned.LeftOut(ImportLeftOut(ImportLeftOut.Kind.PASSWORD_TOO_LONG, nameOf(raw), lines.firstOrNull() ?: 0, position))
            }
            val changes = Changes(given)
            val moved = ArrayList<String>()

            // Websites: the shared rule, blank ones dropped, each once (formats-review P10).
            val urls = ArrayList<String>()
            for (u in raw.urls) {
                val url = EntryRules.normalizeUrl(line(u))
                if (url.isNotEmpty() && url !in urls) urls.add(url)
            }
            var long = 0
            var beyond = 0
            val kept = ArrayList<String>(minOf(urls.size, EntryRules.URLS_MAX))
            for (url in urls) {
                when {
                    EntryRules.length(url) > EntryRules.URL_MAX -> {
                        moved.add(url)
                        long++
                    }
                    kept.size >= EntryRules.URLS_MAX -> {
                        moved.add(url)
                        beyond++
                    }
                    else -> kept.add(url)
                }
            }
            changes.add(ImportChange.Kind.LONG_WEBSITES_MOVED, long)
            changes.add(ImportChange.Kind.WEBSITES_MOVED, beyond)

            var username = line(raw.username).trim()
            if (EntryRules.length(username) > EntryRules.USERNAME_MAX) {
                moved.add(0, username)
                username = EntryRules.cut(username, EntryRules.USERNAME_MAX).trimEnd()
                changes.add(ImportChange.Kind.USERNAME_CUT, 1)
            }

            var title = line(raw.title).trim()
            val fromFile = titleFromFile && title.isNotEmpty()
            if (title.isEmpty()) title = BitwardenJson.titleFor(kept, username, nowMs, texts)
            if (EntryRules.length(title) > EntryRules.TITLE_MAX) {
                moved.add(0, title)
                title = EntryRules.cut(title, EntryRules.TITLE_MAX).trimEnd()
                changes.add(ImportChange.Kind.TITLE_CUT, 1)
            }

            var tab = false
            var lineBreak = false
            var other = false
            for (c in raw.password) {
                when {
                    c == '\t' -> tab = true
                    c == '\n' || c == '\r' -> lineBreak = true
                    TextHygiene.isControl(c) -> other = true
                }
            }
            if (tab) changes.add(ImportChange.Kind.PASSWORD_HAS_TAB, 1)
            if (lineBreak) changes.add(ImportChange.Kind.PASSWORD_HAS_LINE_BREAK, 1)
            if (other) changes.add(ImportChange.Kind.PASSWORD_HAS_CONTROL, 1)
            // Half of a surrogate pair cannot be stored (architecture.md §4.9 step 4): it becomes U+FFFD, and is listed.
            val brokenBefore = surrogates.count
            val password = TextHygiene.replaceUnpairedSurrogates(raw.password, surrogates)
            val pendingOld = raw.pendingOldPassword?.let { TextHygiene.replaceUnpairedSurrogates(it, surrogates) }
            var history = raw.passwordHistory.map {
                val p = TextHygiene.replaceUnpairedSurrogates(it.password, surrogates)
                if (p === it.password) it else HistoryItem(p, it.replacedAt)
            }
            if (surrogates.count > brokenBefore) changes.add(ImportChange.Kind.PASSWORD_CHARACTER_REPLACED, 1)
            if (history.size > EntryRules.HISTORY_MAX) {
                history = history.subList(0, EntryRules.HISTORY_MAX).toList()
                changes.add(ImportChange.Kind.PREVIOUS_PASSWORDS_CUT, 1)
            }

            val fields = fields(raw.fields, moved, changes)

            var notes = lines(raw.notes)
            if (moved.isNotEmpty()) notes = (if (notes.isEmpty()) "" else notes + "\n\n") + moved.joinToString("\n")
            if (EntryRules.length(notes) > EntryRules.NOTES_MAX) {
                notes = EntryRules.cut(notes, EntryRules.NOTES_MAX)
                changes.add(ImportChange.Kind.NOTES_CUT, 1)
            }

            val entry = raw.copy(
                id = TextHygiene.replaceUnpairedSurrogates(raw.id, surrogates),
                title = title,
                username = username,
                password = password,
                notes = notes,
                urls = kept,
                fields = fields,
                deletedAt = null,
                pendingOldPassword = pendingOld,
                passwordHistory = history,
            )
            return Cleaned.Account(ImportAccount(entry, lines, position, wrongLength, fromFile, changes.list()))
        }

        /** The name an account left out is listed by: as it would be cleaned, without counting anything. */
        private fun nameOf(raw: Entry): String {
            val title = TextHygiene.replaceUnpairedSurrogates(TextHygiene.removeControls(raw.title, lineBreaks = false)).trim()
            if (title.isNotEmpty()) return EntryRules.cut(title, EntryRules.TITLE_MAX)
            val urls = raw.urls.map { EntryRules.normalizeUrl(TextHygiene.removeControls(it, lineBreaks = false)) }
            return BitwardenJson.titleFor(urls, TextHygiene.removeControls(raw.username, lineBreaks = false).trim(), nowMs, texts)
        }

        /**
         * The extra fields as the model holds them: names trimmed and on one line, values with line
         * feeds. A value without a name, a value over 1,000 characters and the fields beyond 20 go to
         * [moved]; of more than 20, the hidden ones are kept first.
         */
        private fun fields(raw: List<CustomField>, moved: MutableList<String>, changes: Changes): List<CustomField> {
            val candidates = ArrayList<CustomField>(raw.size)
            var nameless = 0
            var longValues = 0
            var longNames = 0
            for (f in raw) {
                var name = line(f.name).trim()
                val value = lines(f.value)
                if (name.isEmpty()) {
                    if (value.isBlank()) continue
                    moved.add(value)
                    nameless++
                    continue
                }
                if (EntryRules.length(value) > EntryRules.FIELD_VALUE_MAX) {
                    moved.add("$name: $value")
                    longValues++
                    continue
                }
                if (EntryRules.length(name) > EntryRules.FIELD_NAME_MAX) {
                    name = EntryRules.cut(name, EntryRules.FIELD_NAME_MAX).trimEnd()
                    longNames++
                }
                candidates.add(if (name == f.name && value == f.value) f else CustomField(name, value, f.secret))
            }
            changes.add(ImportChange.Kind.NAMELESS_FIELDS_MOVED, nameless)
            changes.add(ImportChange.Kind.LONG_FIELDS_MOVED, longValues)
            changes.add(ImportChange.Kind.FIELD_NAMES_CUT, longNames)
            if (candidates.size <= EntryRules.FIELDS_MAX) return candidates
            // More than 20: the hidden values stay fields (masked), the visible ones go to the notes first.
            val keep = BooleanArray(candidates.size)
            var room = EntryRules.FIELDS_MAX
            for (pass in 0..1) {
                for (i in candidates.indices) {
                    if (room == 0) break
                    if (!keep[i] && candidates[i].secret == (pass == 0)) {
                        keep[i] = true
                        room--
                    }
                }
            }
            val out = ArrayList<CustomField>(EntryRules.FIELDS_MAX)
            var count = 0
            for (i in candidates.indices) {
                val f = candidates[i]
                if (keep[i]) {
                    out.add(f)
                } else {
                    moved.add("${f.name}: ${f.value}")
                    count++
                }
            }
            changes.add(ImportChange.Kind.FIELDS_MOVED, count)
            return out
        }
    }

    /** Collects an account's changes, each kind once with its count. */
    private class Changes(given: List<ImportChange>) {
        private val counts = LinkedHashMap<ImportChange.Kind, Int>()

        init {
            for (c in given) add(c.kind, c.count)
        }

        fun add(kind: ImportChange.Kind, count: Int) {
            if (count > 0) counts[kind] = (counts[kind] ?: 0) + count
        }

        fun list(): List<ImportChange> = counts.map { (k, n) -> ImportChange(k, n) }
    }
}

/** How a CSV file's bytes became text (formats.md §8.2): the preview says which encoding was chosen and why. */
class ImportDecoding(
    val encoding: TextDecoding.Encoding,
    val why: TextDecoding.Why,
    /** Byte-order marks removed from the start. */
    val byteOrderMarks: Int,
    /** Bytes that could not be decoded; each run became one U+FFFD. */
    val replacedBytes: Int,
    /** The lines (from 1) that hold such a U+FFFD, at most `TextDecoding.MAX_LISTED_LINES`: the preview lists them. */
    val replacedLines: List<Int>,
    /** The number of such lines, all of them. */
    val replacedLineCount: Int,
) {
    /** "Text looks wrong? Read as …": the other encoding to offer ([Importer.Choices.encoding]); null for UTF-16. */
    val alternative: TextDecoding.Encoding?
        get() = when (encoding) {
            TextDecoding.Encoding.UTF_8 -> TextDecoding.Encoding.WINDOWS_1251
            TextDecoding.Encoding.WINDOWS_1251 -> TextDecoding.Encoding.UTF_8
            else -> null
        }

    override fun toString(): String =
        "ImportDecoding($encoding, $why, byteOrderMarks=$byteOrderMarks, replacedBytes=$replacedBytes, replacedLines=$replacedLineCount)"
}

/**
 * The columns of a CSV file, for the column-mapping screen of ux.md §5.21: each column's header, its
 * first value that is not blank, and its meaning.
 */
class ImportColumns(
    /** The layout the header was recognised as. */
    val format: Importer.Format,
    /** The headers, as in the file. */
    val headers: List<String>,
    /** The first value of each column that is not blank, or "": what the mapping screen shows beside it. */
    val samples: List<String>,
    /** What each column is read as. */
    val meanings: List<ColumnMeaning>,
    /** The columns whose meaning another column took (now extra fields): an ambiguity the user should see. */
    val ambiguous: List<Int>,
    /** The meanings are the user's ([Importer.Choices.columns]). */
    val fromUser: Boolean,
) {
    override fun toString(): String = "ImportColumns($format, meanings=$meanings, ambiguous=$ambiguous, fromUser=$fromUser, texts hidden)"
}

/** A CSV row whose number of values differs from the header's (formats-review P4). */
class ImportWrongLength(val values: Int, val expected: Int) {
    override fun toString(): String = "ImportWrongLength(values=$values, expected=$expected)"
}

/** One account the file holds, as the importer read and cleaned it. */
class ImportAccount(
    /** The account as it would be added. Its id is new, except for our own JSON (`VaultEdits.addImported` decides). */
    val entry: Entry,
    /** CSV: the line each of its rows starts on (several when adjacent rows were joined); JSON: none. */
    val lines: List<Int>,
    /** JSON: its place in `items`, from 1; CSV: 0. */
    val position: Int,
    /** Not null: its row has the wrong number of values, and it is imported only if the user confirms it. */
    val wrongLength: ImportWrongLength?,
    /** Its name came from the file; false when the importer made one from its website, username or the time. */
    val titleFromFile: Boolean,
    /** What the importer changed to make it fit the model, each kind once. */
    val changes: List<ImportChange>,
) {
    /** How many rows of the file it is (1 for a JSON item). */
    val rows: Int get() = if (lines.isEmpty()) 1 else lines.size

    /** This account and [other], read from later rows, as one: [other]'s websites added (rules (a) to (c) of ux.md §5.21). */
    internal fun joinedWith(other: ImportAccount): ImportAccount {
        val a = entry
        val b = other.entry
        val urls = ArrayList(a.urls)
        for (u in b.urls) if (u !in urls) urls.add(u)
        val changed = LinkedHashMap<ImportChange.Kind, Int>()
        for (c in changes + other.changes) changed[c.kind] = maxOf(changed[c.kind] ?: 0, c.count)
        return ImportAccount(
            a.copy(
                urls = urls,
                createdAt = minOf(a.createdAt, b.createdAt),
                updatedAt = maxOf(a.updatedAt, b.updatedAt),
                passwordChangedAt = listOfNotNull(a.passwordChangedAt, b.passwordChangedAt).maxOrNull(),
            ),
            lines + other.lines,
            position,
            wrongLength,
            titleFromFile,
            changed.map { (k, n) -> ImportChange(k, n) },
        )
    }

    /** This account with another name ("Tidy names"). */
    internal fun renamed(title: String): ImportAccount =
        ImportAccount(entry.copy(title = title), lines, position, wrongLength, titleFromFile, changes)

    override fun toString(): String =
        "ImportAccount(lines=$lines, position=$position, wrongLength=$wrongLength, titleFromFile=$titleFromFile, changes=$changes, $entry)"
}

/** One thing the importer changed in an account, so that the preview can list it ("Gmail: 3 websites moved to notes"). */
class ImportChange(val kind: Kind, val count: Int) {
    enum class Kind {
        /** The password holds a tab: imported as it is (formats-review P14). "Gmail: the password contains a tab." */
        PASSWORD_HAS_TAB,

        /** The password holds a line break: imported as it is. */
        PASSWORD_HAS_LINE_BREAK,

        /** The password holds another control character: imported as it is. */
        PASSWORD_HAS_CONTROL,

        /** Half of a surrogate pair in a password, a previous password or the old password became U+FFFD: it cannot be stored. */
        PASSWORD_CHARACTER_REPLACED,

        /** The name was longer than 200 characters: cut, and kept whole in the notes. */
        TITLE_CUT,

        /** The username was longer than 500 characters: cut, and kept whole in the notes. */
        USERNAME_CUT,

        /** [count] websites beyond the 10 an account holds moved to the notes. */
        WEBSITES_MOVED,

        /** [count] websites longer than 2,048 characters moved to the notes. */
        LONG_WEBSITES_MOVED,

        /** [count] extra fields beyond the 20 an account holds moved to the notes (visible ones first). */
        FIELDS_MOVED,

        /** [count] extra fields whose value is longer than 1,000 characters moved to the notes. */
        LONG_FIELDS_MOVED,

        /** [count] values without a name moved to the notes. */
        NAMELESS_FIELDS_MOVED,

        /** [count] field names longer than 100 characters were cut. */
        FIELD_NAMES_CUT,

        /** The notes, with whatever moved there, were longer than 10,000 characters and were cut. */
        NOTES_CUT,

        /** More than 5 previous passwords: the newest 5 were kept. */
        PREVIOUS_PASSWORDS_CUT,

        /** JSON: the account's passkeys are not imported ("passkeys are not imported"). */
        PASSKEYS_NOT_IMPORTED,

        /** JSON: linked fields, which only point at another value, are not imported. */
        LINKED_FIELDS_NOT_IMPORTED,

        /** Our JSON: a later version's data that nests deeper than a vault file holds was not kept. */
        UNKNOWN_DATA_NOT_KEPT,
    }

    override fun toString(): String = "$kind×$count"
}

/** Something in the file that is not imported, listed by name or place (formats.md §5.2, §8.4). */
class ImportLeftOut(
    val kind: Kind,
    /** The account's name, when it has one. */
    val name: String?,
    /** CSV: the line of its row; 0 otherwise. */
    val line: Int,
    /** JSON: its place in `items`, from 1; 0 otherwise. */
    val position: Int,
) {
    enum class Kind {
        /** "1 row was skipped: password longer than 1,000 characters." The only account a limit leaves out (D-10f). */
        PASSWORD_TOO_LONG,

        /** JSON: an element of `items` that is `null`, a number, a string or an array. */
        NOT_AN_ITEM,

        /** JSON: an item with no name and nothing else in it. */
        EMPTY_ITEM,

        /** JSON: an item in Bitwarden's trash. */
        IN_TRASH,

        /** Our JSON: data of a later version at the top of the file that nests deeper than a vault file holds. */
        UNKNOWN_DATA,
    }

    override fun toString(): String = "ImportLeftOut($kind, line=$line, position=$position, name hidden)"
}

/** A file the importer read (formats.md §8): the accounts and everything the preview says about them. */
class ImportFile(
    val format: Importer.Format,
    /** CSV: how the bytes became text. Null for JSON, which is strict UTF-8. */
    val decoding: ImportDecoding?,
    /** CSV: the delimiter. */
    val delimiter: Char?,
    /** CSV: Excel's `sep=` line named the delimiter. */
    val delimiterFromSepLine: Boolean,
    /** CSV: the columns and their meanings. */
    val columns: ImportColumns?,
    /** The accounts, in the order of the file; rows with the wrong number of values among them, marked. */
    val accounts: List<ImportAccount>,
    /** What is not imported, in the order of the file. */
    val leftOut: List<ImportLeftOut>,
    /** CSV rows with no value at all, skipped. */
    val emptyRows: Int,
    /** CSV: the line on which a quote opened that was never closed, or 0. */
    val unclosedQuoteLine: Int,
    /** Control characters removed from names, usernames, websites, notes and fields: "6 invisible characters were removed". */
    val removedControls: Int,
    /** Halves of surrogate pairs that became U+FFFD (formats-review P7). */
    val replacedCharacters: Int,
    /** Our own JSON, written by a later version of the app. */
    val newerVersion: Boolean,
    /** Our own JSON: the unknown keys of the file's top `passwordVault` object (formats-review P11). */
    val vaultExtras: Map<String, String>,
) {
    /** One account of the preview's sample: never its password (ux.md §5.21). */
    class Sample(val title: String, val username: String) {
        override fun toString(): String = "Sample(texts hidden)"
    }

    /**
     * The preview's sample: the names and usernames of [count] accounts, those with letters outside
     * ASCII (or a U+FFFD) first, so that garbled text shows, then the first others; in file order.
     */
    fun sample(count: Int = 3): List<Sample> {
        fun telling(a: ImportAccount) = (a.entry.title + a.entry.username).any { it.code > 0x7F && (Character.isLetter(it) || it == '�') }
        val picked = accounts.filter(::telling).take(count).toMutableList()
        if (picked.size < count) picked += accounts.filterNot(::telling).take(count - picked.size)
        val order = accounts.withIndex().associate { (i, a) -> a to i }
        return picked.sortedBy { order[it] }.map { Sample(it.entry.title, it.entry.username) }
    }

    override fun toString(): String =
        "ImportFile($format, $decoding, accounts=${accounts.size}, leftOut=${leftOut.size}, emptyRows=$emptyRows, " +
            "unclosedQuoteLine=$unclosedQuoteLine, removedControls=$removedControls, replacedCharacters=$replacedCharacters, " +
            "newerVersion=$newerVersion)"
}
