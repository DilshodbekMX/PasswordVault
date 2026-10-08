package io.github.dilshodbekmx.passwordvault.core.formats.importer

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.ColumnMeaning
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.ImportChange
import io.github.dilshodbekmx.passwordvault.core.formats.ImportLeftOut
import io.github.dilshodbekmx.passwordvault.core.formats.Importer
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Encoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Why
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.NOW
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.csv
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.file
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.read
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.resource
import io.github.dilshodbekmx.passwordvault.core.formats.importer.ImportFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures
import io.github.dilshodbekmx.passwordvault.core.model.EditTexts
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.model.VaultEdits
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import io.github.dilshodbekmx.passwordvault.testing.FakeRandom
import java.nio.charset.Charset
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `Importer.read`: the layouts of formats.md §8.3 and the rules of §8.4 and D-10f. */
class ImporterTest {
    /** The accounts the round trips promise to keep whole: what the app stores, written by 3.11's fixtures. */
    private fun storedAccounts(): List<Entry> =
        WriterFixtures.vectors().map { it.entry } + WriterFixtures.specialEntries().filter(WriterFixtures::isAsStored) +
            WriterFixtures.bulkEntries(300)

    @Test
    fun ourOwnCsvComesBackThroughTheImporter() {
        val entries = storedAccounts()
        val export = GoogleCsv.export(entries)
        val accounts = export.parts.flatMap { file(it).accounts }
        assertTrue(accounts.all { it.changes.isEmpty() && it.wrongLength == null })
        // What our CSV keeps (formats.md §4.5): one account per entry, every website back in it.
        assertEquals(
            entries.map(WriterFixtures::csvAccount),
            accounts.map { a ->
                val e = a.entry
                WriterFixtures.CsvAccount(e.title, e.username, e.password, e.urls, e.notes, e.fields, e.changePending, e.pendingOldPassword)
            },
        )
        val full = accounts.single { it.entry.title == "Full entry" }
        assertEquals(3, full.lines.size) // three rows, one per website, joined by rule (a)
    }

    @Test
    fun ourOwnJsonComesBackWhole() {
        val entries = storedAccounts()
        val file = file(BitwardenJson.export(entries, NOW, GENERATOR).bytes)
        assertEquals(Importer.Format.OWN_JSON, file.format)
        assertEquals(entries, file.accounts.map { it.entry }) // every key, the ids and the unknown keys included
        assertTrue(file.accounts.all { it.changes.isEmpty() })
        assertEquals(0, file.removedControls)
    }

    @Test
    fun bitwardensCsv() {
        val file = file(
            csv(
                "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp",
                "Social,1,login,Facebook,my note,\"PIN: 1234\nSecurity question: First pet?\",0,\"https://facebook.com,https://m.facebook.com\",aziz,Fb#2026,otpauth://totp/x",
                ",,note,Shopping list,\"milk, bread\",,0,,,,",
                "Work,,login,Jira,,Plain line without a separator,0,jira.example.com,ali,J1ra!,",
            ),
        )
        assertEquals(Importer.Format.BITWARDEN_CSV, file.format)
        val (facebook, list, jira) = file.accounts.map { it.entry }
        assertEquals(listOf("https://facebook.com", "https://m.facebook.com"), facebook.urls)
        assertEquals(listOf("aziz", "Fb#2026", "my note"), listOf(facebook.username, facebook.password, facebook.notes))
        assertEquals(
            listOf(hidden("PIN", "1234"), visible("Security question", "First pet?"), hidden("TOTP", "otpauth://totp/x"),
                visible("Folder", "Social")),
            facebook.fields,
        )
        assertTrue(facebook.favourite)
        assertEquals(listOf("Shopping list", "", "", "milk, bread"), listOf(list.title, list.username, list.password, list.notes))
        assertTrue(list.urls.isEmpty() && list.fields.isEmpty() && !list.favourite)
        assertEquals(listOf("https://jira.example.com"), jira.urls)
        assertEquals("Plain line without a separator", jira.notes) // a value without a name is kept in the notes
        assertEquals(listOf(visible("Folder", "Work")), jira.fields)
        assertEquals("[NAMELESS_FIELDS_MOVED×1]", file.accounts[2].changes.toString())
    }

