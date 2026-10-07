package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.ExportNames
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv.Refusal
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.TASHKENT
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.csvAccount
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.id
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import io.github.dilshodbekmx.passwordvault.testing.arch.SourceTree
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The checks of the export prototype (`prototypes/formats/test/FormatsTest.kt`) on version 1's
 * writers, over the prototype's awkward entries, the cases version 1 added and 5,000 generated
 * entries; and the sample files of version 1.
 *
 * [theSampleFilesAreWrittenForTheOutsideChecks] writes the three exports of that one vault, with the
 * app's own file names, into `core/build/samples/`, where `tools/validate/run.sh core/build/samples`
 * checks them with readers other than ours (Python's `csv` and `json`, a transcription of Chromium's
 * CSV importer, openpyxl, LibreOffice). A test run that Gradle takes from its cache writes nothing:
 * `tools/gradle.sh :core:testDebugUnitTest --tests '*FormatSamplesTest*' -Ppv.samples` always runs
 * (any `pv.` property does that, root build.gradle.kts).
 *
 * The prototype's checks of the importer (other encodings and header languages, Bitwarden's and
 * KeePassXC's CSV, the import plan) belong to build-plan package 3.15, and its locked workbook is not
 * in version 1 (architecture.md D-16).
 */
class FormatSamplesTest {
    private val special = WriterFixtures.specialEntries()
    private val bulk = WriterFixtures.bulkEntries(5_000)
    private val all = special + bulk
    private val live = all.filter { it.deletedAt == null }

    /** The entries that follow ux.md's input rules: what the round trips promise to keep whole. */
    private val stored = all.filter(WriterFixtures::isAsStored)

    private val csv by lazy { GoogleCsv.export(all) }
    private val json by lazy { BitwardenJson.export(all, T0, GENERATOR) }
    private val xlsx by lazy { VaultXlsx.export(all, TASHKENT) }

    private fun special(title: String) = special.first { it.title == title }

