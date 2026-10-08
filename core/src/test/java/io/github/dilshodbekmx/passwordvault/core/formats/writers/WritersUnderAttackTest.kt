package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.FieldsBlock
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.GENERATOR
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.TASHKENT
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.Texts
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.id
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.CustomField
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writers against the inputs of the break test (formats-review.md, "Writers under attack";
 * `prototypes/formats/attack/attack/Attack.kt` sections 2 to 7): every character class, a carriage
 * return without a line feed, very long websites, a thousand extra fields, passwords made of quotes.
 * Whatever an entry holds, each writer writes it, and what our own readers read back is what was
 * written. Unpaired surrogates, the one thing a writer changes, are in the other tests (P7).
 */
class WritersUnderAttackTest {
    private fun json(entries: List<Entry>): List<Entry> {
        val read = BitwardenJson.read(BitwardenJson.export(entries, T0, GENERATOR).bytes, T0, Texts, { id(999_999) })
        return (read as BitwardenJson.Read.Ok).entries
    }

    private fun csvRows(entries: List<Entry>): List<List<String>> =
        GoogleCsv.export(entries).parts.flatMap { part -> Csv.read(String(part, Charsets.UTF_8)).rows.map { it.values } }

    private fun xlsxGrid(entries: List<Entry>): List<List<String>> = XlsxReader.read(VaultXlsx.export(entries, TASHKENT).bytes).grid().drop(1)

    /** The cells as the workbook is meant to hold them: its own rules for a cell applied. */
    private fun xlsxMeant(entries: List<Entry>): List<List<String>> = entries.map { e ->
        VaultXlsx.row(e, TASHKENT).map { cell ->
            val text = Xlsx.limitLineFeeds(Xlsx.normalizeNewlines(cell ?: ""))
            if (text.length > Xlsx.MAX_CELL_CHARS) Xlsx.truncate(text, Xlsx.DEFAULT_TRUNCATION_NOTE) else text
        }
    }

    private fun assertWrittenAndReadBack(entries: List<Entry>) {
        // JSON: everything, exactly.
        val back = json(entries)
        assertEquals(entries.size, back.size)
        for ((want, got) in entries.zip(back)) assertTrue("JSON, entry ${want.id}", want == got)
        // CSV: every value of every row, exactly, as our own reader of CSV gives it.
        assertTrue("CSV", GoogleCsv.rows(entries) == csvRows(entries))
        // Excel: every cell, exactly, as the XML parser and the escape rule of ECMA-376 give it.
        assertTrue("Excel", xlsxMeant(entries) == xlsxGrid(entries))
    }

    @Test
    fun everyCharacterClassIsWrittenExactlyByEveryWriter() {
        val classes = linkedMapOf(
            "FEFF-mid" to "a﻿b", "FEFF-start" to "﻿ab", "2028" to "a b", "2029" to "a b", "0085-NEL" to "a\u0085b",
            "00A0-NBSP" to "a b", "00AD-SHY" to "a­b", "200B-ZWSP" to "a​b", "200E-LRM" to "a‎b", "202E-RLO" to "a‮b",
            "FFFD" to "a�b", "FFFE" to "a￾b", "FFFF" to "a￿b", "FDD0" to "a﷐b", "1FFFE" to "a🿾b",
            "E000-PUA" to "ab", "emoji-ZWJ" to "👨‍👩‍👧", "uz-02BB" to "oʻgʻ", "uz-2018" to "o‘g‘",
            "cyrillic" to "Пароль", "uz-cyrillic" to "Ўзбек қғҳ", "rtl" to "שלום مرحبا", "007F-DEL" to "a\u007Fb", "0001" to "a\u0001b",
            "001F" to "a\u001Fb", "0000" to "a\u0000b", "000B-VT" to "a\u000Bb", "000C-FF" to "a\u000Cb", "tab" to "a\tb",
            "lead-space" to " ab", "trail-space" to "ab ", "lead-tab" to "\tab", "only-space" to " ", "eq" to "=1+1", "plus" to "+998901234567",
            "minus" to "-2+3", "at" to "@SUM(A1:A2)", "fullwidth-eq" to "＝1+1", "x-escape" to "_x0041_", "X-upper" to "_X0041_",
            "x-lowerhex" to "_x00e9_", "x005F" to "x005F_", "both" to "_x005F_x0041_", "x-before-control" to "_x0041\u0001", "backslash" to "a\\b\\u0041",
            "lt-amp" to "<a&b>&amp;", "cdata" to "]]>", "quote" to "\"", "comma" to ",", "semicolon" to ";", "apostrophe" to "'",
            "json" to "{\"a\":[1,null]}", "sep" to "sep=;", "percent" to "100%", "dollar" to "\$HOME \${x}",
        )
        val entries = classes.entries.mapIndexed { i, (name, value) ->
            entry(i + 1, "cls $name $value", "user-$name $value", value, listOf("https://cls.example/$name"), "note $value",
                listOf(hidden("f-$name", value), visible("g $value", "v")))
        }
        assertWrittenAndReadBack(entries)
        // The same texts as a field's value through the block of the CSV's note: what the block keeps,
        // it keeps exactly (it writes every line break of a value as a line feed, nothing else).
        for ((e, value) in entries.zip(classes.values)) {
            val block = FieldsBlock.read(GoogleCsv.rows(e).single()[4])
            assertEquals(e.title, e.title, block.title)
            assertEquals(e.title, "note $value", block.notes)
            assertEquals(e.title, listOf(hidden(e.fields[0].name, value), visible("g $value".trim(), "v")), block.fields)
        }
    }

