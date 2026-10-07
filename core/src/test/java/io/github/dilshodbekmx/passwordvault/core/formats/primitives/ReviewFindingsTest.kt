package io.github.dilshodbekmx.passwordvault.core.formats.primitives

import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Encoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Why
import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.util.StrictJson
import io.github.dilshodbekmx.passwordvault.core.util.TextHygiene
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One test per finding of formats-review.md that build-plan package 1.4 fixes, each built from the
 * finding's trigger at the level of the primitive. The end-to-end tests through `Importer.read`
 * belong to package 3.15.
 */
class ReviewFindingsTest {
    private val cp1251 = Charset.forName("windows-1251")

    /**
     * P2: one invalid byte switched the whole file to Windows-1251 and garbled every non-ASCII
     * password, silently. Now the file stays UTF-8, the bad byte becomes U+FFFD and its line is listed;
     * and a JSON file, which must be strict UTF-8, is refused with the offset of the bad byte.
     */
    @Test
    fun p2_oneStrayByteDoesNotSwitchAUtf8FileToWindows1251() {
        // The CSV trigger: a valid UTF-8 file with one stray 0xC3 at the end of the last line.
        val csv = ("name,url,username,password,note\n" +
            "Pochta,https://pochta.uz,ali,Пароль2026,izoh\n" +
            "Bank,https://bank.uz,ali,Oʻzbek‘1,\"ok\"\n" +
            "Last,https://x.example,u,p,trailing ").toByteArray() + byteArrayOf(0xC3.toByte())
        val decoded = TextDecoding.decode(csv)
        assertEquals(Encoding.UTF_8, decoded.encoding)
        assertEquals(Why.UTF8_SEQUENCES, decoded.why)
        assertEquals(1, decoded.replacedBytes)
        assertEquals(listOf(4), decoded.replacedLines)
        val rows = Csv.read(decoded.text).rows
        assertEquals(listOf("Пароль2026", "Oʻzbek‘1", "p"), rows.map { it.values[3] })
        assertEquals("trailing \uFFFD", rows[2].values[4])
        // What the prototype did instead (its decoder fell back to Windows-1251):
        assertEquals("РџР°СЂРѕР»СЊ2026", String("Пароль2026".toByteArray(), cp1251))

        // The JSON trigger: our own JSON with one byte of a Cyrillic letter in a note changed to 0xFF.
        val json = """{"encrypted":false,"items":[{"name":"Почта","notes":"Иккинчи қатор","login":{"password":"Пароль1"}}]}"""
        val bytes = json.toByteArray()
        val at = json.substring(0, json.indexOf("Иккинчи") + 2).toByteArray().size // the lead byte of "к"
        bytes[at] = 0xFF.toByte()
        val d = TextDecoding.decode(bytes)
        assertFalse(d.isStrictUtf8)
        assertEquals(Encoding.UTF_8, d.encoding) // not Windows-1251, so not "РџРѕС‡С‚Р°"
        assertEquals(at.toLong(), d.firstReplacedByte)
        val f = try {
            StrictJson.parseUtf8(bytes, 32)
            null
        } catch (e: StrictJson.Failure) {
            e
        }
        assertEquals(StrictJson.Reason.MALFORMED_UTF8, f!!.reason)
        assertEquals(at.toLong(), f.offset)
    }

    /**
     * P3: `{"items":` and about 2,000 `[` (a 4,076-byte file) overflowed kotlinx-serialization's
     * recursive reader on a thread with a 1 MB or 512 KB stack. StrictJson refuses it at the 33rd
     * level, on a 256 KB stack too, with its own exception.
     */
    @Test
    fun p3_nestedArraysAreRefusedWithoutOverflowingTheStack() {
        val n = 2_033
        val text = "{\"items\":" + "[".repeat(n) + "]".repeat(n) + "}"
        assertEquals(4_076, text.length)
        for (stack in listOf(512L * 1024, 256L * 1024)) {
            val thrown = onThread(stack) { StrictJson.parse(text, 32) }
            val f = thrown as StrictJson.Failure
            assertEquals(StrictJson.Reason.TOO_DEEP, f.reason)
            assertEquals(40L, f.offset) // the 32nd '[' would be the 33rd level: the object is level 1
            assertEquals("too-deep at offset 40", f.message)
        }
        // The same file is no problem at all within the limit's reach: 5,000 nested objects too.
        val objects = "{\"a\":".repeat(5_000) + "1" + "}".repeat(5_000)
        assertEquals(StrictJson.Reason.TOO_DEEP, (onThread(256L * 1024) { StrictJson.parse(objects, 32) } as StrictJson.Failure).reason)
        // The trigger is real: kotlinx-serialization's own reader overflows on it (a deeper copy, so
        // that the stack runs out whatever the JIT inlines).
        val deeper = "{\"items\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}"
        assertTrue(onThread(256L * 1024) { Json.parseToJsonElement(deeper) } is StackOverflowError)
    }

