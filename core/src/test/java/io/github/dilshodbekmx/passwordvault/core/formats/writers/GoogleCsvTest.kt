package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.FieldsBlock
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv.Refusal
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.csvAccount
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.id
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CSV export in Google's layout: the file, its parts and the report (formats.md §4). */
class GoogleCsvTest {
    private fun text(entries: List<Entry>): String {
        val export = GoogleCsv.export(entries)
        assertEquals(1, export.parts.size)
        return String(export.parts[0], Charsets.UTF_8)
    }

    private val header = "name,url,username,password,note\r\n"

    // ------------------------------------------------------------------ the file

    @Test
    fun theLayoutIsExactlyTheOneOfFormatsMd() {
        val gmail = entry(1, "Gmail", "aziz@gmail.com", "p,ss\"word", listOf("https://mail.google.com"), "Recovery phone below",
            listOf(hidden("PIN code", "1234"), visible("Address", "Toshkent, Chilonzor 5\nsecond line of the address")))
        val expected = header +
            "Gmail,https://mail.google.com,aziz@gmail.com,\"p,ss\"\"word\",\"Recovery phone below\n" +
            "\n" +
            "--- PasswordVault (4 lines) ---\n" +
            "Title: Gmail\n" +
            "PIN code (hidden): 1234\n" +
            "Address: Toshkent, Chilonzor 5\n" +
            "  second line of the address\"\r\n"
        val export = GoogleCsv.export(listOf(gmail))
        assertEquals(1, export.parts.size)
        // UTF-8, no byte-order mark, CRLF after every row, LF inside a value.
        assertArrayEquals(expected.toByteArray(Charsets.UTF_8), export.parts[0])
        assertEquals(listOf("name", "url", "username", "password", "note"), GoogleCsv.HEADER)
    }

    @Test
    fun anEmptyVaultGivesOneFileWithTheHeader() {
        val export = GoogleCsv.export(emptyList())
        assertEquals(1, export.parts.size)
        assertEquals(header, String(export.parts[0], Charsets.UTF_8))
        assertEquals(0, export.report.accounts)
        assertEquals(0, export.report.rows)
        assertEquals(listOf(0), export.report.parts.map { it.rows })
        assertEquals(header.length, export.report.parts[0].bytes)
        assertTrue(export.report.refused.isEmpty())
    }

    @Test
    fun thereIsOneRowPerWebsiteInTheOrderGivenAndOneRowWithoutAWebsite() {
        val entries = listOf(
            entry(1, "b.example", "u1", "p1", listOf("https://b.example", "https://a.example/login", "android://AbC123==@com.example.app/"), "n"),
            entry(2, "No website", "u2", "p2"),
            entry(3, "a.example", "u3", "p3", listOf("https://a.example")),
        )
        assertEquals(
            header +
                "b.example,https://b.example,u1,p1,n\r\n" +
                "b.example,https://a.example/login,u1,p1,n\r\n" +
                "b.example,android://AbC123==@com.example.app/,u1,p1,n\r\n" +
                "No website,,u2,p2,\r\n" +
                "a.example,https://a.example,u3,p3,\r\n",
            text(entries),
        )
        assertEquals(5, GoogleCsv.rows(entries).size)
        assertEquals(3, GoogleCsv.rows(entries[0]).size)
        assertEquals(listOf("No website", "", "u2", "p2", ""), GoogleCsv.rows(entries[1]).single())
    }

    @Test
    fun aWebsiteWithoutASchemeThatLooksLikeAHostGetsHttps() {
        val e = entry(1, "gmail.com", "me", "pw", listOf("gmail.com", " mail.ru/login ", "  ", "my bank", "localhost"))
        assertEquals(
            listOf("https://gmail.com", "https://mail.ru/login", "my bank", "localhost"),
            GoogleCsv.rows(e).map { it[1] },
        )
    }

