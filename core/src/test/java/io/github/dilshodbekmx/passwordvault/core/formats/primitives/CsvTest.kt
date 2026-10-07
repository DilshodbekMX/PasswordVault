package io.github.dilshodbekmx.passwordvault.core.formats.primitives

import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTest {
    private fun values(table: Csv.Table) = table.rows.map { it.values }
    private fun lines(table: Csv.Table) = table.rows.map { it.line }

    // ------------------------------------------------------------------ writing

    @Test
    fun aValueIsQuotedOnlyWhenItMustBe() {
        val plain = listOf("Gmail", "https://mail.google.com/?a=1;b=2", "aziz@gmail.com", "p@ss=word-🔑", "Oʻzbek", "", "a'b", "=1+1", "+998901234567")
        for (v in plain) assertFalse(v, Csv.needsQuotes(v))
        val quoted = mapOf(
            "a,b" to "\"a,b\"",
            "say \"hi\"" to "\"say \"\"hi\"\"\"",
            "line1\nline2" to "\"line1\nline2\"",
            "cr\rhere" to "\"cr\rhere\"",
            " lead" to "\" lead\"",
            "trail " to "\"trail \"",
            "\ttab" to "\"\ttab\"",
            "tab\t" to "\"tab\t\"",
            "\"" to "\"\"\"\"",
        )
        for ((v, written) in quoted) {
            assertTrue(v, Csv.needsQuotes(v))
            val sb = StringBuilder()
            Csv.appendField(sb, v)
            assertEquals(written, sb.toString())
        }
        assertFalse(Csv.needsQuotes("mid\tdle"))
        // With another delimiter, that delimiter needs quotes and the comma does not.
        assertTrue(Csv.needsQuotes("a;b", ';'))
        assertFalse(Csv.needsQuotes("a,b", ';'))
    }

    @Test
    fun rowsEndInCrlfWithNoByteOrderMark() {
        val text = Csv.write(listOf("name", "url"), listOf(listOf("Bank", "https://bank.uz"), listOf("a,b", "")))
        assertEquals("name,url\r\nBank,https://bank.uz\r\n\"a,b\",\r\n", text)
        assertEquals("name;url\nBank;x\n", Csv.write(listOf("name", "url"), listOf(listOf("Bank", "x")), "\n", ';'))
        assertThrows(IllegalArgumentException::class.java) { Csv.write(listOf("a"), emptyList(), delimiter = '"') }
    }

    @Test
    fun theWriterReplacesUnpairedSurrogatesAndCountsThem() {
        val counter = TextHygiene.Counter()
        val text = Csv.write(listOf("name", "note"), listOf(listOf("T\uD800x", "n\uDE00\uD83Dz 🔑")), replaced = counter)
        assertEquals(3, counter.count)
        assertEquals("name,note\r\nT\uFFFDx,n\uFFFD\uFFFDz 🔑\r\n", text)
        assertFalse(TextHygiene.hasUnpairedSurrogate(text))
    }

    // ------------------------------------------------------------------ reading

    @Test
    fun rfc4180() {
        val text = "a,b,c\r\n\"x,1\",\"say \"\"hi\"\"\",\r\n\"multi\r\nline\",\"lf\nonly\",\"cr\ronly\"\r\n,,\r\n"
        val table = Csv.read(text)
        assertEquals(listOf("a", "b", "c"), table.header!!.values)
        assertEquals(
            listOf(listOf("x,1", "say \"hi\"", ""), listOf("multi\r\nline", "lf\nonly", "cr\ronly"), listOf("", "", "")),
            values(table),
        )
        // The second row starts on line 3 and holds a CRLF, an LF and a CR: the third starts on line 7.
        assertEquals(listOf(2, 3, 7), lines(table))
        assertTrue(table.wrongLength.isEmpty())
        assertEquals(0, table.unclosedQuoteLine)
    }

    @Test
    fun rowsEndAtCrlfLfOrALoneCr() {
        val table = Csv.read("h1,h2\ra,b\nc,d\r\ne,f")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d"), listOf("e", "f")), values(table))
        assertEquals(listOf(2, 3, 4), lines(table))
    }

    @Test
    fun excelsLeniencyIsKept() {
        // Text after a closing quote is kept; a quote inside an unquoted value is an ordinary character.
        val table = Csv.read("h1,h2,h3\n\"ab\"cd,ab\"cd,\"x\" \"y\"\n")
        assertEquals(listOf(listOf("abcd", "ab\"cd", "x \"y\"")), values(table))
        // A quote after a space is not an opening quote.
        assertEquals(listOf(" \"a\"", "b"), Csv.parse(" \"a\",b").rows.single().values)
    }

    @Test
    fun aQuoteThatIsNeverClosedIsReportedWithItsLine() {
        val text = "name,note\nBank,fine\nMail,\"open\nand more\nto the end"
        val table = Csv.read(text)
        assertEquals(3, table.unclosedQuoteLine)
        assertEquals(listOf("Mail", "open\nand more\nto the end"), table.rows[1].values)
        assertEquals(3, table.rows[1].line)
    }

    @Test
    fun blankLinesAreSkippedAndCountedButStillNumbered() {
        val table = Csv.read("\nname,url\n\n  \t\nBank,x\n\n")
        assertEquals(listOf("name", "url"), table.header!!.values)
        assertEquals(2, table.header.line)
        assertEquals(listOf(5), lines(table))
        assertEquals(4, table.blankLines)
        // An empty quoted value or a lone delimiter is a row, not a blank line.
        assertEquals(listOf(listOf(""), listOf("", "")), Csv.parse("\"\"\n,\n").rows.map { it.values })
    }

    @Test
    fun theDelimiterIsTheOneThatSplitsTheHeaderMost() {
        assertEquals(',', Csv.detectDelimiter("name,url,username;x\n"))
        assertEquals(';', Csv.detectDelimiter("Название;Сайт;Логин;Пароль;Примечание\r\n"))
        assertEquals('\t', Csv.detectDelimiter("a\tb\tc,d\n1\t2\t3\n"))
        assertEquals(';', Csv.detectDelimiter("\"a,b,c\";d;e\n")) // commas inside quotes do not count
        assertEquals(',', Csv.detectDelimiter("a,b;c\n")) // a tie goes to the comma
        assertEquals(',', Csv.detectDelimiter("single\n"))
        assertEquals(';', Csv.detectDelimiter("\n\na;b\n")) // the header is the first row that is not blank
        val table = Csv.read("Название;Сайт;Логин;Пароль\r\nПочта;mail.ru;иван;Пароль1\r\n")
        assertEquals(';', table.delimiter)
        assertEquals(Csv.DelimiterFrom.DETECTED, table.delimiterFrom)
        assertEquals(listOf(listOf("Почта", "mail.ru", "иван", "Пароль1")), values(table))
    }

    @Test
    fun excelsSepLineNamesTheDelimiterAndIsSkipped() {
        for (first in listOf("sep=;", "SEP=;", "\"sep=;\"")) {
            val table = Csv.read("$first\r\nname;url,with comma\r\nBank;https://bank.uz\r\n")
            assertEquals(first, ';', table.delimiter)
            assertEquals(first, Csv.DelimiterFrom.SEP_LINE, table.delimiterFrom)
            assertEquals(first, listOf("name", "url,with comma"), table.header!!.values)
            assertEquals(first, 2, table.header.line)
            assertEquals(first, listOf(3), lines(table))
        }
        assertEquals('\t', Csv.read("sep=\t\na\tb\n").delimiter)
        assertEquals('|', Csv.read("sep=|").delimiter)
        assertNull(Csv.read("sep=|").header)
        // Not a sep= line: more after the character, or a line that only starts like one.
        assertEquals(listOf("sep=;x", "y"), Csv.read("sep=;x,y\n1,2\n").header!!.values)
        assertEquals(Csv.DelimiterFrom.DETECTED, Csv.read("sep=;x,y\n1,2\n").delimiterFrom)
        assertEquals(listOf("sepa=;", "b"), Csv.read("sepa=;,b\n").header!!.values)
        // A byte-order mark before it does not hide it.
        assertEquals(';', Csv.read("\uFEFFsep=;\na;b\n").delimiter)
    }

    @Test
    fun aByteOrderMarkIsNotPartOfTheFirstValue() {
        assertEquals(listOf("name", "url"), Csv.read("\uFEFF\uFEFFname,url\n").header!!.values)
    }

    @Test
    fun rowsWithAnotherNumberOfValuesThanTheHeaderAreReported() {
        val table = Csv.read("a,b,c\n1,2,3\n1,2\n1,2,3,4\n\"x\ny\",2,3\n1\n")
        assertEquals(listOf(1, 2, 4), table.wrongLength.map { it.index })
        assertEquals(listOf(3, 4, 7), table.wrongLength.map { it.line })
        assertEquals(listOf(2, 4, 1), table.wrongLength.map { it.values })
        assertTrue(table.wrongLength.all { it.expected == 3 })
        assertEquals(5, table.rows.size) // reported, not dropped: the importer decides
    }

    @Test
    fun theRowLimitStopsTheReaderAndSaysThatMoreRowsFollow() {
        val text = "h\n1\n2\n3\n\n  \n"
        assertEquals(listOf(listOf("1"), listOf("2"), listOf("3")), values(Csv.read(text)))
        assertFalse(Csv.read(text).moreRows)
        val two = Csv.read(text, maxRows = 2)
        assertEquals(listOf(listOf("1"), listOf("2")), values(two))
        assertTrue(two.moreRows)
        assertFalse(Csv.read(text, maxRows = 3).moreRows) // only blank lines after the third row
        val none = Csv.read(text, maxRows = 0)
        assertEquals(listOf("h"), none.header!!.values)
        assertTrue(none.moreRows)
        assertEquals(1, Csv.parse("a\nb\n", maxRows = 1).rows.size)
        assertTrue(Csv.parse("a\nb\n", maxRows = 1).moreRows)
    }

    @Test
    fun aListInsideOneValueIsReadWithParse() {
        // Bitwarden's login_uri holds a CSV list of its own (formats.md §8.3).
        val urls = Csv.read("login_uri\n\"https://a.example,https://b.example\"\n").rows.single().values.single()
        assertEquals(listOf("https://a.example", "https://b.example"), Csv.parse(urls).rows.single().values)
    }

    @Test
    fun whatTheWriterWritesTheReaderReadsBack() {
        val random = Random(4_4)
        val pieces = listOf("a", "Ж", "🔑", "oʻ", ",", ";", "\t", "\"", "\"\"", " ", "\n", "\r\n", "\r", "=", "sep=;", "x")
        repeat(3_000) {
            val delimiter = Csv.DELIMITERS[random.nextInt(3)]
            val width = 2 + random.nextInt(4)
            val header = List(width) { "h$it" }
            val rows = List(random.nextInt(5)) {
                List(width) { (0 until random.nextInt(5)).joinToString("") { pieces[random.nextInt(pieces.size)] } }
            }
            val text = Csv.write(header, rows, listOf("\r\n", "\n")[random.nextInt(2)], delimiter)
            val table = Csv.read(text)
            assertEquals(text, delimiter, table.delimiter)
            assertEquals(text, header, table.header!!.values)
            assertEquals(text, rows, values(table))
            assertTrue(text, table.wrongLength.isEmpty())
            assertEquals(text, 0, table.unclosedQuoteLine)
        }
    }

    @Test
    fun aLargeFileIsReadQuickly() {
        val header = listOf("name", "url", "username", "password", "note")
        val rows = List(10_000) { i -> listOf("Bank $i", "https://bank$i.uz", "user$i", "p,a\"ss$i", "note $i\nline 2") }
        val text = Csv.write(header, rows)
        val start = System.nanoTime()
        val table = Csv.read(text)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(rows, values(table))
        assertTrue("$ms ms", ms < 10_000)
    }
}
