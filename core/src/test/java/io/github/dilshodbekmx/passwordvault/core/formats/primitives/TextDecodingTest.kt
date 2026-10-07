package io.github.dilshodbekmx.passwordvault.core.formats.primitives

import io.github.dilshodbekmx.passwordvault.core.formats.Csv
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Encoding
import io.github.dilshodbekmx.passwordvault.core.formats.TextDecoding.Why
import java.nio.charset.Charset
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecodingTest {
    private val cp1251 = Charset.forName("windows-1251")
    private val sample = "name,url,username,password,note\r\nPochta,https://pochta.uz,ali,Пароль2026,izoh «muhim»\r\n" +
        "Bank,https://bank.uz,ali,Oʻzbek‘1,\"ok 🔑\"\r\n"

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun validUtf8IsUtf8() {
        val d = TextDecoding.decode(sample.toByteArray())
        assertEquals(sample, d.text)
        assertEquals(Encoding.UTF_8, d.encoding)
        assertEquals(Why.VALID_UTF8, d.why)
        assertEquals(0, d.replacedBytes)
        assertEquals(0, d.byteOrderMarks)
        assertEquals(-1L, d.firstReplacedByte)
        assertTrue(d.isStrictUtf8)
        assertEquals(Why.VALID_UTF8, TextDecoding.decode("plain ascii".toByteArray()).why)
        assertEquals("", TextDecoding.decode(ByteArray(0)).text)
    }

    @Test
    fun aUtf8MarkNamesUtf8AndEveryLeadingMarkIsRemoved() {
        val one = TextDecoding.decode(bytes(0xEF, 0xBB, 0xBF) + sample.toByteArray())
        assertEquals(sample, one.text)
        assertEquals(Why.BYTE_ORDER_MARK, one.why)
        assertEquals(1, one.byteOrderMarks)
        assertTrue(one.isStrictUtf8)
        val two = TextDecoding.decode(bytes(0xEF, 0xBB, 0xBF, 0xEF, 0xBB, 0xBF) + "{}".toByteArray())
        assertEquals("{}", two.text)
        assertEquals(2, two.byteOrderMarks)
        // A U+FEFF after the start is text.
        assertEquals("a\uFEFF", TextDecoding.decode("a\uFEFF".toByteArray()).text)
    }

    @Test
    fun utf16WithAMark() {
        for ((charset, mark, encoding) in listOf(
            Triple(Charsets.UTF_16LE, bytes(0xFF, 0xFE), Encoding.UTF_16LE),
            Triple(Charsets.UTF_16BE, bytes(0xFE, 0xFF), Encoding.UTF_16BE),
        )) {
            val d = TextDecoding.decode(mark + sample.toByteArray(charset))
            assertEquals(sample, d.text)
            assertEquals(encoding, d.encoding)
            assertEquals(Why.BYTE_ORDER_MARK, d.why)
            assertEquals(1, d.byteOrderMarks)
            assertEquals(0, d.replacedBytes)
            assertFalse(d.isStrictUtf8)
        }
    }

    @Test
    fun utf16WithoutAMarkIsRecognisedByItsNulBytes() {
        val cyrillicHeavy = "Номи;Сайт;Фойдаланувчи;Парол;Изоҳ\r\n" + "Почта;pochta.uz;али;Пароль2026;эслатма\r\n".repeat(30)
        for (text in listOf(sample, cyrillicHeavy)) {
            val le = TextDecoding.decode(text.toByteArray(Charsets.UTF_16LE))
            assertEquals(text, le.text)
            assertEquals(Encoding.UTF_16LE, le.encoding)
            assertEquals(Why.NUL_BYTES, le.why)
            val be = TextDecoding.decode(text.toByteArray(Charsets.UTF_16BE))
            assertEquals(text, be.text)
            assertEquals(Encoding.UTF_16BE, be.encoding)
        }
        // One NUL byte in a UTF-8 file does not make it UTF-16.
        val oneNul = "name,url\nBank,x\u0000y\n"
        assertEquals(Encoding.UTF_8, TextDecoding.decode(oneNul.toByteArray()).encoding)
        assertEquals(oneNul, TextDecoding.decode(oneNul.toByteArray()).text)
        // NUL bytes at both parities are not UTF-16 either.
        assertEquals(Encoding.UTF_8, TextDecoding.decode(ByteArray(64)).encoding)
    }

    @Test
    fun utf16ReportsUnpairedSurrogatesAndAnOddLastByte() {
        val good = "a🔑b".toByteArray(Charsets.UTF_16LE) // 61 00 3D D8 11 DD 62 00
        val loneHigh = bytes(0xFF, 0xFE) + bytes(0x61, 0x00, 0x3D, 0xD8, 0x62, 0x00)
        val d = TextDecoding.decode(loneHigh)
        assertEquals("a\uFFFDb", d.text)
        assertEquals(2, d.replacedBytes)
        assertEquals(4L, d.firstReplacedByte)
        val loneLow = TextDecoding.decode(bytes(0xFE, 0xFF) + bytes(0xDD, 0x11, 0x00, 0x61))
        assertEquals("\uFFFDa", loneLow.text)
        val odd = TextDecoding.decode(bytes(0xFF, 0xFE) + good + bytes(0x41))
        assertEquals("a🔑b\uFFFD", odd.text)
        assertEquals(1, odd.replacedBytes)
    }

    @Test
    fun utf8WithBadBytesStaysUtf8WhenMultiByteSequencesOccur() {
        val bad = sample.toByteArray() + "Last,https://x.example,u,p,trailing ".toByteArray() + bytes(0xC3)
        val d = TextDecoding.decode(bad)
        assertEquals(Encoding.UTF_8, d.encoding)
        assertEquals(Why.UTF8_SEQUENCES, d.why)
        assertEquals(sample + "Last,https://x.example,u,p,trailing \uFFFD", d.text)
        assertEquals(1, d.replacedBytes)
        assertEquals(listOf(4), d.replacedLines)
        assertEquals(1, d.replacedLineCount)
        assertEquals((bad.size - 1).toLong(), d.firstReplacedByte)
        assertFalse(d.isStrictUtf8)
    }

    @Test
    fun windows1251OnlyWhenNoMultiByteUtf8SequenceOccurs() {
        val text = "Название;Сайт;Логин;Пароль;Примечание\r\nПочта;mail.ru;иван;Пароль1;заметка «важно» №5 — ёж\r\n"
        val d = TextDecoding.decode(text.toByteArray(cp1251))
        assertEquals(text, d.text)
        assertEquals(Encoding.WINDOWS_1251, d.encoding)
        assertEquals(Why.NO_UTF8_SEQUENCES, d.why)
        assertEquals(0, d.replacedBytes)
    }

    @Test
    fun theWindows1251TableIsTheJdks() {
        val all = ByteArray(256) { it.toByte() }
        val jdk = String(all, cp1251) // 0x98 is unassigned; the JDK gives U+FFFD for it
        val ours = TextDecoding.decode(all, Encoding.WINDOWS_1251)
        assertEquals(jdk, ours.text)
        assertEquals('\uFFFD', ours.text[0x98])
        assertEquals(1, ours.replacedBytes)
        assertEquals(0x98L, ours.firstReplacedByte)
    }

    @Test
    fun wellFormedTextDecodesAsTheJdkDecodesIt() {
        val random = Random(8_1)
        val units = intArrayOf('a'.code, ' '.code, '\n'.code, 0x7F, 0x80, 0x7FF, 0x800, 0x416, 0x2BB, 0xFFFD, 0xFFFF, 0xE000)
        repeat(20_000) {
            val sb = StringBuilder()
            repeat(random.nextInt(8)) {
                if (random.nextInt(5) == 0) sb.appendCodePoint(0x10000 + random.nextInt(0x100000)) else sb.append(units[random.nextInt(units.size)].toChar())
            }
            val text = sb.toString()
            assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_8), Encoding.UTF_8).text)
            assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16LE), Encoding.UTF_16LE).text)
            assertEquals(text, TextDecoding.decode(text.toByteArray(Charsets.UTF_16BE), Encoding.UTF_16BE).text)
        }
    }

    @Test
    fun eachMaximalSubpartOfABadUtf8SequenceIsOneReplacement() {
        // The Unicode Standard's recommendation (§3.9, "U+FFFD Substitution of Maximal Subparts"),
        // modelled on its Table 3-7 of well-formed byte sequences. The JDK is not the oracle here: it
        // replaces an encoded surrogate (ED A0 80) with one U+FFFD where the recommendation gives three.
        val table = listOf(
            listOf(0x00..0x7F),
            listOf(0xC2..0xDF, 0x80..0xBF),
            listOf(0xE0..0xE0, 0xA0..0xBF, 0x80..0xBF),
            listOf(0xE1..0xEC, 0x80..0xBF, 0x80..0xBF),
            listOf(0xED..0xED, 0x80..0x9F, 0x80..0xBF),
            listOf(0xEE..0xEF, 0x80..0xBF, 0x80..0xBF),
            listOf(0xF0..0xF0, 0x90..0xBF, 0x80..0xBF, 0x80..0xBF),
            listOf(0xF1..0xF3, 0x80..0xBF, 0x80..0xBF, 0x80..0xBF),
            listOf(0xF4..0xF4, 0x80..0x8F, 0x80..0xBF, 0x80..0xBF),
        )
        fun model(b: ByteArray): Pair<String, Int> {
            val sb = StringBuilder()
            var bad = 0
            var i = 0
            while (i < b.size) {
                // The longest k for which b[i, i + k) starts some row of the table.
                var best = 0
                var whole: List<IntRange>? = null
                for (row in table) {
                    var k = 0
                    while (k < row.size && i + k < b.size && (b[i + k].toInt() and 0xFF) in row[k]) k++
                    if (k > best) best = k
                    if (k == row.size) whole = row
                }
                if (whole != null) {
                    sb.append(String(b, i, whole.size, Charsets.UTF_8))
                    i += whole.size
                } else {
                    val n = maxOf(best, 1)
                    sb.append('\uFFFD')
                    bad += n
                    i += n
                }
            }
            return sb.toString().trimStart('\uFEFF') to bad
        }
        val random = Random(8_2)
        val pieces = intArrayOf('a'.code, '\n'.code, '\r'.code, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBB, 0xBF, 0xC0, 0xC1, 0xC2, 0xDF,
            0xE0, 0xE1, 0xEC, 0xED, 0xEE, 0xEF, 0xF0, 0xF1, 0xF3, 0xF4, 0xF5, 0xFF)
        repeat(50_000) {
            val b = ByteArray(random.nextInt(10)) { pieces[random.nextInt(pieces.size)].toByte() }
            val ours = TextDecoding.decode(b, Encoding.UTF_8)
            val (text, bad) = model(if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) b.copyOfRange(3, b.size) else b)
            assertEquals(text, ours.text)
            assertEquals(bad, ours.replacedBytes)
        }
        // The examples of the Unicode Standard's Table 3-8 and the encoded surrogate.
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFD\uFFFDA", TextDecoding.decode(bytes(0xC0, 0xAF, 0xE0, 0x80, 0xBF, 0xF0, 0x81, 0x82, 0x41), Encoding.UTF_8).text)
        assertEquals("\uFFFD\uFFFD\uFFFD\uFFFD\uFFFDA", TextDecoding.decode(bytes(0xED, 0xA0, 0x80, 0xED, 0xBF, 0x41), Encoding.UTF_8).text)
        assertEquals("\uFFFD\uFFFD\uFFFD", TextDecoding.decode(bytes(0xED, 0xA0, 0x80), Encoding.UTF_8).text)
        assertEquals("\uFFFDA", TextDecoding.decode(bytes(0xF4, 0x80, 0x80, 0x41), Encoding.UTF_8).text)
    }

    @Test
    fun eachUnpairedUtf16UnitIsOneReplacement() {
        // As WHATWG's decoder: a unit that is not part of a pair is one U+FFFD, and the unit after it is
        // read again (the JDK's decoder swallows it); an odd last byte is one U+FFFD.
        val random = Random(16)
        val units = intArrayOf(0x0041, 0x0416, 0xD83D, 0xDD11, 0xD800, 0xDFFF, 0x000A, 0x000D, 0xFFFD)
        repeat(20_000) {
            val text = String(CharArray(random.nextInt(8)) { units[random.nextInt(units.size)].toChar() })
            val odd = random.nextInt(3) == 0
            val expected = StringBuilder()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])) {
                    expected.append(c).append(text[i + 1])
                    i += 2
                } else {
                    expected.append(if (Character.isSurrogate(c)) '\uFFFD' else c)
                    i++
                }
            }
            if (odd) expected.append('\uFFFD')
            for (encoding in listOf(Encoding.UTF_16LE, Encoding.UTF_16BE)) {
                val b = ByteArray(text.length * 2 + if (odd) 1 else 0) { 0x41 }
                text.forEachIndexed { k, c ->
                    val hi = (c.code shr 8).toByte()
                    val lo = c.code.toByte()
                    b[2 * k] = if (encoding == Encoding.UTF_16LE) lo else hi
                    b[2 * k + 1] = if (encoding == Encoding.UTF_16LE) hi else lo
                }
                assertEquals(expected.toString(), TextDecoding.decode(b, encoding).text)
            }
        }
    }

    @Test
    fun replacedBytesAreListedByLineAsCsvNumbersItsRows() {
        val text = "name,note Ж\r\n" + "a,\"one\ntwo X\"\r\n" + "b,c\rd,eX\n"
        val raw = text.toByteArray()
        raw[raw.indexOf('X'.code.toByte())] = 0xFF.toByte()
        raw[raw.lastIndexOf('X'.code.toByte())] = 0xFE.toByte()
        val d = TextDecoding.decode(raw)
        assertEquals(Why.UTF8_SEQUENCES, d.why)
        assertEquals(listOf(3, 5), d.replacedLines)
        val rows = Csv.read(d.text).rows
        assertEquals(listOf(2, 4, 5), rows.map { it.line })
        // The row of line 3 is the one that started on line 2 and runs over two lines.
        assertTrue(rows[0].values[1].contains('\uFFFD'))
        assertTrue(rows[2].values[1].contains('\uFFFD'))
    }

    @Test
    fun atMostAThousandLinesAreListedButAllAreCounted() {
        val raw = ("ok Ж\n" + "bad ").toByteArray() + bytes(0xFF) + "\n".toByteArray()
        val many = ByteArray(0).let { var all = it; repeat(2_000) { all += raw }; all }
        val d = TextDecoding.decode(many)
        assertEquals(Why.UTF8_SEQUENCES, d.why)
        assertEquals(TextDecoding.MAX_LISTED_LINES, d.replacedLines.size)
        assertEquals(2_000, d.replacedLineCount)
        assertEquals(2_000, d.replacedBytes)
        assertEquals((2..2_000).step(2).toList().take(5), d.replacedLines.take(5))
    }

    @Test
    fun theCallerCanChooseTheEncoding() {
        val body = "name,password\r\nPochta,Пароль1\r\n"
        val withUtf8Mark = bytes(0xEF, 0xBB, 0xBF) + body.toByteArray(cp1251)
        val chosen = TextDecoding.decode(withUtf8Mark, Encoding.WINDOWS_1251)
        assertEquals(body, chosen.text)
        assertEquals(Why.CHOSEN, chosen.why)
        assertEquals(1, chosen.byteOrderMarks)
        val asUtf8 = TextDecoding.decode(body.toByteArray(cp1251), Encoding.UTF_8)
        assertEquals(Why.CHOSEN, asUtf8.why)
        assertEquals(6, asUtf8.replacedBytes) // the six Cyrillic letters, one replacement each
        assertEquals(listOf(2), asUtf8.replacedLines)
        assertEquals(body, TextDecoding.decode(bytes(0xFF, 0xFE) + body.toByteArray(Charsets.UTF_16LE), Encoding.UTF_16LE).text)
        assertEquals(body, TextDecoding.decode(body.toByteArray(Charsets.UTF_16BE), Encoding.UTF_16BE).text)
    }
}