    @Test
    fun deletedAccountsHaveNoRow() {
        val entries = listOf(
            entry(1, "Kept", "u", "p", listOf("https://kept.example")),
            entry(2, "Deleted", "gone", "Deleted#Pass", listOf("https://deleted.example"), "secret note",
                listOf(hidden("Deleted PIN", "0000")), deletedAt = WriterFixtures.T0),
            entry(3, "Kept too", "u", "p", listOf("https://kept2.example")),
        )
        val export = GoogleCsv.export(entries)
        val csv = String(export.parts.single(), Charsets.UTF_8)
        assertEquals(header + "Kept,https://kept.example,u,p,\"\n\n--- PasswordVault (1 lines) ---\nTitle: Kept\"\r\n" +
            "Kept too,https://kept2.example,u,p,\"\n\n--- PasswordVault (1 lines) ---\nTitle: Kept too\"\r\n", csv)
        for (gone in listOf("Deleted", "gone", "Deleted#Pass", "deleted.example", "secret note", "0000")) assertFalse(gone, csv.contains(gone))
        assertEquals(2, export.report.accounts)
        assertEquals(2, export.report.rows)
        assertEquals(2, GoogleCsv.rows(entries).size)
        // Only deleted accounts: the header alone.
        assertEquals(header, text(listOf(entries[1])))
    }

    @Test
    fun aPendingChangeWritesTheNewPasswordAndKeepsTheOldOne() {
        val e = entry(1, "Gmail", "aziz@gmail.com", "N3w!pass", listOf("https://mail.google.com"), "",
            listOf(hidden("PIN", "1234")), changePending = true, pendingOldPassword = "Old#2")
        assertEquals(
            header + "Gmail,https://mail.google.com,aziz@gmail.com,N3w!pass,\"\n\n--- PasswordVault (3 lines) ---\n" +
                "Title: Gmail\nPIN (hidden): 1234\nOld password (hidden): Old#2\"\r\n",
            text(listOf(e)),
        )
        val back = WriterFixtures.readCsv(GoogleCsv.export(listOf(e)).parts).single()
        assertEquals("N3w!pass", back.password)
        assertEquals("Old#2", back.pendingOldPassword)
        assertTrue(back.changePending)
    }

