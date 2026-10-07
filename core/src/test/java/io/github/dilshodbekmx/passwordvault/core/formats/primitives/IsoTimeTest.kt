package io.github.dilshodbekmx.passwordvault.core.formats.primitives

import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `java.time` is the oracle here: tests run on the JVM, where it exists (the app's code may not use it). */
class IsoTimeTest {
    private val iso = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    @Test
    fun formatWritesUtcWithMilliseconds() {
        assertEquals("1970-01-01T00:00:00.000Z", IsoTime.format(0))
        assertEquals("1969-12-31T23:59:59.999Z", IsoTime.format(-1))
        assertEquals("2026-10-06T07:05:09.123Z", IsoTime.format(Instant.parse("2026-10-06T07:05:09.123Z").toEpochMilli()))
        assertEquals("2000-02-29T12:00:00.000Z", IsoTime.format(Instant.parse("2000-02-29T12:00:00Z").toEpochMilli()))
        assertEquals("9999-12-31T23:59:59.999Z", IsoTime.format(Instant.parse("9999-12-31T23:59:59.999Z").toEpochMilli()))
        assertEquals("0000-01-01T00:00:00.000Z", IsoTime.format(LocalDate.of(0, 1, 1).toEpochDay() * IsoTime.MS_PER_DAY))
    }

    @Test
    fun formatAgreesWithJavaTimeOverTenThousandYears() {
        val random = Random(1970)
        val min = LocalDate.of(0, 1, 1).toEpochDay() * IsoTime.MS_PER_DAY
        val max = LocalDate.of(9999, 12, 31).toEpochDay() * IsoTime.MS_PER_DAY + IsoTime.MS_PER_DAY - 1
        repeat(20_000) {
            val ms = min + (random.nextDouble() * (max - min)).toLong()
            val text = IsoTime.format(ms)
            assertEquals(iso.format(Instant.ofEpochMilli(ms)), text)
            assertEquals(text, ms, IsoTime.parse(text))
        }
    }

    @Test
    fun yearsOutsideFourDigitsAreWrittenAsJavaScriptWritesThem() {
        val big = LocalDate.of(10_000, 1, 1).toEpochDay() * IsoTime.MS_PER_DAY
        assertEquals("+010000-01-01T00:00:00.000Z", IsoTime.format(big))
        assertEquals(big, IsoTime.parse("+010000-01-01T00:00:00.000Z"))
        val negative = LocalDate.of(-1, 12, 31).toEpochDay() * IsoTime.MS_PER_DAY
        assertEquals("-000001-12-31T00:00:00.000Z", IsoTime.format(negative))
        assertEquals(negative, IsoTime.parse("-000001-12-31T00:00:00.000Z"))
    }

    @Test
    fun theCivilDateHelpersAgreeWithJavaTime() {
        for (day in -800_000L..800_000L step 7) {
            val date = LocalDate.ofEpochDay(day)
            assertArrayEquals(intArrayOf(date.year, date.monthValue, date.dayOfMonth), IsoTime.civilFromDays(day))
            assertEquals(day, IsoTime.daysFromCivil(date.year, date.monthValue, date.dayOfMonth))
        }
        for (year in -400..2400) {
            assertEquals(year.toString(), LocalDate.of(year, 1, 1).isLeapYear, IsoTime.isLeapYear(year))
            for (month in 1..12) assertEquals(LocalDate.of(year, month, 1).lengthOfMonth(), IsoTime.daysInMonth(year, month))
        }
    }

    @Test
    fun parseReadsTheFormsOtherAppsWrite() {
        fun at(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()
        val cases = mapOf(
            "2026-10-06T07:05:09.123Z" to at("2026-10-06T07:05:09.123Z"), // Bitwarden, JavaScript
            "2024-05-01T10:00:00Z" to at("2024-05-01T10:00:00Z"), // KeePassXC
            "2023-01-02T03:04:05+05:00" to at("2023-01-02T03:04:05+05:00"),
            "2023-01-02T03:04:05+0500" to at("2023-01-02T03:04:05+05:00"),
            "2023-01-02T03:04:05-03" to at("2023-01-02T03:04:05-03:00"),
            "2023-01-02T03:04:05.5-03:30" to at("2023-01-02T03:04:05.500-03:30"),
            "2023-01-02 03:04" to at("2023-01-02T03:04:00Z"), // spreadsheets; no offset means UTC
            "2023-01-02t03:04:05z" to at("2023-01-02T03:04:05Z"),
            "2023-01-02T03:04:05,25Z" to at("2023-01-02T03:04:05.250Z"),
            "2023-01-02T03:04:05.123456789Z" to at("2023-01-02T03:04:05.123Z"), // cut to milliseconds
            "2023-01-02" to at("2023-01-02T00:00:00Z"),
            "  2023-01-02T03:04:05Z  " to at("2023-01-02T03:04:05Z"),
            "2024-02-29T00:00:00Z" to at("2024-02-29T00:00:00Z"),
            "2016-12-31T23:59:60Z" to at("2017-01-01T00:00:00Z"), // a leap second is the next second
        )
        for ((text, ms) in cases) assertEquals(text, ms, IsoTime.parse(text))
    }

    @Test
    fun parseRefusesWhatIsNotADate() {
        val refused = listOf(
            null, "", " ", "today", "2023-1-2", "23-01-02", "2023/01/02", "2023-13-01", "2023-00-10", "2023-02-29", "2023-04-31",
            "2023-01-32", "2023-01-02T24:00:00Z", "2023-01-02T10:60:00Z", "2023-01-02T10:00:61Z", "2023-01-02T10:00:00+24:00",
            "2023-01-02T10:00:00+05:60", "2023-01-02T10Z", "2023-01-02T10:00:00.Z", "2023-01-02T10:00:00.1234567890Z",
            "١٢٣٤-٠١-٠٢", "2023-01-02T10:00:00Q", "2023-01-02T10:00:00Z" + " ".repeat(60),
        )
        for (text in refused) assertNull(text, IsoTime.parse(text))
    }

    @Test
    fun theDigitsAreAsciiWhateverTheDefaultLocale() {
        val saved = Locale.getDefault()
        try {
            for (tag in listOf("fa-IR", "ar-SA", "th-TH-u-nu-thai", "tr-TR", "uz-Cyrl-UZ")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                val text = IsoTime.format(1_791_244_800_123L)
                assertTrue(tag, text.all { it.code < 0x80 })
                assertFalse(tag, text.any { it.isDigit() && it !in '0'..'9' })
                assertEquals(tag, 1_791_244_800_123L, IsoTime.parse(text))
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