    /**
     * Bitwarden writes a secure note's row with empty login columns (formats.md §8.3: `type=note` has no
     * username or password). A row of type `note` that has them all the same, as an edited file may,
     * keeps them: a password is never dropped on import but for its length (formats.md §8.4, D-10f).
     */
    @Test
    fun aBitwardenNoteRowThatHasAPasswordKeepsIt() {
        val file = file(
            csv(
                "folder,favorite,type,name,notes,fields,reprompt,login_uri,login_username,login_password,login_totp",
                ",,note,Shopping list,\"milk, bread\",,0,,,,",
                ",,note,Wi-Fi at home,router in the hall,,0,,admin,R0uter#Pass,",
            ),
        )
        val (list, wifi) = file.accounts.map { it.entry }
        assertEquals(listOf("", ""), listOf(list.username, list.password))
        assertEquals(listOf("admin", "R0uter#Pass", "router in the hall"), listOf(wifi.username, wifi.password, wifi.notes))
        assertTrue(file.accounts.all { it.changes.isEmpty() })
    }

    @Test
    fun keePassXcsCsv() {
        val file = file(
            csv(
                "\"Group\",\"Title\",\"Username\",\"Password\",\"URL\",\"Notes\",\"TOTP\",\"Icon\",\"Last Modified\",\"Created\"",
                "\"Root\",\"Gmail\",\"aziz@gmail.com\",\"G00gle!\",\"https://mail.google.com\",\"personal\",\"\",\"0\",\"2024-03-09T10:00:00Z\",\"2023-01-01T08:00:00Z\"",
                "\"Root/Banks/Uzbek\",\"Kapitalbank\",\"ali\",\"K@p1t\",\"https://kapitalbank.uz\",\"\",\"otpauth://totp/k\",\"0\",\"2024-05-01T00:00:00Z\",\"2024-01-01T00:00:00Z\"",
            ),
        )
        assertEquals(Importer.Format.KEEPASSXC_CSV, file.format)
        val (gmail, bank) = file.accounts.map { it.entry }
        assertTrue(gmail.fields.isEmpty()) // the root group is no folder of the user's
        assertEquals(IsoTime.parse("2023-01-01T08:00:00Z"), gmail.createdAt)
        assertEquals(IsoTime.parse("2024-03-09T10:00:00Z"), gmail.updatedAt)
        assertEquals("personal", gmail.notes)
        assertEquals(listOf(hidden("TOTP", "otpauth://totp/k"), visible("Folder", "Banks/Uzbek")), bank.fields)
    }

    @Test
    fun windows1251WithSemicolonsAndRussianHeaders() {
        val bytes = "Название;Сайт;Логин;Пароль;Примечание\r\nПочта;mail.ru;ivan;Пароль1;личное\r\n".toByteArray(Charset.forName("windows-1251"))
        val file = file(bytes)
        assertEquals(Importer.Format.GENERIC_CSV, file.format)
        assertEquals(Encoding.WINDOWS_1251, file.decoding!!.encoding)
        assertEquals(Why.NO_UTF8_SEQUENCES, file.decoding!!.why)
        assertEquals(Encoding.UTF_8, file.decoding!!.alternative)
        assertEquals(';', file.delimiter)
        val e = file.accounts.single().entry
        assertEquals(listOf("Почта", "ivan", "Пароль1", "личное"), listOf(e.title, e.username, e.password, e.notes))
        assertEquals(listOf("https://mail.ru"), e.urls)
    }

