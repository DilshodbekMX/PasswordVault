package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.FieldsBlock
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.DraftEntry
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.EntryRules
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import java.util.Random
import java.util.TimeZone

/**
 * What the tests of build-plan package 3.11 share: times, ids, the awkward entries of the export
 * prototype (`prototypes/formats/test/FormatsTest.kt`) in version 1's model, and a reading of our own
 * CSV back into entries.
 *
 * Every helper here writes numbers with ASCII digits whatever the default locale (formats-review
 * P18: the prototype's `"%04d".format` and `String.format` without a locale wrote Persian digits
 * under fa-IR).
 */
internal object WriterFixtures {
    /** 2026-10-06 07:05:09.123 UTC: 12:05 in Tashkent. */
    val T0: Long = IsoTime.parse("2026-10-06T07:05:09.123Z")!!
    const val DAY = 86_400_000L
    const val HOUR = 3_600_000L
    const val MINUTE = 60_000L

    val TASHKENT: TimeZone = TimeZone.getTimeZone("Asia/Tashkent")

    /** `"PasswordVault " + versionName`, as `BuildInfo.generator` gives it (R-14). */
    const val GENERATOR = "PasswordVault 1.0"

    /** `00000000-0000-4000-8000-<n, 12 digits>`: a valid UUID of version 4. */
    fun id(n: Int): String = "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    /** Hands out [id]s from [first] on. */
    class Ids(first: Int = 1) {
        private var next = first

        fun next(): String = id(next++)
    }

    /** The texts `:app` would pass in; in Uzbek, so that a name made here visibly comes from them. */
    object Texts : EditTexts {
        override fun untitled(nowMs: Long): String = "Nomsiz $nowMs"

        override fun fromBackup(title: String): String = "$title (zaxiradan)"

        override fun fromFile(title: String): String = "$title (fayldan)"
    }

    fun entry(
        n: Int,
        title: String,
        username: String = "",
        password: String = "",
        urls: List<String> = emptyList(),
        notes: String = "",
        fields: List<CustomField> = emptyList(),
        favourite: Boolean = false,
        changePending: Boolean = false,
        checkIgnored: Boolean = false,
        createdAt: Long = T0,
        updatedAt: Long = T0,
        passwordChangedAt: Long? = null,
        deletedAt: Long? = null,
        pendingOldPassword: String? = null,
        history: List<HistoryItem> = emptyList(),
        extras: Map<String, String> = emptyMap(),
    ): Entry = Entry(
        id = id(n),
        title = title,
        username = username,
        password = password,
        notes = notes,
        urls = urls,
        fields = fields,
        favourite = favourite,
        changePending = changePending,
        checkIgnored = checkIgnored,
        createdAt = createdAt,
        updatedAt = updatedAt,
        passwordChangedAt = passwordChangedAt,
        deletedAt = deletedAt,
        pendingOldPassword = pendingOldPassword,
        passwordHistory = history,
        extras = extras,
    )

    fun visible(name: String, value: String) = CustomField(name, value, secret = false)

    fun hidden(name: String, value: String) = CustomField(name, value, secret = true)

    /**
     * Whether [e] is an account as the app stores it: what Add and Edit accept (`EntryRules`), with
     * nothing left to trim or normalise, and outside Recently deleted. The round trips promise "no
     * loss" for these; an entry that breaks the input rules is written all the same.
     */
    fun isAsStored(e: Entry): Boolean {
        val form = DraftEntry(e.title, e.username, e.password, e.notes, e.urls, e.fields, e.favourite)
        return e.deletedAt == null && e.title.isNotBlank() && EntryRules.problems(form).isEmpty() &&
            EntryRules.prepare(form) == form && e.passwordHistory.size <= EntryRules.HISTORY_MAX &&
            (e.pendingOldPassword == null || e.changePending)
    }

    // ------------------------------------------------------------------ the prototype's entries

    /** The first id of [specialEntries]; [bulkEntries] start at [BULK_FIRST_ID]. */
    const val SPECIAL_FIRST_ID = 1
    const val BULK_FIRST_ID = 1_001

    /** The 40,000-character note of the prototype: over ux.md's limit and over Excel's cell limit. */
    val LONG_NOTE: String = buildString {
        var i = 0
        while (length < 40_000) {
            append("Qator ").append(i).append(": o‘zbek matni, русский текст, emoji 😀. ")
            i++
        }
    }.substring(0, 40_000)