    /**
     * P4: rows with more or fewer values than the header were imported silently, with shifted or
     * missing passwords. The reader now reports each one with its line and both counts.
     */
    @Test
    fun p4_rowsWithMoreOrFewerValuesThanTheHeaderAreReported() {
        val text = "name,url,username,password,note\n" +
            "Gmail,https://mail.google.com,aziz,pa,ss,word,my note\n" + // an unquoted comma inside a password
            "Bank,https://bank.uz,ali\n" + // a short row
            "Mail,https://mail.ru,ivan,\"pa,ss\",ok\n"
        val table = Csv.read(text)
        assertEquals(2, table.wrongLength.size)
        val (long, short) = table.wrongLength
        assertEquals(listOf(0, 2, 7, 5), listOf(long.index, long.line, long.values, long.expected))
        assertEquals(listOf(1, 3, 3, 5), listOf(short.index, short.line, short.values, short.expected))
        // The rows are kept for the preview, which shows them and leaves them out unless confirmed.
        assertEquals(3, table.rows.size)
        assertEquals("pa,ss", table.rows[2].values[3])
    }

    /**
     * P7: lone surrogates. The writers produced three different results: `?` in CSV and JSON (the
     * UTF-8 encoder's), `_xD800_` in the `.xlsx` (cut by LibreOffice, shown literally by openpyxl),
     * and nothing reported. Now one function replaces each with U+FFFD and counts it, and the CSV and
     * `.xlsx` writers both use it.
     */
    @Test
    fun p7_unpairedSurrogatesBecomeU_FFFDAndAreCountedByEveryWriter() {
        // The trigger: title, username, password, notes, website, field name and field value.
        val values = listOf("T\uD800x", "u\uDC00", "p\uD83D", "n\uDE00\uD83Dz", "https://x.example/\uD800", "F\uD800", "v\uDFFF")
        val expected = listOf("T\uFFFDx", "u\uFFFD", "p\uFFFD", "n\uFFFD\uFFFDz", "https://x.example/\uFFFD", "F\uFFFD", "v\uFFFD")

        val hygiene = TextHygiene.Counter()
        assertEquals(expected, values.map { TextHygiene.replaceUnpairedSurrogates(it, hygiene) })
        assertEquals(8, hygiene.count)

        val csvCount = TextHygiene.Counter()
        val csv = Csv.write(List(values.size) { "c$it" }, listOf(values), replaced = csvCount)
        assertEquals(8, csvCount.count)
        val csvBytes = csv.toByteArray(Charsets.UTF_8)
        assertFalse(String(csvBytes, Charsets.UTF_8).contains('?'))
        assertEquals(expected, Csv.read(TextDecoding.decode(csvBytes).text).rows.single().values)

        val out = ByteArrayOutputStream()
        val stats = Xlsx.write(out, "Passwords", List(values.size) { Xlsx.Column("c$it", 10.0) }, listOf(values), "PasswordVault")
        assertEquals(8, stats.replacedSurrogates)
        val book = XlsxTest.Book.read(out.toByteArray())
        assertEquals(expected, book.rows()[1].map { it.value })
        val sst = String(book.parts.getValue("xl/sharedStrings.xml"), Charsets.UTF_8)
        assertFalse(sst.contains("_xD800_") || sst.contains("_xDC00_") || sst.contains("_xD83D_"))
    }