    @Test
    fun aCarriageReturnWithoutALineFeedIsWrittenAsItIs() {
        val cr = entry(1, "ti\rtle", "us\rer", "pa\rss", listOf("https://cr.example/\r"), "no\rte\r", listOf(visible("fi\reld", "va\rlue")))
        val crlf = entry(2, "ti\r\ntle", "us\r\ner", "pa\r\nss", listOf("https://crlf.example"), "no\r\nte\r\n", listOf(visible("field", "va\r\nlue")))
        // JSON and the CSV's own columns keep them; a cell of the workbook shows every line break as a line feed.
        val back = json(listOf(cr, crlf))
        assertTrue(cr == back[0] && crlf == back[1])
        val rows = csvRows(listOf(cr, crlf))
        assertEquals(listOf("ti\rtle", "https://cr.example/", "us\rer", "pa\rss"), rows[0].subList(0, 4))
        assertEquals(listOf("ti\r\ntle", "https://crlf.example", "us\r\ner", "pa\r\nss"), rows[1].subList(0, 4))
        // In the block a line break is a line feed: the name has two lines, the field's name becomes one line.
        assertEquals("no\rte\r\n\n--- PasswordVault (4 lines) ---\nTitle: ti\n  tle\nfi eld: va\n  lue", rows[0][4])
        val grid = xlsxGrid(listOf(cr, crlf))
        assertEquals(listOf("ti\ntle", "us\ner", "pa\nss"), grid[0].subList(0, 3))
        assertEquals("no\nte\n", grid[1][5])
    }

    @Test
    fun passwordsMadeOfDoubleQuotesAreWrittenExactly() {
        val quotes = (1..8).map { n -> entry(n, "\"".repeat(n), "q$n", "\"".repeat(n), listOf("https://q.example/$n"), "\"".repeat(n)) } +
            entry(9, "comma-quote", "cq", "\",\"", listOf("https://q.example/cq"), "\"\n\"") +
            entry(10, "\"", "\"", "\"", emptyList(), "\"")
        assertWrittenAndReadBack(quotes)
        val accounts = WriterFixtures.readCsv(GoogleCsv.export(quotes).parts)
        assertEquals(quotes.map { it.password }, accounts.map { it.password })
        assertEquals(quotes.map { it.notes }, accounts.map { it.notes })
    }

    @Test
    fun veryLongWebsitesAreWrittenAndReported() {
        val longUrls = listOf(2_049, 100_000, 1_100_000).mapIndexed { i, n ->
            entry(i + 1, "url$n", "u", "pw-$n", listOf("https://long.example/" + "a".repeat(n - 21)))
        }
        assertWrittenAndReadBack(longUrls)
        val export = GoogleCsv.export(longUrls)
        // Chrome refuses each for its length; the last one alone makes a part over 1,000 KB, which is written and named.
        assertEquals(List(3) { GoogleCsv.Refusal.LONG_WEBSITE }, export.report.refused.map { it.reason })
        assertEquals(listOf(2, 1), export.report.parts.map { it.rows })
        assertEquals(listOf(2), export.report.partsTooLarge)
        assertTrue(export.parts[1].size > 1_100_000)
        // In the workbook the cell is cut at Excel's limit; the JSON and the CSV hold the whole address.
        assertEquals(2, VaultXlsx.export(longUrls, TASHKENT).stats.truncatedCells)
    }