    /**
     * The awkward cases, one entry each: the prototype's fifteen (some break ux.md's input rules on
     * purpose), then the cases version 1 added: a pending change, a deleted account, the vectors of
     * formats.md §4.4, unknown keys, an unpaired surrogate.
     */
    fun specialEntries(): List<Entry> {
        val ids = SPECIAL_FIRST_ID
        return listOf(
            entry(ids + 0, "O‘zbekiston pochtasi (U+2018)", "g‘alaba@pochta.uz", "o‘zbek‘2026", listOf("https://pochta.uz"),
                "Izoh: g‘alaba, o‘zbek"),
            entry(ids + 1, "Oʻzbekiston banki (U+02BB)", "gʻalaba", "oʻzbekʻ2026", listOf("https://bank.uz"), "Oʻzbek tili: gʻalaba"),
            entry(ids + 2, "Почта России", "иван.петров@mail.ru", "Пароль123", listOf("https://mail.ru/login"), "Заметка: «важно»"),
            entry(ids + 3, "Games 🎮", "player🙂", "p@ss🔑word", listOf("https://games.example"), "👨‍👩‍👧 family plan"),
            entry(ids + 4, "בנק הפועלים / البنك", "مستخدم", "كلمة123", listOf("https://bank.example.il"), "RTL: שלום world مرحبا 123"),
            entry(ids + 5, "Smith, John \"JJ\"", "john,smith", "a,b\"c\"\"d", listOf("https://comma.example/?a=1,2"),
                "He said \"hi\", then left"),
            entry(ids + 6, "Multi-line notes", "user", " spaced password ", listOf("https://lines.example"),
                "line1\nline2\r\nline3\rline4\n"),
            entry(ids + 7, "=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "-2+3", listOf("https://formula.example"),
                "@SUM(A1:A2)", listOf(visible("calc", "=1+1"))),
            entry(ids + 8, "Tab case", "tab\tuser", "tab\there", listOf("https://tab.example"), "\tleading tab"),
            entry(ids + 9, "Control characters", "ctrl", "ctrl\u0001char", listOf("https://ctrl.example"),
                "bell\u0007 unit\u001F nonchar\uFFFE literal _x0041_ and _x005F_"),
            entry(ids + 10, "Long note", "longnote", "pw-long", listOf("https://long.example"), LONG_NOTE),
            entry(ids + 11, "Empty fields"),
            entry(ids + 12, "Full entry", "full@example.com", "Current#1",
                listOf("https://a.example/login", "https://b.example", "android://AbC123==@com.example.app/"),
                "Everything set",
                listOf(hidden("PIN code", "4321"), visible("Security question", "First school?"), visible("Address", "line one\nline two")),
                favourite = true, checkIgnored = true, createdAt = T0 - DAY * 400, updatedAt = T0, passwordChangedAt = T0 - DAY * 30,
                history = listOf(HistoryItem("Old#2", T0 - DAY * 30), HistoryItem("Older#3", T0 - DAY * 200))),
            entry(ids + 13, "No scheme", "me", "pw", listOf("gmail.com")),
            entry(ids + 14, "Many lines", "lines", "pw300", listOf("https://many.example"),
                (1..300).joinToString("\n") { "recovery code $it" }),
            // Version 1: a change that is pending keeps the website's old password (D-10d; vector 6).
            entry(ids + 15, "Gmail", "aziz@gmail.com", "N3w!pass", listOf("https://mail.google.com"), "",
                listOf(hidden("PIN", "1234")), changePending = true, pendingOldPassword = "Old#2",
                passwordChangedAt = T0 - HOUR, history = listOf(HistoryItem("Older#1", T0 - DAY * 90))),
            // A deleted account: in Recently deleted, and in no plain export (D-10e).
            entry(ids + 16, "Deleted account", "gone@example.com", "Deleted#Pass", listOf("https://deleted.example"),
                "must not be exported", listOf(hidden("Deleted PIN", "0000")), deletedAt = T0 - DAY),
            // Vectors 1, 2, 5 and 7 of formats.md §4.4.
            entry(ids + 17, "Bank", "ali", "B@nk1", listOf("https://bank.uz"), "Branch: Chilonzor",
                listOf(visible("Q: mother's name", "Aziza"))),
            entry(ids + 18, "Bank", "vali", "B@nk2", listOf("https://bank.uz"), "",
                listOf(visible("Code (hidden)", "77"), hidden("PIN", "1234"))),
            entry(ids + 19, "Telegram", "+998901112233", "T3legram!", listOf("https://web.telegram.org"), "",
                listOf(visible("Title", "Mr"), visible("Old password", "x"))),
            entry(ids + 20, "Server", "root", "S3rver!", listOf("https://srv.example"), "",
                listOf(visible("C:\\keys", "line 1\nline 2"))),
            // Vector 8: the user's own note reads as a block; it must stay a note.
            entry(ids + 21, "mail.ru", "ivan", "M@il1", listOf("https://mail.ru"),
                "Old notes:\n\n" + FieldsBlock.marker(1) + "\nPIN: 9999"),
            // Keys a later version wrote into this entry, kept as they are.
            entry(ids + 22, "From the future", "later", "L8ter!", listOf("https://later.example"), "keeps unknown keys",
                extras = linkedMapOf("futureField" to "{\"a\":[1,2]}", "futureNumber" to "1.50")),
            // Formats-review P7: an unpaired surrogate in every text; every writer makes it U+FFFD.
            entry(ids + 23, "T\uD800x", "u\uDC00", "p\uD83D", listOf("https://x.example/a\uD800"), "n\uDE00\uD83Dz",
                listOf(hidden("F\uD800", "v\uDFFF"))),
        )
    }