    /**
     * P9: the `.xlsx` bytes depended on the time zone and told the phone's UTC offset, because
     * `ZipEntry.setTime` turned the prototype's fixed instant into 12:00, 17:00 or 07:00 local. With a
     * fixed local time the bytes are identical under UTC, Asia/Tashkent and America/New_York.
     */
    @Test
    fun p9_theXlsxBytesDoNotDependOnTheTimeZone() {
        val zones = listOf("UTC", "Asia/Tashkent", "America/New_York")
        val rows = listOf(listOf("Gmail", "aziz@gmail.com", "p\"ss🔑"), listOf("Bank", "+998901234567", "Oʻzbek"))
        val columns = listOf(Xlsx.Column("Name", 24.0), Xlsx.Column("Username", 26.0), Xlsx.Column("Password", 22.0))
        val saved = TimeZone.getDefault()
        try {
            val files = zones.map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val out = ByteArrayOutputStream()
                Xlsx.write(out, "Passwords", columns, rows, "PasswordVault — private")
                out.toByteArray()
            }
            assertArrayEquals(files[0], files[1])
            assertArrayEquals(files[0], files[2])
            for (entry in XlsxTest.ZipHeaders.read(files[0])) {
                assertEquals(0x0021, entry.localDate) // 1980-01-01
                assertEquals(0x0000, entry.localTime) // 00:00
                assertEquals(0, entry.localExtra) // no extended time stamp
            }
            // The prototype's way, for contrast: a fixed instant gives one file per zone.
            val prototype = zones.map { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                val out = ByteArrayOutputStream()
                ZipOutputStream(out).use { zip ->
                    zip.putNextEntry(ZipEntry("x").apply { time = 946_728_000_000L })
                    zip.write(1)
                    zip.closeEntry()
                }
                out.toByteArray()
            }
            assertNotEquals(prototype[0].toList(), prototype[1].toList())
            assertNotEquals(prototype[0].toList(), prototype[2].toList())
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    /**
     * P12: the byte-order-mark branches decoded leniently and hid damaged bytes (a UTF-8 mark in
     * front of a Windows-1251 body gave six U+FFFD and a `1` for `Пароль1`, and no problem), and
     * UTF-16 without a mark was refused as binary. Now every branch reports what it replaced, and
     * UTF-16 without a mark is read.
     */
    @Test
    fun p12_theMarkBranchesReportDamageAndUtf16WithoutAMarkIsRead() {
        val body = "name,url,username,password,note\r\nPochta,https://pochta.uz,ali,Пароль1,izoh\r\n"
        val trigger = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.toByteArray(cp1251)
        val d = TextDecoding.decode(trigger)
        assertEquals(Encoding.UTF_8, d.encoding)
        assertEquals(Why.BYTE_ORDER_MARK, d.why)
        assertEquals(6, d.replacedBytes) // reported, with its line
        assertEquals(listOf(2), d.replacedLines)
        assertEquals("\uFFFD".repeat(6) + "1", Csv.read(d.text).rows.single().values[3])
        // The preview's "Read as Windows-1251" then gives the right text.
        assertEquals("Пароль1", Csv.read(TextDecoding.decode(trigger, Encoding.WINDOWS_1251).text).rows.single().values[3])
        // A damaged UTF-16 file with a mark is reported too.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "ok\n".toByteArray(Charsets.UTF_16LE) + byteArrayOf(0x00, 0xD8.toByte()) +
            "x\n".toByteArray(Charsets.UTF_16LE)
        val u = TextDecoding.decode(utf16)
        assertEquals(2, u.replacedBytes)
        assertEquals(listOf(2), u.replacedLines)

        // UTF-16LE (and BE) without a mark: read, not refused.
        for ((charset, encoding) in listOf(Charsets.UTF_16LE to Encoding.UTF_16LE, Charsets.UTF_16BE to Encoding.UTF_16BE)) {
            val plain = TextDecoding.decode(body.toByteArray(charset))
            assertEquals(encoding, plain.encoding)
            assertEquals(Why.NUL_BYTES, plain.why)
            assertEquals(body, plain.text)
            assertEquals(0, plain.replacedBytes)
        }
    }

    /** P13 (the `sep=` line): a first line `sep=;`, which Excel writes, gave "no known columns". */
    @Test
    fun p13_excelsSepLineNamesTheDelimiterAndIsSkipped() {
        val text = "sep=;\r\nname;url;username;password;note\r\nBank;https://bank.uz;ali;pa,ss;Oʻzbek\r\n"
        val table = Csv.read(text)
        assertEquals(';', table.delimiter)
        assertEquals(Csv.DelimiterFrom.SEP_LINE, table.delimiterFrom)
        assertEquals(listOf("name", "url", "username", "password", "note"), table.header!!.values)
        assertEquals(listOf("Bank", "https://bank.uz", "ali", "pa,ss", "Oʻzbek"), table.rows.single().values)
        assertEquals(3, table.rows.single().line) // lines count from the sep= line
        assertTrue(table.wrongLength.isEmpty())
        // Without the line the delimiter would have been a guess; here the comma in "pa,ss" makes
        // the header and the row disagree under ','.
        assertEquals(',', Csv.detectDelimiter("name;url;x,y,z\n"))
    }

    private fun onThread(stackBytes: Long, block: () -> Any?): Throwable? {
        var thrown: Throwable? = null
        val thread = Thread(null, {
            try {
                block()
            } catch (t: Throwable) {
                thrown = t
            }
        }, "finding-test", stackBytes)
        thread.start()
        thread.join()
        return thrown
    }
}