    @Test
    fun valuesOverALimitMoveOrAreCutAndAreListed() {
        val title = "N".repeat(250)
        val username = "u".repeat(600)
        val notes = "x".repeat(9_990)
        val urls = (1..13).joinToString(",") { "{\"uri\":\"https://s$it.example\"}" }
        val longValue = "v".repeat(1_200)
        val item = """{"type":1,"name":"$title","notes":"$notes","fields":[{"name":"","value":"orphan","type":0},""" +
            """{"name":"${"F".repeat(120)}","value":"v","type":0},{"name":"Long value","value":"$longValue","type":1}],""" +
            """"login":{"username":"$username","password":"pw","uris":[$urls]}}"""
        val file = file("""{"encrypted":false,"folders":[],"items":[$item]}""")
        val a = file.accounts.single()
        val e = a.entry
        assertEquals("N".repeat(200), e.title)
        assertEquals("u".repeat(500), e.username)
        assertEquals((1..10).map { "https://s$it.example" }, e.urls)
        assertEquals(listOf(visible("F".repeat(100), "v")), e.fields)
        val moved = listOf(title, username, "https://s11.example", "https://s12.example", "https://s13.example", "orphan", "Long value: $longValue")
        assertEquals((notes + "\n\n" + moved.joinToString("\n")).substring(0, 10_000), e.notes)
        assertEquals(
            "[WEBSITES_MOVED×3, USERNAME_CUT×1, TITLE_CUT×1, NAMELESS_FIELDS_MOVED×1, LONG_FIELDS_MOVED×1, FIELD_NAMES_CUT×1, NOTES_CUT×1]",
            a.changes.toString(),
        )
        assertEquals("pw", e.password)

        // When the notes have room, everything moved is there in full.
        val roomy = file("""{"encrypted":false,"folders":[],"items":[${item.replace(notes, "short")}]}""").accounts.single()
        assertEquals("short\n\n" + moved.joinToString("\n"), roomy.entry.notes)
        assertFalse(roomy.changes.any { it.kind == ImportChange.Kind.NOTES_CUT })

        // More than 5 previous passwords in our own file: the newest 5.
        val history = (1..7).map { HistoryItem("old$it", NOW - it * 1_000L) }
        val own = file(BitwardenJson.export(listOf(entry(1, "Many", "u", "pw", listOf("https://m.example"), history = history)), NOW, GENERATOR).bytes)
        assertEquals(history.take(5), own.accounts.single().entry.passwordHistory)
        assertEquals("[PREVIOUS_PASSWORDS_CUT×1]", own.accounts.single().changes.toString())
    }

    @Test
    fun halvesOfSurrogatePairsBecomeTheReplacementCharacterAndAPasswordWithOneIsListed() {
        val u = "\\u" // a JSON escape: the file holds a lone surrogate written as \uD800
        val json = """{"encrypted":false,"items":[{"type":1,"name":"T${u}d800x","login":{"username":"u","password":"a${u}dc00b"},""" +
            """"notes":"n${u}d83dz"}]}"""
        val file = file(json)
        val e = file.accounts.single().entry
        assertEquals("T\uFFFDx", e.title)
        assertEquals("a\uFFFDb", e.password)
        assertEquals("n\uFFFDz", e.notes)
        assertEquals(3, file.replacedCharacters)
        assertEquals("[PASSWORD_CHARACTER_REPLACED×1]", file.accounts.single().changes.toString())
    }

    @Test
    fun emptyRowsAnUnclosedQuoteAndTheLinesOfEachAccount() {
        val file = file(
            csv(
                "name,url,username,password,note",
                ",,,,",
                "A,https://a.example,u,p,first",
                "",
                "B,https://b.example,u,p,\"never closed",
                "C,https://c.example,u,p,",
            ),
        )
        assertEquals(1, file.emptyRows)
        assertEquals(5, file.unclosedQuoteLine)
        assertEquals(listOf(listOf(3), listOf(5)), file.accounts.map { it.lines })
        assertEquals("never closed\nC,https://c.example,u,p,\n", file.accounts[1].entry.notes)
    }

    @Test
    fun aNulFarIntoACsvIsAControlCharacterNotTheEndOfTheImport() {
        val rows = (1..300).joinToString("") { "s$it,https://s$it.example,u$it,p$it,note $it\n" }
        val text = "name,url,username,password,note\n" + rows + "Last,https://last.example,u,p\u0000w,n\u0000ote\n"
        assertTrue(text.indexOf('\u0000') > 8_192)
        val file = file(text)
        val last = file.accounts.last()
        assertEquals("p\u0000w", last.entry.password) // a password is never altered: kept and listed
        assertEquals("[PASSWORD_HAS_CONTROL×1]", last.changes.toString())
        assertEquals("note", last.entry.notes)
        assertEquals(1, file.removedControls)
        // Near the start it is not text at all, as FileKind says.
        assertEquals(Importer.Result.NotText, read("name,url\u0000,username,password\n"))
    }

    /**
     * A file from outside may have any header. The time and the memory of a read grow with the size of
     * the file, not with the header times the rows: a header of a million columns over 10,000 short rows
     * (1 MB) took more than half a minute, and one column whose name has 50,000 characters over 10,000
     * rows (110 KB) did not fit in 512 MB.
     */
    @Test
    fun aHugeHeaderOverManyShortRowsIsReadInTimeAndMemoryThatGrowWithTheFile() {
        val wide = StringBuilder("name,url,username,password").append(",".repeat(1_000_000)).append('\n')
        repeat(10_000) { wide.append("a\n") }
        var start = System.nanoTime()
        val wideFile = file(wide.toString())
        val wideMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, wideFile.accounts.size)
        assertEquals(1_000_004, wideFile.columns!!.headers.size)
        assertEquals(listOf("a", "", ""), wideFile.columns!!.samples.take(3))
        assertEquals("a", wideFile.accounts.last().entry.title)
        assertTrue("$wideMs ms", wideMs < 5_000)