    // ------------------------------------------------------------------ the vectors of formats.md §4.4

    /** One vector of formats.md §4.4: the stored entry, and the note column the CSV writes for it. */
    class Vector(val number: Int, val entry: Entry, val note: String) {
        override fun toString(): String = "vector $number"
    }

    /**
     * The vectors of formats.md §4.4 as stored entries, each with the exact note column of the
     * table: 1 to 4 are the cases of formats-review P8 (4 twice: a pasted block without a count, and
     * one whose count does not reach the end), 5 to 7 the reserved labels and an escaped backslash,
     * 8 notes that would read as a block. Vector 3's field was typed with leading spaces, so its entry
     * is what `EntryRules.prepare` stores. Each has its own username and password, so that no two
     * rows of a CSV are taken for one account's websites.
     */
    fun vectors(): List<Vector> {
        val pasted = "Old notes:\n\n--- PasswordVault ---\nPIN: 9999"
        val short = "Old notes:\n\n" + FieldsBlock.marker(1) + "\nPIN: 9999\nmore text"
        val looksLikeABlock = "Old notes:\n\n" + FieldsBlock.marker(1) + "\nPIN: 9999"
        val typed = EntryRules.prepare(DraftEntry(title = "Bank", username = "sami", password = "B@nk3", urls = listOf("https://bank.uz"),
            fields = listOf(visible("A", "1"), visible("  B", "x"))))
        return listOf(
            Vector(1, entry(101, "Bank", "ali", "B@nk1", listOf("https://bank.uz"), "Branch: Chilonzor",
                listOf(visible("Q: mother's name", "Aziza"))),
                "Branch: Chilonzor\n\n--- PasswordVault (2 lines) ---\nTitle: Bank\nQ\\: mother's name: Aziza"),
            Vector(2, entry(102, "Bank", "vali", "B@nk2", listOf("https://bank.uz"), "",
                listOf(visible("Code (hidden)", "77"), hidden("PIN", "1234"))),
                "\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nCode \\(hidden): 77\nPIN (hidden): 1234"),
            Vector(3, entry(103, typed.title, typed.username, typed.password, typed.urls, typed.notes, typed.fields),
                "\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nA: 1\nB: x"),
            Vector(4, entry(104, "mail.ru", "ivan", "M@il4", listOf("https://mail.ru"), pasted), pasted),
            Vector(4, entry(105, "mail.ru", "olim", "M@il5", listOf("https://mail.ru"), short), short),
            Vector(5, entry(106, "Telegram", "+998901112233", "T3legram!", listOf("https://web.telegram.org"), "",
                listOf(visible("Title", "Mr"), visible("Old password", "x"))),
                "\n\n--- PasswordVault (3 lines) ---\nTitle: Telegram\n\\Title: Mr\n\\Old password: x"),
            Vector(6, entry(107, "Gmail", "aziz@gmail.com", "N3w!pass", listOf("https://mail.google.com"), "",
                listOf(hidden("PIN", "1234")), changePending = true, pendingOldPassword = "Old#2"),
                "\n\n--- PasswordVault (3 lines) ---\nTitle: Gmail\nPIN (hidden): 1234\nOld password (hidden): Old#2"),
            Vector(7, entry(108, "Server", "root", "S3rver!", listOf("https://srv.example"), "",
                listOf(visible("C:\\keys", "line 1\nline 2"))),
                "\n\n--- PasswordVault (3 lines) ---\nTitle: Server\nC\\:\\\\keys: line 1\n  line 2"),
            Vector(8, entry(109, "mail.ru", "aziza", "M@il8", listOf("https://mail.ru"), looksLikeABlock),
                looksLikeABlock + "\n\n--- PasswordVault (1 lines) ---\nTitle: mail.ru"),
        )
    }