    @Test
    fun theVaultOfTheSamplesIsThePrototypesAndMore() {
        assertEquals(24, special.size)
        assertEquals(5_024, all.size)
        assertEquals(5_023, live.size) // one account is in Recently deleted
        assertEquals(5_017, stored.size) // 17 of the special ones follow the input rules, and all generated ones
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    // ------------------------------------------------------------------ CSV

    @Test
    fun csv_thePrototypesChecks() {
        val expected = GoogleCsv.rows(all)
        // The Full entry has three websites, every other live account one row.
        assertEquals(5_025, expected.size)
        assertEquals(5_025, csv.report.rows)
        assertEquals(5_023, csv.report.accounts)
        // Over Chrome's 3,000 rows: two parts, each within both limits, together the rows in order.
        assertEquals(listOf(3_000, 2_025), csv.report.parts.map { it.rows })
        assertTrue(csv.report.partsTooLarge.isEmpty())
        val read = ArrayList<List<String>>()
        for (part in csv.parts) {
            val text = String(part, Charsets.UTF_8)
            assertTrue(text.startsWith("name,url,username,password,note\r\n"))
            assertTrue(part.size <= GoogleCsv.MAX_BYTES)
            val table = Csv.read(text)
            assertEquals(',', table.delimiter)
            assertEquals(0, table.unclosedQuoteLine)
            assertTrue(table.wrongLength.isEmpty())
            assertEquals(0, table.blankLines)
            assertTrue(table.rows.size <= GoogleCsv.MAX_ROWS)
            read += table.rows.map { it.values }
        }
        assertTrue(read == expected) // no message: the rows are passwords
        // What Chrome's importer would refuse (formats.md §9): the two notes over 1,000 bytes, the account without a password.
        assertEquals(
            listOf(special("Long note").id to Refusal.LONG_NOTE, special("Empty fields").id to Refusal.NO_PASSWORD,
                special("Many lines").id to Refusal.LONG_NOTE),
            csv.report.refused.map { it.entryId to it.reason },
        )
        // Accounts whose note carries a block: a field, a name that is not the website's host, an old password.
        assertEquals(live.count { GoogleCsv.rows(it)[0][4] != TextHygiene.replaceUnpairedSurrogates(it.notes) }, csv.report.accountsWithBlock)
        assertTrue(csv.report.accountsWithBlock > 5_000) // "o‘zbek 1" is not "site0.example.com"
    }

    @Test
    fun csv_readsBackWithoutLossThroughOurOwnReaders() {
        val back = WriterFixtures.readCsv(csv.parts)
        assertEquals(live.size, back.size)
        val byId = live.map { it.id }.zip(back).toMap()
        // Every entry that follows the input rules comes back whole: title, username, password, all
        // websites, notes, the extra fields with their hidden flag, a pending change's old password.
        for (e in stored) assertTrue("entry ${e.id}", csvAccount(e) == byId.getValue(e.id))

        // The prototype's single checks, on version 1's readers.
        val full = byId.getValue(special("Full entry").id)
        assertEquals(special("Full entry").urls, full.urls) // the three rows are one account again
        assertEquals("Everything set", full.notes)
        assertEquals(special("Full entry").fields, full.fields)
        assertEquals(" spaced password ", byId.getValue(special("Multi-line notes").id).password)
        assertEquals(listOf("https://gmail.com"), byId.getValue(special("No scheme").id).urls) // the scheme is added
        assertEquals(300, byId.getValue(special("Many lines").id).notes.lines().size)
        // A password is written exactly, also with a control character in it (formats-review P14);
        // the prototype's importer removed these.
        assertEquals("ctrl\u0001char", byId.getValue(special("Control characters").id).password)
        assertEquals("tab\there", byId.getValue(special("Tab case").id).password)
        assertEquals(WriterFixtures.LONG_NOTE, byId.getValue(special("Long note").id).notes) // the CSV itself cuts nothing
        // Version 1: the pending change, and the user's note that looks like a block.
        val gmail = byId.getValue(special("Gmail").id)
        assertEquals("N3w!pass" to "Old#2", gmail.password to gmail.pendingOldPassword)
        assertTrue(gmail.changePending)
        assertEquals(special("mail.ru").notes, byId.getValue(special("mail.ru").id).notes)
        assertTrue(byId.getValue(special("mail.ru").id).fields.isEmpty())
    }

    // ------------------------------------------------------------------ JSON

    @Test
    fun json_thePrototypesChecks() {
        val read = BitwardenJson.read(json.bytes, T0, Texts, WriterFixtures.Ids(900_001)::next) as BitwardenJson.Read.Ok
        assertTrue(read.ownFormat)
        assertFalse(read.newerVersion)
        assertTrue(read.notImported.isEmpty())
        assertEquals(live.size, read.entries.size)
        assertEquals(live.size, json.items)
        // Lossless, unknown keys included ("From the future" carries two): every live entry but the one
        // whose unpaired surrogates were written as U+FFFD.
        val lone = special.first { it.title.contains('\uD800') }
        for ((want, got) in live.zip(read.entries)) {
            if (want.id == lone.id) continue
            assertTrue("entry ${want.id}", want == got)
        }
        assertEquals(mapOf("futureField" to "{\"a\":[1,2]}", "futureNumber" to "1.50"), read.entries.first { it.title == "From the future" }.extras)
        val replaced = read.entries.first { it.id == lone.id }
        assertEquals(listOf("T�x", "u�", "p�", "n��z"), listOf(replaced.title, replaced.username, replaced.password, replaced.notes))
        assertEquals(8, json.replacedSurrogates)
        // Read again and written again: the same file.
        assertArrayEquals(json.bytes, BitwardenJson.export(read.entries, T0, GENERATOR, read.vaultExtras).bytes)
    }

    // ------------------------------------------------------------------ Excel

    private fun expectedCells(): List<List<String>> {
        val header = VaultXlsx.columns().map { it.header }
        return listOf(header) + live.map { e ->
            VaultXlsx.row(e, TASHKENT).map { cell ->
                val text = Xlsx.limitLineFeeds(Xlsx.normalizeNewlines(TextHygiene.replaceUnpairedSurrogates(cell ?: "")))
                // tools/validate's convention for a cell the writer had to cut: the first characters it kept.
                if (text.length > Xlsx.MAX_CELL_CHARS) "truncated:" + text.substring(0, 1_000) else text
            }
        }
    }

    @Test
    fun xlsx_thePrototypesChecks() {
        assertEquals(live.size, xlsx.stats.rows)
        assertEquals(1, xlsx.stats.truncatedCells) // the 40,000-character note
        assertEquals(8, xlsx.stats.replacedSurrogates)
        val book = XlsxReader.read(xlsx.bytes)
        val grid = book.grid()
        val expected = expectedCells()
        assertEquals(expected.size, grid.size)
        for (r in expected.indices) {
            for (c in 0 until 11) {
                val want = expected[r][c]
                val got = grid[r][c]
                val same = if (want.startsWith("truncated:")) {
                    got.startsWith(want.substring(10)) && got.length <= Xlsx.MAX_CELL_CHARS && got.endsWith("32,767 in one cell]")
                } else {
                    got == want
                }
                assertTrue("row ${r + 1}, column ${c + 1}", same)
            }
        }
        // 300 lines of recovery codes: the first 253 line feeds stay, the rest become " ↵ ".
        val manyLines = grid.first { it[0] == "Many lines" }[5]
        assertEquals(Xlsx.MAX_LINE_FEEDS, manyLines.count { it == '\n' })
        assertTrue(manyLines.endsWith("recovery code 299 ↵ recovery code 300"))
    }

    // ------------------------------------------------------------------ all three

    @Test
    fun deletedEntriesAreInNoOutput() {
        val deleted = special("Deleted account")
        val secrets = listOf("Deleted account", "gone@example.com", "Deleted#Pass", "deleted.example", "must not be exported", "Deleted PIN", deleted.id)
        val texts = csv.parts.map { String(it, Charsets.UTF_8) } + String(json.bytes, Charsets.UTF_8) +
            XlsxReader.read(xlsx.bytes).sharedStrings.joinToString("\n")
        assertEquals(4, texts.size)
        for (text in texts) {
            for (secret in secrets) assertFalse(secret, text.contains(secret))
        }
        // And every other account is in each of them, once.
        assertEquals(live.size, json.items)
        assertEquals(live.size, xlsx.stats.rows)
        assertEquals(live.size, csv.report.accounts)
        assertEquals(live.map { TextHygiene.replaceUnpairedSurrogates(it.title) }, XlsxReader.read(xlsx.bytes).grid().drop(1).map { it[0] })
    }

    @Test
    fun theThreeFilesHoldTheSameAccounts() {
        // What tools/validate compares: title, username and password, once per account (once per website in the CSV).
        fun clean(s: String) = TextHygiene.replaceUnpairedSurrogates(s)
        val wanted = live.map { Triple(clean(it.title), clean(it.username), clean(it.password)) }
        val fromXlsx = XlsxReader.read(xlsx.bytes).grid().drop(1).map { Triple(it[0], it[1], it[2]) }
        assertTrue(wanted == fromXlsx)
        val fromJson = (BitwardenJson.read(json.bytes, T0, Texts, { id(1) }) as BitwardenJson.Read.Ok).entries
            .map { Triple(it.title, it.username, it.password) }
        assertTrue(wanted == fromJson)
        val fromCsv = WriterFixtures.readCsv(csv.parts).map { Triple(it.title, it.username, it.password) }
        assertTrue(wanted == fromCsv)
    }

    @Test
    fun fiveThousandEntriesAreWrittenAndReadQuickly() {
        // formats.md §2 rule 10 measured 3 to 17 ms for the CSV and 42 to 102 ms for the others on this
        // machine; the bound here only catches a writer that has become slow by orders of magnitude.
        fun ms(block: () -> Unit): Long {
            val started = System.nanoTime()
            block()
            return (System.nanoTime() - started) / 1_000_000
        }
        val times = linkedMapOf(
            "CSV export" to ms { GoogleCsv.export(all) },
            "CSV read" to ms { WriterFixtures.readCsv(csv.parts) },
            "JSON export" to ms { BitwardenJson.export(all, T0, GENERATOR) },
            "JSON read" to ms { BitwardenJson.read(json.bytes, T0, Texts, { id(1) }) },
            "XLSX export" to ms { VaultXlsx.export(all, TASHKENT) },
        )
        for ((what, took) in times) assertTrue("$what took $took ms", took < 5_000)
    }

    // ------------------------------------------------------------------ the sample files

    private fun sha256(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return Bytes.hex(digest.digest())
    }

    /**
     * The files of version 1 are the same bytes whatever the default locale and time zone: this test
     * runs under tr-TR and fa-IR (tools/check.sh), and the sums below are the same in both. The
     * workbook is summed over its parts as they are before compression, which the zip library of the
     * machine does. A change to a writer or to the entries of [WriterFixtures] changes these sums:
     * then look at what changed in the files, and write the new sums here.
     */
    @Test
    fun theFilesAreTheSameBytesEverywhere() {
        assertEquals(CSV_SHA256, sha256(*csv.parts.toTypedArray()))
        assertEquals(JSON_SHA256, sha256(json.bytes))
        val book = XlsxReader.read(xlsx.bytes)
        assertEquals(XLSX_PARTS_SHA256, sha256(*book.names.flatMap { listOf(it.toByteArray(), book.text(it).toByteArray()) }.toTypedArray()))
    }

    @Test
    fun theSampleFilesAreWrittenForTheOutsideChecks() {
        val dir = File(SourceTree.root(), "core/build/samples")
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        // The app's own names (formats.md §2 rule 5), for an export made at T0 on a phone in Tashkent.
        val names = ArrayList<String>()
        csv.parts.forEachIndexed { i, part ->
            val name = ExportNames.csv(T0, TASHKENT, i + 1, csv.parts.size)
            File(dir, name).writeBytes(part)
            names += name
        }
        File(dir, ExportNames.json(T0, TASHKENT)).writeBytes(json.bytes)
        File(dir, ExportNames.xlsx(T0, TASHKENT)).writeBytes(xlsx.bytes)
        names += ExportNames.json(T0, TASHKENT)
        names += ExportNames.xlsx(T0, TASHKENT)
        // What the writers meant, for tools/validate to compare exactly (its README, "Expected values").
        fun jsonRows(rows: List<List<String>>) = JsonArray(rows.map { row -> JsonArray(row.map(::JsonPrimitive)) }).toString()
        File(dir, "expected-csv-rows.json").writeText(jsonRows(GoogleCsv.rows(all)), Charsets.UTF_8)
        File(dir, "expected-xlsx-cells.json").writeText(jsonRows(expectedCells()), Charsets.UTF_8)

        assertEquals(
            listOf("passwords-2026-10-06-part1.csv", "passwords-2026-10-06-part2.csv", "passwords-2026-10-06.json", "passwords-2026-10-06.xlsx"),
            names,
        )
        assertEquals(
            (names + listOf("expected-csv-rows.json", "expected-xlsx-cells.json")).sorted(),
            dir.listFiles()!!.map { it.name }.sorted(),
        )
        for ((i, part) in csv.parts.withIndex()) assertArrayEquals(part, File(dir, names[i]).readBytes())
        assertArrayEquals(json.bytes, File(dir, names[2]).readBytes())
        assertArrayEquals(xlsx.bytes, File(dir, names[3]).readBytes())
    }

    private companion object {
        const val CSV_SHA256 = "6c4c87e9264e15472550cb038bfd0cfc8818a16597559076b88cce3edce24a9c"
        const val JSON_SHA256 = "56589af56a3ed60f3f616c845240acfe3515f2cdb8ad2698237139b2c374f524"
        const val XLSX_PARTS_SHA256 = "fd503724d4d3de426e9b2f37ce943e9deffaa0ec017a301324766481422cf86d"
    }
}
