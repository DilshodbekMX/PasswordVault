package io.github.dilshodbekmx.passwordvault.core.formats.primitives

import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element

class XlsxTest {
    private val columns = listOf(Xlsx.Column("Name", 24.0), Xlsx.Column("Password", 22.0), Xlsx.Column("Notes", 40.0))

    private fun write(rows: List<List<String?>>, cols: List<Xlsx.Column> = columns, pageHeader: String = "PasswordVault — private"): Pair<ByteArray, Xlsx.Stats> {
        val out = ByteArrayOutputStream()
        val stats = Xlsx.write(out, "Passwords", cols, rows, pageHeader)
        return out.toByteArray() to stats
    }

    @Test
    fun sevenPartsInSchemaOrderEachWellFormed() {
        val book = Book.read(write(listOf(listOf("Bank", "s3cret", "note"))).first)
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/sharedStrings.xml"),
            book.names,
        )
        for (name in book.names) book.xml(name) // throws if not well-formed
        val workbook = book.xml("xl/workbook.xml").documentElement
        assertEquals("Passwords", (workbook.getElementsByTagNameNS(NS, "sheet").item(0) as Element).getAttribute("name"))
        val names = workbook.getElementsByTagNameNS(NS, "definedName")
        assertEquals("'Passwords'!\$A\$1:\$C\$2", names.item(0).textContent)
        assertEquals("'Passwords'!\$1:\$1", names.item(1).textContent)
    }

    @Test
    fun everyCellIsASharedStringAndReadsBackAsWritten() {
        val rows = listOf(
            listOf("Oʻzbekiston banki", "Пароль‘2026 🔑", "first line\r\nsecond\rthird"),
            listOf("=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "007"),
            listOf("<a&b> \"quoted\" 'single'", "", null),
            listOf("בנק / البنك", "-2+3", "@SUM(A1:A2)", "ignored: beyond the last column"),
            listOf("Oʻzbekiston banki"), // a repeated string is one shared string
        )
        val (bytes, stats) = write(rows)
        val book = Book.read(bytes)
        val expected = listOf(
            listOf("Name", "Password", "Notes"),
            listOf("Oʻzbekiston banki", "Пароль‘2026 🔑", "first line\nsecond\nthird"),
            listOf("=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "007"),
            listOf("<a&b> \"quoted\" 'single'"),
            listOf("בנק / البنك", "-2+3", "@SUM(A1:A2)"),
            listOf("Oʻzbekiston banki"),
        )
        assertEquals(expected, book.rows().map { r -> r.map { it.value } })
        assertEquals(listOf("A1", "B1", "C1"), book.rows()[0].map { it.ref })
        assertTrue(book.rows()[0].all { it.style == "1" })
        assertTrue(book.rows().drop(1).flatten().all { it.style == "2" })
        val sheet = String(book.parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8)
        assertFalse(sheet.contains("<f>"))
        assertFalse(sheet.contains("inlineStr"))
        assertFalse(sheet.contains("t=\"str\""))
        assertEquals(5, stats.rows)
        // 3 headers + 3 + 3 + 1 + 3 values; the last row repeats the first value.
        assertEquals(13, stats.sharedStrings)
        assertEquals(0, stats.truncatedCells)
        val sst = book.xml("xl/sharedStrings.xml").documentElement
        assertEquals("14", sst.getAttribute("count"))
        assertEquals("13", sst.getAttribute("uniqueCount"))
    }

    @Test
    fun charactersXmlCannotCarryAreEscapedAndLiteralEscapesSurvive() {
        val values = listOf(
            "bell\u0007 unit\u001F nul\u0000",
            "nonchars \uFFFE\uFFFF",
            "literal _x0041_ and _x005F_ and _X0041_",
            "_x0041\u0001", // an underscore that an escape after it would turn into one
            "x005F_ _x004 _xZZZZ_ tab\there",
        )
        val (bytes, _) = write(values.map { listOf(it) }, listOf(Xlsx.Column("Value", 30.0)))
        val book = Book.read(bytes)
        assertEquals(values, book.rows().drop(1).map { it.single().value })
        val sst = String(book.parts.getValue("xl/sharedStrings.xml"), Charsets.UTF_8)
        assertTrue(sst.contains("bell_x0007_ unit_x001F_ nul_x0000_"))
        assertTrue(sst.contains("_x005F_x0041_ and _x005F_x005F_ and _X0041_"))
        assertTrue(sst.contains("_x005F_x0041_x0001_"))
        assertTrue(sst.contains("_xFFFE__xFFFF_"))
    }

    @Test
    fun unpairedSurrogatesBecomeTheReplacementCharacterAndAreCounted() {
        val (bytes, stats) = write(listOf(listOf("T\uD800x", "p\uD83D", "n\uDE00\uD83Dz 🔑")))
        assertEquals(4, stats.replacedSurrogates)
        val book = Book.read(bytes)
        assertEquals(listOf("T\uFFFDx", "p\uFFFD", "n\uFFFD\uFFFDz 🔑"), book.rows()[1].map { it.value })
        val sst = String(book.parts.getValue("xl/sharedStrings.xml"), Charsets.UTF_8)
        assertFalse(sst.contains("_xD800_"))
        assertFalse(sst.substringAfter("?>").contains("?")) // no '?' where a surrogate was (after the XML declaration)
        // The escape function on its own, for text that did not go through TextHygiene.
        assertEquals("a\uFFFDb🔑", Xlsx.text("a\uDC00b🔑"))
    }

    @Test
    fun theSameInputGivesTheSameBytes() {
        val rows = List(200) { listOf("Account $it", "pw-$it-🔑", "note $it") }
        assertArrayEquals(write(rows).first, write(rows).first)
    }

    @Test
    fun everyZipEntryCarriesTheFixedLocalTimeAndNoExtraField() {
        val entries = ZipHeaders.read(write(listOf(listOf("a", "b", "c"))).first)
        assertEquals(7, entries.size)
        for (e in entries) {
            assertEquals(e.name, 0x0000, e.localTime)
            assertEquals(e.name, 0x0021, e.localDate) // 1980-01-01
            assertEquals(e.name, 0x0000, e.centralTime)
            assertEquals(e.name, 0x0021, e.centralDate)
            assertEquals(e.name, 0, e.localExtra)
            assertEquals(e.name, 0, e.centralExtra)
        }
    }

    @Test
    fun aCellOverExcelsLimitIsCutWithANote() {
        val long = "o‘zbek 🔑 ".repeat(5_000) // 50,000 characters with surrogate pairs all along
        val (bytes, stats) = write(listOf(listOf("Long", "", long)))
        assertEquals(1, stats.truncatedCells)
        val cell = Book.read(bytes).rows()[1].last().value
        assertTrue(cell.length <= Xlsx.MAX_CELL_CHARS)
        assertTrue(cell.endsWith(" more characters: Excel shows at most 32,767 in one cell]"))
        assertFalse(TextHygiene.hasUnpairedSurrogate(cell))
        val kept = cell.substringBefore("\n[…")
        assertTrue(long.startsWith(kept))
        assertTrue(cell.contains("[… ${long.length - kept.length} more characters"))
        assertEquals("x", Xlsx.truncate("x", Xlsx.DEFAULT_TRUNCATION_NOTE))
    }

    @Test
    fun lineFeedsAfterThe253rdBecomeArrows() {
        val note = (1..300).joinToString("\n") { "code $it" }
        val cell = Book.read(write(listOf(listOf("Codes", "", note))).first).rows()[1].last().value
        assertEquals(253, cell.count { it == '\n' })
        assertEquals(300 - 1 - 253, Regex(" ↵ ").findAll(cell).count())
        assertEquals(note.replace("\n", ""), cell.replace("\n", "").replace(" ↵ ", ""))
    }

    @Test
    fun sheetNamesFollowExcelsRules() {
        for (good in listOf("Passwords", "Пароли", "Parollar", "Paroʻllar", "a".repeat(31), "It's mine")) assertTrue(good, Xlsx.isValidSheetName(good))
        for (bad in listOf("", "a".repeat(32), "a[b", "a]b", "a:b", "a*b", "a?b", "a/b", "a\\b", "'quoted", "quoted'", "History", "history", "x\uD800")) {
            assertFalse(bad, Xlsx.isValidSheetName(bad))
            val e = assertThrows(IllegalArgumentException::class.java) { Xlsx.write(ByteArrayOutputStream(), bad, columns, emptyList(), "") }
            assertEquals("not a valid sheet name", e.message)
        }
        assertThrows(IllegalArgumentException::class.java) { Xlsx.write(ByteArrayOutputStream(), "Passwords", emptyList(), emptyList(), "") }
    }

    @Test
    fun thePageHeaderFitsExcelsLimitWithItsAmpersandsDoubled() {
        fun header(pageHeader: String): String {
            val book = Book.read(write(listOf(listOf("a")), pageHeader = pageHeader).first)
            return book.xml("xl/worksheets/sheet1.xml").getElementsByTagNameNS(NS, "oddHeader").item(0).textContent
        }
        assertEquals("&LPasswordVault — private&R&D", header("PasswordVault — private"))
        assertEquals("&LA&&B&R&D", header("A&B"))
        val long = header("&".repeat(300))
        assertEquals(Xlsx.MAX_HEADER_FOOTER_CHARS - 1, long.length) // 124 pairs of && fit in 249 places
        assertTrue(long.startsWith("&L&&") && long.endsWith("&&&R&D"))
        assertTrue(header("🔑".repeat(200)).length <= Xlsx.MAX_HEADER_FOOTER_CHARS)
    }

    @Test
    fun columnLetters() {
        assertEquals(listOf("A", "B", "Z", "AA", "AZ", "BA", "ZZ", "AAA", "XFD"), listOf(0, 1, 25, 26, 51, 52, 701, 702, 16_383).map { Xlsx.colName(it) })
    }

    @Test
    fun anEmptySheetHasOnlyItsHeader() {
        val (bytes, stats) = write(emptyList())
        val book = Book.read(bytes)
        assertEquals(listOf(listOf("Name", "Password", "Notes")), book.rows().map { r -> r.map { it.value } })
        assertEquals(0, stats.rows)
        assertTrue(String(book.parts.getValue("xl/worksheets/sheet1.xml"), Charsets.UTF_8).contains("<dimension ref=\"A1:C1\"/>"))
    }

    // ------------------------------------------------------------------ a small reader of our own

    class Cell(val ref: String, val style: String, val value: String)

    class Book(val names: List<String>, val parts: Map<String, ByteArray>) {
        fun xml(name: String): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(parts.getValue(name)))

        /** The shared strings, with OOXML's `_xHHHH_` escapes decoded as Excel and Apache POI do. */
        private val shared: List<String> by lazy {
            val items = xml("xl/sharedStrings.xml").documentElement.getElementsByTagNameNS(NS, "si")
            List(items.length) { decode(items.item(it).textContent) }
        }

        fun rows(): List<List<Cell>> {
            val rows = xml("xl/worksheets/sheet1.xml").documentElement.getElementsByTagNameNS(NS, "row")
            return List(rows.length) { r ->
                val cells = (rows.item(r) as Element).getElementsByTagNameNS(NS, "c")
                List(cells.length) { c ->
                    val cell = cells.item(c) as Element
                    assertEquals("s", cell.getAttribute("t"))
                    val index = cell.getElementsByTagNameNS(NS, "v").item(0).textContent.toInt()
                    Cell(cell.getAttribute("r"), cell.getAttribute("s"), shared[index])
                }
            }
        }

        companion object {
            fun read(bytes: ByteArray): Book {
                val names = ArrayList<String>()
                val parts = LinkedHashMap<String, ByteArray>()
                ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        names.add(entry.name)
                        parts[entry.name] = zip.readBytes()
                    }
                }
                return Book(names, parts)
            }

            fun decode(s: String) = Regex("_x([0-9A-Fa-f]{4})_").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
        }
    }

    /** The DOS times and extra fields of each entry, from the local headers and the central directory. */
    class ZipHeaders(val name: String, val localTime: Int, val localDate: Int, val localExtra: Int, val centralTime: Int, val centralDate: Int, val centralExtra: Int) {
        companion object {
            private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
            private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

            fun read(zip: ByteArray): List<ZipHeaders> {
                var end = zip.size - 22
                while (u32(zip, end) != 0x06054b50) end--
                val count = u16(zip, end + 10)
                var at = u32(zip, end + 16)
                return List(count) {
                    assertEquals(0x02014b50, u32(zip, at))
                    val nameLength = u16(zip, at + 28)
                    val extra = u16(zip, at + 30)
                    val comment = u16(zip, at + 32)
                    val local = u32(zip, at + 42)
                    assertEquals(0x04034b50, u32(zip, local))
                    val entry = ZipHeaders(
                        String(zip, at + 46, nameLength, Charsets.UTF_8),
                        u16(zip, local + 10), u16(zip, local + 12), u16(zip, local + 28),
                        u16(zip, at + 12), u16(zip, at + 14), extra,
                    )
                    at += 46 + nameLength + extra + comment
                    entry
                }
            }
        }
    }

    companion object {
        const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }
}