    /** [n] generated entries, the same every time: the prototype's, with ASCII digits in every locale. */
    fun bulkEntries(n: Int): List<Entry> {
        val random = Random(42)
        val words = listOf("o‘zbek", "gʻalaba", "почта", "банк", "Toshkent", "Samarqand", "магазин", "kino", "🎵", "shop")
        val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!@#$%^&*()-_=+[]{};:,.<>/?\"'"
        return List(n) { i ->
            val word = words[i % words.size]
            entry(
                BULK_FIRST_ID + i,
                title = "$word ${i + 1}",
                username = "user$i@example.com",
                password = String(CharArray(16) { chars[random.nextInt(chars.length)] }),
                urls = listOf("https://site$i.example.com/"),
                notes = if (i % 7 == 0) "Note $i: $word\nsecond line, with comma" else "",
                fields = if (i % 11 == 0) listOf(hidden("PIN", (i % 10_000).toString().padStart(4, '0'))) else emptyList(),
                favourite = i % 13 == 0,
                createdAt = T0 - i * MINUTE,
                updatedAt = T0,
                passwordChangedAt = if (i % 3 == 0) T0 - i * HOUR else null,
                history = if (i % 17 == 0) listOf(HistoryItem("old$i", T0 - DAY)) else emptyList(),
            )
        }
    }

    // ------------------------------------------------------------------ our CSV, read back

    /** What our CSV keeps of an account (formats.md §4.5). */
    data class CsvAccount(
        val title: String,
        val username: String,
        val password: String,
        val urls: List<String>,
        val notes: String,
        val fields: List<CustomField>,
        val changePending: Boolean,
        val pendingOldPassword: String?,
    )

    /** What the CSV is meant to keep of [e]; the websites as the CSV writes them. */
    fun csvAccount(e: Entry) = CsvAccount(
        title = e.title,
        username = e.username,
        password = e.password,
        urls = e.urls.map(EntryRules::normalizeUrl).filter { it.isNotEmpty() },
        notes = e.notes,
        fields = e.fields,
        changePending = e.changePending && e.pendingOldPassword != null,
        pendingOldPassword = if (e.changePending) e.pendingOldPassword else null,
    )

    /**
     * Our own CSV read back with our own readers: `Csv.read`, then `FieldsBlock.read` on every note,
     * then adjacent rows that are equal except for the url joined into one account. The joining is
     * the importer's rule (formats.md §8.3, build plan 3.15), written out here in its simplest form
     * so that the round trip can be checked without the importer.
     */
    fun readCsv(parts: List<ByteArray>): List<CsvAccount> {
        val out = ArrayList<CsvAccount>()
        for (part in parts) {
            val table = Csv.read(String(part, Charsets.UTF_8))
            check(table.header!!.values == listOf("name", "url", "username", "password", "note"))
            check(table.wrongLength.isEmpty() && table.unclosedQuoteLine == 0)
            var previousRow: List<String>? = null
            for (row in table.rows) {
                val v = row.values
                val block = FieldsBlock.read(v[4])
                val last = out.lastOrNull()
                val previous = previousRow
                val sameAccount = last != null && previous != null && previous[0] == v[0] && previous[2] == v[2] &&
                    previous[3] == v[3] && previous[4] == v[4] && v[1].isNotEmpty() && v[1] !in last.urls
                if (sameAccount) {
                    out[out.size - 1] = last.copy(urls = last.urls + v[1])
                } else {
                    out.add(CsvAccount(
                        title = block.title ?: v[0],
                        username = v[2],
                        password = v[3],
                        urls = listOf(v[1]).filter { it.isNotEmpty() },
                        notes = block.notes,
                        fields = block.fields,
                        changePending = block.oldPassword != null,
                        pendingOldPassword = block.oldPassword,
                    ))
                }
                previousRow = v
            }
        }
        return out
    }
}