    @Test
    fun aThousandExtraFieldsAreWrittenAndReadBack() {
        val many = entry(1, "fields1000", "u", "pw-fields", listOf("https://f.example"), "note",
            (1..1_000).map { CustomField("Field $it", "value $it\nline two", it % 2 == 0) })
        assertWrittenAndReadBack(listOf(many))
        val note = GoogleCsv.rows(many).single()[4]
        assertTrue(note.startsWith("note\n\n--- PasswordVault (2001 lines) ---\nTitle: fields1000\nField 1: value 1\n  line two\nField 2 (hidden): value 2\n"))
        val block = FieldsBlock.read(note)
        assertEquals(many.fields, block.fields)
        assertEquals("note", block.notes)
        assertEquals(listOf(GoogleCsv.Refusal.LONG_NOTE), GoogleCsv.report(listOf(many)).refused.map { it.reason })
    }

    @Test
    fun anAccountWithNothingButANameOrNotEvenThatIsWritten() {
        // The reader never refuses a stored entry, so a writer may meet one that no form would save.
        val odd = listOf(
            entry(1, ""),
            entry(2, "   "),
            entry(3, "", password = "only a password"),
            entry(4, "Name", urls = listOf("", "  ", "not a website", "https://ok.example")),
            entry(5, "Fields", fields = listOf(visible("", ""), hidden("  ", "  "), visible("a\nb", "c"))),
            entry(6, "History", password = "p", history = (1..9).map { HistoryItem("", T0 - it) }),
        )
        val export = GoogleCsv.export(odd)
        assertEquals(1 + 1 + 1 + 2 + 1 + 1, export.report.rows)
        assertEquals(6, BitwardenJson.export(odd, T0, GENERATOR).items)
        assertEquals(6, VaultXlsx.export(odd, TASHKENT).stats.rows)
        // The JSON keeps even these as they are, with two exceptions that are the reader's rules: an item
        // without a name gets one, and an item without a name and without anything else is listed, not imported.
        val read = BitwardenJson.read(BitwardenJson.export(odd, T0, GENERATOR).bytes, T0, Texts, { id(999_999) }) as BitwardenJson.Read.Ok
        assertEquals(listOf("Nomsiz $T0", "Name", "Fields", "History"), read.entries.map { it.title })
        assertEquals(odd.drop(2).map { it.copy(title = "") }, read.entries.map { it.copy(title = "") })
        assertEquals(listOf(1 to BitwardenJson.NotImported.What.EMPTY, 2 to BitwardenJson.NotImported.What.EMPTY),
            read.notImported.map { it.position to it.what })
    }

    @Test
    fun timesFarOutsideAnyCalendarStopNoWriter() {
        val times = listOf(Long.MIN_VALUE, Long.MIN_VALUE / 2, -62_167_219_200_001L, -1L, 0L, 253_402_300_800_000L, Long.MAX_VALUE / 2, Long.MAX_VALUE)
        val zones = listOf(TASHKENT, TimeZone.getTimeZone("UTC"), TimeZone.getTimeZone("America/New_York"), TimeZone.getTimeZone("Pacific/Kiritimati"))
        val entries = times.mapIndexed { i, t ->
            entry(i + 1, "Time $i", "u", "p", createdAt = t, updatedAt = t, passwordChangedAt = t, history = listOf(HistoryItem("old", t)))
        }
        for (zone in zones) {
            val exported = VaultXlsx.export(entries, zone)
            assertEquals(entries.size, exported.stats.rows)
            assertEquals(entries.size + 1, XlsxReader.read(exported.bytes).grid().size)
        }
        assertEquals(entries.size, GoogleCsv.export(entries).report.rows)
        val written = BitwardenJson.export(entries, Long.MAX_VALUE, GENERATOR)
        val back = (BitwardenJson.read(written.bytes, T0, Texts, { id(1) }) as BitwardenJson.Read.Ok).entries
        assertEquals(entries.size, back.size)
        // Between the years 0 and 9999 a time comes back to the millisecond.
        for ((want, got) in entries.zip(back)) {
            if (want.createdAt in -62_167_219_200_000L until 253_402_300_800_000L) assertTrue("time ${want.createdAt}", want == got)
            assertFalse(got.title.isEmpty())
        }
    }
}