        val long = StringBuilder("name,password, ").append("x".repeat(50_000)).append('\n')
        repeat(10_000) { long.append("a,b,c\n") }
        start = System.nanoTime()
        val longFile = file(long.toString())
        val longMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, longFile.accounts.size)
        // The column is an extra field as before: hidden, its name cut to 100 characters and listed.
        assertEquals(listOf(hidden("x".repeat(100), "c")), longFile.accounts.last().entry.fields)
        assertEquals("[FIELD_NAMES_CUT×1]", longFile.accounts.last().changes.toString())
        assertTrue("$longMs ms", longMs < 5_000)

        // A long header is cleaned as before, every row counted: its control characters, its cut, and
        // the whole name in front of a value that is too long for a field and goes to the notes.
        val odd = "\u0001".repeat(300) + " Name " + "y".repeat(150) + "\u0002"
        val oddFile = file(csv("name,password,\"$odd\"", "a,b,c", "d,e,${"v".repeat(1_001)}"))
        assertEquals(listOf(visible("Name " + "y".repeat(95), "c")), oddFile.accounts[0].entry.fields)
        assertEquals("[FIELD_NAMES_CUT×1]", oddFile.accounts[0].changes.toString())
        assertEquals("Name " + "y".repeat(150) + ": " + "v".repeat(1_001), oddFile.accounts[1].entry.notes)
        assertEquals("[LONG_FIELDS_MOVED×1]", oddFile.accounts[1].changes.toString())
        assertEquals(2 * 301, oddFile.removedControls)
        println("a huge header over 10,000 short rows: a million columns read in $wideMs ms, a name of 50,000 characters in $longMs ms")
    }

    /**
     * One account may list any number of websites (a Bitwarden item's `uris`, the list in `login_uri`).
     * Each was compared with every one before it: 80,000 of them, a file of 1 MB, took 13 s.
     */
    @Test
    fun anAccountWithAHundredThousandWebsitesIsReadInTimeThatGrowsWithTheFile() {
        val n = 100_000
        val hosts = (1..n).map { "h$it.example" }
        val json = """{"encrypted":false,"items":[{"type":1,"name":"Many","login":{"username":"u","password":"p","uris":[""" +
            hosts.joinToString(",") { "{\"uri\":\"$it\"}" } + "]}}]}"
        val csv = "name,login_uri,login_username,login_password\nMany,\"" + hosts.joinToString(",") + ",h7.example\",u,p\n"
        for ((text, repeats) in listOf(json to 0, csv to 1)) {
            val start = System.nanoTime()
            val a = file(text).accounts.single()
            val ms = (System.nanoTime() - start) / 1_000_000
            assertEquals((1..10).map { "https://h$it.example" }, a.entry.urls)
            // Each website once: the repeat in the CSV's list is neither kept nor moved.
            assertEquals("[WEBSITES_MOVED×${n - 10}]", a.changes.filter { it.kind == ImportChange.Kind.WEBSITES_MOVED }.toString())
            assertTrue(a.entry.notes.startsWith("https://h11.example\nhttps://h12.example\n"))
            assertTrue("$ms ms with $repeats repeats", ms < 5_000)
        }
    }

    /**
     * The shared website rule (`EntryRules.normalizeUrl`) asks whether a text looks like a host with a
     * pattern whose time grows with the square of the text when a space follows many dots. An imported
     * value may be megabytes long: one website of 200,000 such characters took 40 s to read, and 4,000
     * accounts each with one of 2,000 characters, which the rule is asked about twice when the account
     * has no name, half a minute.
     */
    @Test
    fun aWebsiteOfDotsWithASpaceIsReadInTimeThatGrowsWithTheFile() {
        val long = "a.".repeat(100_000) + " b"
        val short = "a.".repeat(1_000) + " b"
        val files = listOf(
            // In the website column of an account with a name: too long for a website, so it goes to the notes.
            csv("name,url,username,password", "Site,\"$long\",u,p"),
            // Firefox's layout, which compares the host of the website with the host of the form.
            csv("url,username,password,httpRealm,formActionOrigin", "\"$long\",u,p,,https://form.example"),
            // An account that is left out (its password is too long) is listed by a name made from its website.
            csv("name,url,username,password", ",\"$long\",,${"p".repeat(1_001)}"),
            // Accounts without a name, each named after a website that is short enough to be kept.
            csv("name,url,username,password", *Array(4_000) { ",\"$short\",u$it,p" }),
        )
        val times = ArrayList<Long>()
        val read = files.map { text ->
            val start = System.nanoTime()
            file(text).also { times.add((System.nanoTime() - start) / 1_000_000) }
        }
        // What is read is what the rule gives: such a text is no host, so it is kept as written.
        val a = read[0].accounts.single()
        assertTrue(a.entry.urls.isEmpty())
        assertEquals(long.substring(0, 10_000), a.entry.notes)
        assertEquals("[LONG_WEBSITES_MOVED×1, NOTES_CUT×1]", a.changes.toString())
        assertEquals("[LONG_WEBSITES_MOVED×1, NOTES_CUT×1]", read[1].accounts.single().changes.toString())
        assertEquals(ImportLeftOut.Kind.PASSWORD_TOO_LONG, read[2].leftOut.single().kind)
        assertEquals(long.substring(0, 200), read[2].leftOut.single().name)
        assertEquals(4_000, read[3].accounts.size)
        assertEquals(listOf(short), read[3].accounts.last().entry.urls)
        assertEquals(short.substring(0, 200), read[3].accounts.last().entry.title) // the "host" of a text that is no address
        assertTrue("$times ms", times.all { it < 5_000 })
    }

    @Test
    fun tenThousandRowsAreReadAndOneMoreIsRefused() {
        val header = "name,url,username,password\n"
        fun rows(n: Int) = (1..n).joinToString("") { "s$it,https://s$it.example,u$it,p$it\n" }
        assertEquals(10_000, file(header + rows(10_000)).accounts.size)
        val tooMany = read(header + rows(10_001))
        assertTrue(tooMany.toString(), tooMany is Importer.Result.TooManyRows)
        assertEquals(null, (tooMany as Importer.Result.TooManyRows).rows)
        assertEquals(Importer.MAX_ROWS, tooMany.limit)
    }

    @Test
    fun theUsersColumnsAreRead() {
        val text = csv("A,B,C", "x,y,z")
        val choose = read(text) as Importer.Result.ChooseColumns
        assertEquals(listOf(ColumnMeaning.FIELD, ColumnMeaning.FIELD, ColumnMeaning.FIELD), choose.columns.meanings)
        assertEquals(1, choose.rows)
        assertEquals(Encoding.UTF_8, choose.decoding.encoding)
        // A meaning given twice goes to the first column; the other is an extra field.
        val twice = file(text, Importer.Choices(columns = listOf(ColumnMeaning.PASSWORD, ColumnMeaning.PASSWORD, ColumnMeaning.TITLE)))
        assertEquals(listOf(1), twice.columns!!.ambiguous)
        val e = twice.accounts.single().entry
        assertEquals(listOf("x", "z"), listOf(e.password, e.title))
        assertEquals(listOf(hidden("B", "y")), e.fields)
        // Fewer meanings than columns: the rest are skipped; the user chose no password, and the file is read.
        val short = file(text, Importer.Choices(columns = listOf(ColumnMeaning.TITLE)))
        assertEquals(listOf(ColumnMeaning.TITLE, ColumnMeaning.SKIP, ColumnMeaning.SKIP), short.columns!!.meanings)
        assertEquals(listOf("x", ""), short.accounts.single().entry.let { listOf(it.title, it.password) })
    }

    @Test
    fun theSampleShowsNamesWithLettersOutsideAsciiFirstAndNeverAPassword() {
        val file = file(csv("name,url,username,password", "Alpha,a.example,al,p1", "Beta,b.example,be,p2", "Gamma,c.example,ga,p3",
            "Почта,pochta.uz,почтачи,p4"))
        assertEquals(listOf("Alpha" to "al", "Beta" to "be", "Почта" to "почтачи"), file.sample().map { it.title to it.username })
        assertEquals("Sample(texts hidden)", file.sample().first().toString())
    }

    @Test
    fun nothingThatAFileHoldsIsInAToString() {
        val file = file(resource("uzbek-cyrillic-headers.csv"))
        val texts = listOf(file.toString(), file.accounts.toString(), file.columns.toString(), file.leftOut.toString())
        for (secret in listOf("Парол-2026!", "aziz", "Почта", "pochta", "шахсий", "Номи")) {
            assertTrue(texts.none { it.contains(secret) })
        }
        // An account that is left out is listed by its name, which its toString hides too; so does the plan's.
        val long = file(csv("name,url,username,password", "Zaxira,https://z.example,zafar,${"p".repeat(1_001)}", "accounts.google.com,https://accounts.google.com,gulnora,pw"))
        assertEquals("Zaxira", long.leftOut.single().name)
        val plan = ImportFixtures.plan(long)
        assertEquals("accounts.google.com", plan.tidyExamples.single().before)
        for (text in listOf(long.leftOut.toString(), long.toString(), plan.toString(), plan.accounts.toString(), plan.tidyExamples.toString(), plan.entries.toString())) {
            for (secret in listOf("Zaxira", "zafar", "gulnora", "google", "Google")) assertFalse(text, text.contains(secret))
        }
    }

    /**
     * The id of an item of our own JSON is text of the file like any other: `Entry.toString` shows an
     * id, so one that is no id is not kept (`VaultEdits.addImported` would give a new one anyway).
     */
    @Test
    fun anIdThatIsNoIdIsNotKeptAndShowsInNoToString() {
        val own = file(
            """{"encrypted":false,"passwordVault":{"format":"passwordvault-export","version":1},"folders":[],"items":[""" +
                """{"id":"S3cret-text-in-the-id","type":1,"name":"A","login":{"username":"u","password":"p"}},""" +
                """{"id":"${ImportFixtures.id(7)}","type":1,"name":"B","login":{"username":"u","password":"q"}},""" +
                """{"id":"00000000-0000-4000-8000-00000000000A","type":1,"name":"C","login":{"username":"u","password":"r"}}]}""",
        )
        assertEquals(Importer.Format.OWN_JSON, own.format)
        assertFalse(own.accounts.toString(), own.accounts.toString().contains("S3cret"))
        assertFalse(ImportFixtures.plan(own).entries.toString().contains("S3cret"))
        assertTrue(own.accounts.all { Uuid.isValid(it.entry.id) })
        assertEquals(ImportFixtures.id(7), own.accounts[1].entry.id) // an id that is one is kept as written
        assertEquals(3, own.accounts.map { it.entry.id }.toSet().size)
    }

    /**
     * Build-plan §12, decision 9: our own JSON's data of a newer version at the top of the file is listed
     * once ("Some data from a newer version of the app will not be imported.") and is not kept; an
     * item's unknown keys stay with its account, as before.
     */
    @Test
    fun ourOwnJsonsUnknownTopLevelDataIsListedOnceAndNotKept() {
        val newer = file(
            """{"encrypted":false,"passwordVault":{"format":"passwordvault-export","version":2,"exportedAt":"2027-01-01T00:00:00.000Z",""" +
                """"folders":[{"id":1,"name":"Ish"}],"theme":"dark"},"folders":[],"items":[""" +
                """{"type":1,"id":"${ImportFixtures.id(5)}","name":"A","login":{"username":"u","password":"p"},"passwordVault":{"future":true}}]}""",
        )
        assertTrue(newer.newerVersion)
        assertEquals(listOf(ImportLeftOut.Kind.UNKNOWN_DATA to 0), newer.leftOut.map { it.kind to it.position })
        val plan = ImportFixtures.plan(newer)
        val after = VaultEdits.addImported(plan.entries, FakeRandom(3), ImportFixtures.Texts::fromFile)(Vault(), NOW).vault
        assertTrue("not kept", after.extras.isEmpty())
        assertEquals("an item's own unknown keys are kept", mapOf("future" to "true"), after.entries.single().extras)

        // Our own file of this version, and a foreign one, list nothing.
        val own = file(BitwardenJson.export(listOf(ImportFixtures.entry(1, "A", "u", "p")), NOW, GENERATOR).bytes)
        assertTrue(own.leftOut.isEmpty())
        assertTrue(file("""{"encrypted":false,"folders":[],"items":[{"type":1,"name":"B","login":{"password":"q"}}]}""").leftOut.isEmpty())
    }

    /** Build-plan §12, decision 10: the preview starts "Tidy names" off for this app's own files, which the file says. */
    @Test
    fun aFileOfThisAppSaysSo() {
        val withFields = ImportFixtures.entry(1, "accounts.google.com", "aziz", "pw", urls = listOf("https://accounts.google.com"), fields = listOf(hidden("PIN", "1234")))
        val plain = ImportFixtures.entry(2, "gmail.com", "ali", "pw2", urls = listOf("https://gmail.com"))
        assertTrue("our JSON", file(BitwardenJson.export(listOf(plain), NOW, GENERATOR).bytes).fromThisApp)
        assertTrue("our CSV with its fields block", file(GoogleCsv.export(listOf(plain, withFields)).parts.single()).fromThisApp)
        // Our CSV without a block is Chrome's layout and nothing more: it cannot be told apart.
        assertFalse(file(GoogleCsv.export(listOf(plain)).parts.single()).fromThisApp)
        assertFalse(file(csv("name,url,username,password,note", "gmail.com,https://gmail.com,ali,pw,")).fromThisApp)
        assertFalse(file("""{"encrypted":false,"folders":[],"items":[{"type":1,"name":"B","login":{"password":"q"}}]}""").fromThisApp)
    }

    /** The review of 3.15: a JSON password that is not text is listed on its account, and a secure note keeps its login. */
    @Test
    fun aJsonPasswordThatIsNotTextIsListedOnItsAccount() {
        val file = file(
            """{"encrypted":false,"items":[""" +
                """{"type":1,"name":"Gmail","login":{"username":"aziz","password":["x"]}},""" +
                """{"type":2,"name":"Router","secureNote":{"type":0},"login":{"username":"admin","password":"R0uter#Pass"}}]}""",
        )
        val (gmail, router) = file.accounts
        assertEquals("[PASSWORD_NOT_IMPORTED×1]", gmail.changes.toString())
        assertEquals(listOf("aziz", ""), listOf(gmail.entry.username, gmail.entry.password))
        assertEquals(listOf("admin", "R0uter#Pass"), listOf(router.entry.username, router.entry.password))
        assertTrue(router.changes.isEmpty())
    }

    /**
     * A 10 MB file of millions of tiny values ran out of memory on a phone whose app memory is small
     * (the review of 3.15, on API 37 at 192 MB): the read says so, and the app goes on. Here the read
     * is made to run out of memory by the text that names an account that has no name.
     */
    @Test
    fun aReadThatRunsOutOfMemoryIsAFileTooLargeToReadOnThisPhone() {
        val noMemory = object : EditTexts {
            override fun untitled(nowMs: Long): String = throw OutOfMemoryError("Failed to allocate")

            override fun fromBackup(title: String): String = title

            override fun fromFile(title: String): String = title
        }
        val files = listOf(
            csv("name,url,username,password", ",,,S3cret!"),
            """{"encrypted":false,"items":[{"type":1,"name":null,"login":{"password":"S3cret!"}}]}""",
        )
        for (text in files) {
            val result = Importer.read(text.toByteArray(Charsets.UTF_8), NOW, noMemory, FakeRandom(7))
            assertTrue(result.toString(), result is Importer.Result.Unreadable)
            assertEquals(ParseError(0, Importer.REASON_OUT_OF_MEMORY), (result as Importer.Result.Unreadable).error)
        }
        assertEquals("out-of-memory", Importer.REASON_OUT_OF_MEMORY)
    }

    @Test
    fun noFileMakesTheImporterThrow() {
        val random = Random(42)
        val seeds = listOf(
            resource("proton/chrome.csv"), resource("proton/bitwarden.json"), resource("uzbek-cyrillic-headers.csv"),
            GoogleCsv.export(WriterFixtures.specialEntries()).parts.single(),
            BitwardenJson.export(WriterFixtures.specialEntries(), NOW, GENERATOR).bytes,
        )
        var count = 0
        fun check(bytes: ByteArray) {
            val result = read(bytes)
            assertFalse(result.toString(), result is Importer.Result.Unreadable && result.error.reason == Importer.REASON_IMPORTER_FAILURE)
            count++
        }
        repeat(1_000) { check(ByteArray(random.nextInt(700)).also(random::nextBytes)) }
        for (seed in seeds) {
            repeat(200) {
                val b = seed.copyOf(1 + random.nextInt(seed.size))
                repeat(1 + random.nextInt(8)) { b[random.nextInt(b.size)] = random.nextInt(256).toByte() }
                check(b)
            }
        }
        assertEquals(2_000, count)
    }
}