    @Test
    fun valuesAreWrittenExactlyAndQuotedOnlyWhenTheyMustBe() {
        val e = entry(1, "=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "-2+3", listOf("https://formula.example"), "@SUM(A1:A2)")
        val others = listOf(
            entry(2, " leading space", "trailing space ", "\ttab first", listOf("https://q.example/?a=1,2"), "tab last\t"),
            entry(3, "a\"b", "\"", "\"\"", listOf("https://q.example/2"), "two\nlines"),
            entry(4, "semi;colon", "single'quote", "back\\slash", listOf("https://q.example/3"), "cr\rinside"),
        )
        val csv = text(listOf(e) + others)
        // Nothing is put in front of a value that a spreadsheet would take for a formula (formats.md §4.6).
        assertTrue(csv.contains("\r\n\"=HYPERLINK(\"\"http://evil.example\"\",\"\"click\"\")\",https://formula.example,+998901234567,-2+3,"))
        // The layout rule, written out again: quoted when the value holds , " CR or LF, or starts or ends with a space or a tab.
        fun quoted(v: String) =
            if (v.isNotEmpty() && (v.any { it in ",\"\r\n" } || v.first() in " \t" || v.last() in " \t")) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val rows = listOf(GoogleCsv.HEADER) + GoogleCsv.rows(listOf(e) + others)
        assertEquals(rows.joinToString("") { row -> row.joinToString(",") { quoted(it) } + "\r\n" }, csv)
        // And our own reader gives every value back.
        val table = Csv.read(csv)
        assertEquals(GoogleCsv.rows(listOf(e) + others), table.rows.map { it.values })
        assertTrue(table.wrongLength.isEmpty())
    }

    @Test
    fun ourCsvReadsBackWithoutLoss() {
        val stored = WriterFixtures.specialEntries().filter(WriterFixtures::isAsStored)
        assertTrue(stored.size >= 15)
        val export = GoogleCsv.export(stored)
        val back = WriterFixtures.readCsv(export.parts)
        // What formats.md §4.5 says the CSV keeps: title, username, password, every website, notes, the
        // extra fields with their hidden flag, and a pending change's old password.
        assertEquals(stored.map(::csvAccount), back)
        // The same accounts after Google has thrown away the name column: the Title line still names them.
        val withoutNames = export.parts.map { part ->
            val table = Csv.read(String(part, Charsets.UTF_8))
            Csv.write(GoogleCsv.HEADER, table.rows.map { listOf("") + it.values.drop(1) }).toByteArray(Charsets.UTF_8)
        }
        val named = WriterFixtures.readCsv(withoutNames)
        for ((want, got) in stored.map(::csvAccount).zip(named)) {
            // Without a Title line the title equals the host of the first website, or there is no website.
            if (got.title.isNotEmpty()) assertEquals(want.title, got.title)
            assertEquals(want.copy(title = ""), got.copy(title = ""))
        }
        assertEquals(stored.size, named.size)
    }

    @Test
    fun theUsersNotesThatLookLikeABlockComeBackAsNotes() {
        // Vector 8 of formats.md §4.4, through the whole file.
        val notes = "Old notes:\n\n" + FieldsBlock.marker(1) + "\nPIN: 9999"
        val e = entry(1, "mail.ru", "ivan", "M@il1", listOf("https://mail.ru"), notes)
        val back = WriterFixtures.readCsv(GoogleCsv.export(listOf(e)).parts).single()
        assertEquals(notes, back.notes)
        assertTrue(back.fields.isEmpty())
        assertEquals("mail.ru", back.title)
    }

    @Test
    fun everyVectorOfFormatsMdComesBackWholeThroughTheFile() {
        // formats.md §4.4: the four cases of formats-review P8, both reserved labels, an escaped
        // backslash and notes that read as a block, in one file, read back by our own readers.
        val vectors = WriterFixtures.vectors()
        val export = GoogleCsv.export(vectors.map { it.entry })
        val rows = Csv.read(String(export.parts.single(), Charsets.UTF_8)).rows.map { it.values }
        assertEquals(vectors.size, rows.size)
        for ((vector, row) in vectors.zip(rows)) assertEquals(vector.toString(), vector.note, row[4])
        // Vector 6: the password column holds the new password; the block keeps the old one.
        assertEquals("N3w!pass", rows[vectors.indexOfFirst { it.number == 6 }][3])
        val back = WriterFixtures.readCsv(export.parts)
        for ((vector, account) in vectors.zip(back)) assertEquals(vector.toString(), csvAccount(vector.entry), account)
        // What each vector says it reads back as.
        assertEquals(listOf(visible("Q: mother's name", "Aziza")), back[0].fields)
        assertEquals("Branch: Chilonzor", back[0].notes)
        assertEquals(listOf(visible("Code (hidden)", "77"), hidden("PIN", "1234")), back[1].fields)
        assertEquals(listOf(visible("A", "1"), visible("B", "x")), back[2].fields)
        for (pasted in back.subList(3, 5)) assertTrue(pasted.fields.isEmpty())
        assertEquals(listOf(visible("Title", "Mr"), visible("Old password", "x")), back[5].fields)
        assertEquals("Telegram", back[5].title)
        assertFalse(back[5].changePending)
        assertEquals(Triple("N3w!pass", "Old#2", true), Triple(back[6].password, back[6].pendingOldPassword, back[6].changePending))
        assertEquals(listOf(visible("C:\\keys", "line 1\nline 2")), back[7].fields)
        assertEquals(vectors[8].entry.notes, back[8].notes)
        assertTrue(back[8].fields.isEmpty())
    }

    @Test
    fun unpairedSurrogatesAreWrittenAsTheReplacementCharacterAndCounted() {
        val e = entry(1, "T\uD800x", "u\uDC00", "p\uD83D", listOf("https://x.example/a\uD800", "https://y.example"), "n\uDE00\uD83Dz",
            listOf(hidden("F\uD800", "v\uDFFF")))
        val export = GoogleCsv.export(listOf(e, entry(2, "fine 😀", "u", "p", listOf("https://fine.example"))))
        val bytes = export.parts.single()
        val csv = String(bytes, Charsets.UTF_8)
        assertArrayEquals(csv.toByteArray(Charsets.UTF_8), bytes)
        assertFalse(csv.contains('?')) // what a UTF-8 encoder writes for one
        assertTrue(csv.contains("T�x,https://x.example/a�,u�,p�,"))
        assertTrue(csv.contains("F� (hidden): v�"))
        assertTrue(csv.contains("fine 😀")) // a pair is left alone
        // The title's one, the username's, the password's and the note's five (two in the notes, the Title
        // line's, the field's name and value) are written in both rows; the first url's one once.
        assertEquals(2 * (1 + 1 + 1 + 5) + 1, export.report.replacedSurrogates)
        assertEquals(0, GoogleCsv.export(listOf(entry(2, "fine 😀"))).report.replacedSurrogates)
    }

    // ------------------------------------------------------------------ parts

    private fun small(n: Int) = entry(n, "site$n.example", "user$n", "pass$n", listOf("https://site$n.example"))

    @Test
    fun aFileHasAtMost3000RowsAndAnAccountsRowsStayTogether() {
        val exactly = GoogleCsv.export((1..3_000).map(::small))
        assertEquals(listOf(3_000), exactly.report.parts.map { it.rows })

        val oneMore = GoogleCsv.export((1..3_001).map(::small))
        assertEquals(listOf(3_000, 1), oneMore.report.parts.map { it.rows })
        assertEquals(2, oneMore.parts.size)
        assertTrue(oneMore.report.partsTooLarge.isEmpty())

        // 2,999 rows, then an account with three websites: it goes to the next file whole.
        val three = entry(5_000, "Three", "u", "p", listOf("https://a.example", "https://b.example", "https://c.example"))
        val split = GoogleCsv.export((1..2_999).map(::small) + three + small(6_000))
        assertEquals(listOf(2_999, 4), split.report.parts.map { it.rows })
        val second = Csv.read(String(split.parts[1], Charsets.UTF_8))
        assertEquals(listOf("Three", "Three", "Three", "site6000.example"), second.rows.map { it.values[0] })
        // Every part is a file of its own: header, rows, the sizes of the report.
        for ((i, part) in split.parts.withIndex()) {
            val csv = String(part, Charsets.UTF_8)
            assertTrue(csv.startsWith(header))
            assertEquals(split.report.parts[i].rows, Csv.read(csv).rows.size)
            assertEquals(split.report.parts[i].bytes, part.size)
        }
        assertEquals(3_003, split.report.rows)
        assertEquals(3_001, split.report.accounts)
        // All parts together are the rows, in order.
        assertEquals(
            GoogleCsv.rows((1..2_999).map(::small) + three + small(6_000)),
            split.parts.flatMap { Csv.read(String(it, Charsets.UTF_8)).rows.map { row -> row.values } },
        )
    }

    @Test
    fun aFileHasAtMost1000KilobytesCountedInBytesOfUtf8() {
        // Each row holds 50,000 Cyrillic letters: 100,000 bytes and a little more.
        val big = (1..21).map { n -> entry(n, "site$n.example", "user$n", "pass$n", listOf("https://site$n.example"), "ж".repeat(50_000)) }
        val export = GoogleCsv.export(big)
        assertEquals(listOf(10, 10, 1), export.report.parts.map { it.rows })
        for ((i, part) in export.parts.withIndex()) {
            assertTrue(part.size <= GoogleCsv.MAX_BYTES)
            assertEquals(part.size, export.report.parts[i].bytes)
        }
        assertTrue(export.parts[0].size + 100_000 > GoogleCsv.MAX_BYTES) // the eleventh would not have fitted
        assertTrue(export.report.partsTooLarge.isEmpty())
        assertEquals(1_000 * 1_024, GoogleCsv.MAX_BYTES)
        assertEquals(3_000, GoogleCsv.MAX_ROWS)
    }

    @Test
    fun aPartThatOneAccountAloneMakesTooLargeIsWrittenAndReported() {
        // Two websites, so two rows of 600 KB each: no file can hold this account within Chrome's limit.
        val huge = entry(2, "Huge", "u", "p", listOf("https://a.example", "https://b.example"), "x".repeat(600_000))
        val export = GoogleCsv.export(listOf(small(1), huge, small(3)))
        assertEquals(listOf(1, 2, 1), export.report.parts.map { it.rows })
        assertEquals(listOf(false, true, false), export.report.parts.map { it.tooLarge })
        assertEquals(listOf(2), export.report.partsTooLarge)
        assertTrue(export.parts[1].size > GoogleCsv.MAX_BYTES)
        // Nothing is left out because of it.
        assertEquals(4, export.parts.sumOf { Csv.read(String(it, Charsets.UTF_8)).rows.size })

        // The same for rows: an account with more websites than a file may have rows (never made by the app).
        val manyUrls = entry(4, "Many", "u", "p", (1..3_001).map { "https://s$it.example" })
        val rows = GoogleCsv.export(listOf(small(1), manyUrls))
        assertEquals(listOf(1, 3_001), rows.report.parts.map { it.rows })
        assertEquals(listOf(2), rows.report.partsTooLarge)
    }

    // ------------------------------------------------------------------ the report

    private fun reasons(vararg entries: Entry) = GoogleCsv.report(entries.toList()).refused.map { it.reason }

    @Test
    fun p15_theReportFollowsChromesOwnChecksOfAUrl() {
        fun e(n: Int, url: String) = entry(n, "t$n", "u", "pw", listOf(url))
        val cyrillic = "https://пример.рф/" + "путь".repeat(250)
        val report = GoogleCsv.report(listOf(
            e(1, "HTTPS://MAIL.RU/"), // an upper-case scheme is accepted
            e(2, cyrillic), // 1,018 characters as typed, 6,030 as Chrome counts them
            e(3, "https://exa mple.com/"), // a space in the host
            e(4, "android://com.example.app/"), // no certificate hash
            e(5, "android://AbC123==@com.example.app/"),
            e(6, "https://пример.рф/путь"),
        ))
        assertEquals(listOf(id(2), id(3), id(4)), report.refused.map { it.entryId })
        assertEquals(listOf(Refusal.LONG_WEBSITE, Refusal.BAD_WEBSITE, Refusal.BAD_WEBSITE), report.refused.map { it.reason })
        assertEquals(listOf(cyrillic, "https://exa mple.com/", "android://com.example.app/"), report.refused.map { it.url })
        assertEquals(1, report.rows(Refusal.LONG_WEBSITE))
        assertEquals(2, report.rows(Refusal.BAD_WEBSITE))
        assertEquals(listOf(id(3), id(4)), report.accounts(Refusal.BAD_WEBSITE))
        assertEquals(6, report.accounts)
    }

    @Test
    fun theReasonsComeInChromesOrder() {
        val long = "x".repeat(1_001)
        // Without a password nothing else is looked at.
        assertEquals(listOf(Refusal.NO_PASSWORD), reasons(entry(1, "t", long, "", emptyList(), long)))
        assertEquals(listOf(Refusal.LONG_PASSWORD), reasons(entry(1, "t", long, long, emptyList(), long)))
        assertEquals(listOf(Refusal.LONG_USERNAME), reasons(entry(1, "t", long, "pw", emptyList(), long)))
        assertEquals(listOf(Refusal.LONG_NOTE), reasons(entry(1, "t", "u", "pw", emptyList(), long)))
        assertEquals(listOf(Refusal.NO_WEBSITE), reasons(entry(1, "t", "u", "pw")))
        assertEquals(listOf(Refusal.BAD_WEBSITE), reasons(entry(1, "t", "u", "pw", listOf("my bank"))))
        assertEquals(emptyList<Refusal>(), reasons(entry(1, "example.com", "u", "pw", listOf("example.com"))))
        assertEquals(emptyList<Refusal>(), reasons(entry(1, "example.com", "", "pw", listOf("https://example.com")))) // no username is fine
    }

    @Test
    fun theLimitsAreBytesOfUtf8() {
        fun e(username: String = "u", password: String = "pw", notes: String = "") =
            entry(1, "example.com", username, password, listOf("https://example.com"), notes)
        assertEquals(emptyList<Refusal>(), reasons(e(password = "x".repeat(1_000))))
        assertEquals(listOf(Refusal.LONG_PASSWORD), reasons(e(password = "x".repeat(1_001))))
        assertEquals(emptyList<Refusal>(), reasons(e(password = "ж".repeat(500))))
        assertEquals(listOf(Refusal.LONG_PASSWORD), reasons(e(password = "ж".repeat(500) + "x")))
        assertEquals(emptyList<Refusal>(), reasons(e(username = "ж".repeat(500))))
        assertEquals(listOf(Refusal.LONG_USERNAME), reasons(e(username = "ж".repeat(501))))
        // About 500 Cyrillic letters of notes, as formats.md §4.2 says.
        assertEquals(emptyList<Refusal>(), reasons(e(notes = "ж".repeat(500))))
        assertEquals(listOf(Refusal.LONG_NOTE), reasons(e(notes = "ж".repeat(501))))
        assertEquals(emptyList<Refusal>(), reasons(e(notes = "😀".repeat(250))))
        assertEquals(listOf(Refusal.LONG_NOTE), reasons(e(notes = "😀".repeat(250) + "x")))
    }

    @Test
    fun theBlockCountsTowardsTheNote() {
        val notes = "x".repeat(960)
        val plain = entry(1, "example.com", "u", "pw", listOf("https://example.com"), notes)
        assertEquals(emptyList<Refusal>(), reasons(plain))
        // The same notes with one extra field: "\n\n--- PasswordVault (1 lines) ---\nPIN (hidden): 1234" is 52 more.
        val withField = plain.copy(fields = listOf(hidden("PIN", "1234")))
        assertEquals(960 + 52, GoogleCsv.rows(withField).single()[4].length)
        assertEquals(listOf(Refusal.LONG_NOTE), reasons(withField))
        val report = GoogleCsv.report(listOf(plain, withField, plain.copy(id = id(3), title = "Other name")))
        assertEquals(2, report.accountsWithBlock) // the field, and the name that differs from the host
    }

    @Test
    fun everyRowOfAnAccountIsJudgedWithItsOwnUrl() {
        val mixed = entry(1, "Mixed", "u", "pw", listOf("https://good.example", "my bank", "https://also-good.example"))
        val report = GoogleCsv.report(listOf(mixed))
        assertEquals(3, report.rows)
        assertEquals(listOf("my bank"), report.refused.map { it.url })

        // What does not depend on the url refuses every row; the account is named once.
        val noPassword = entry(2, "No password", "u", "", listOf("https://a.example", "https://b.example", "https://c.example"))
        val all = GoogleCsv.report(listOf(mixed, noPassword, entry(3, "Deleted", "u", "", deletedAt = 1L)))
        assertEquals(3, all.rows(Refusal.NO_PASSWORD))
        assertEquals(listOf(id(2)), all.accounts(Refusal.NO_PASSWORD))
        assertEquals(listOf(id(1)), all.accounts(Refusal.BAD_WEBSITE))
        assertEquals(emptyList<String>(), all.accounts(Refusal.NO_WEBSITE))
        assertEquals(2, all.accounts) // the deleted account is neither written nor reported
        assertEquals(6, all.rows)
    }

    @Test
    fun theReportSaysNothingOfTheAccountsInItsText() {
        val e = entry(1, "Secret title", "secret-user", "", listOf("https://secret.example/path"), "secret note")
        val export = GoogleCsv.export(listOf(e))
        val shown = export.toString() + export.report + export.report.refused + export.report.parts
        for (secret in listOf("Secret title", "secret-user", "secret.example", "secret note")) assertFalse(shown, shown.contains(secret))
        assertTrue(shown.contains(Refusal.NO_PASSWORD.name))
    }
}
