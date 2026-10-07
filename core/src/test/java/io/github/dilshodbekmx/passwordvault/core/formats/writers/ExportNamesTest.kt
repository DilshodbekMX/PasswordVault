package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.ExportNames
import io.github.dilshodbekmx.passwordvault.core.formats.IsoTime
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.TASHKENT
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The names and MIME types of the files the app saves (formats.md §0, §2 rules 4 and 5). */
class ExportNamesTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val newYork: TimeZone = TimeZone.getTimeZone("America/New_York")

    @Test
    fun theNamesAreThoseOfFormatsMd() {
        assertEquals("PasswordVault-backup-2026-10-06.pvault", ExportNames.backup(T0, TASHKENT))
        assertEquals("passwords-2026-10-06.csv", ExportNames.csv(T0, TASHKENT))
        assertEquals("passwords-2026-10-06.json", ExportNames.json(T0, TASHKENT))
        assertEquals("passwords-2026-10-06.xlsx", ExportNames.xlsx(T0, TASHKENT))
        assertEquals("2026-10-06", ExportNames.date(T0, TASHKENT))
    }

    @Test
    fun aSplitCsvNamesItsParts() {
        assertEquals("passwords-2026-10-06.csv", ExportNames.csv(T0, TASHKENT, part = 1, parts = 1))
        assertEquals("passwords-2026-10-06-part1.csv", ExportNames.csv(T0, TASHKENT, part = 1, parts = 2))
        assertEquals("passwords-2026-10-06-part2.csv", ExportNames.csv(T0, TASHKENT, part = 2, parts = 2))
        assertEquals("passwords-2026-10-06-part12.csv", ExportNames.csv(T0, TASHKENT, part = 12, parts = 12))
        for ((part, parts) in listOf(0 to 1, 2 to 1, 3 to 2, -1 to 2, 1 to 0)) {
            assertThrows(IllegalArgumentException::class.java) { ExportNames.csv(T0, TASHKENT, part, parts) }
        }
    }

    @Test
    fun theDateIsTheLocalDateOfTheZoneGiven() {
        val evening = IsoTime.parse("2026-10-06T20:30:00Z")!! // 01:30 on the 7th in Tashkent, 16:30 on the 6th in New York
        assertEquals("2026-10-07", ExportNames.date(evening, TASHKENT))
        assertEquals("2026-10-06", ExportNames.date(evening, utc))
        assertEquals("2026-10-06", ExportNames.date(evening, newYork))
        assertEquals("passwords-2026-10-07.xlsx", ExportNames.xlsx(evening, TASHKENT))
        assertEquals("PasswordVault-backup-2026-10-06.pvault", ExportNames.backup(evening, newYork))
        val newYear = IsoTime.parse("2026-12-31T19:00:00Z")!!
        assertEquals("2027-01-01", ExportNames.date(newYear, TASHKENT))
        assertEquals("2026-12-31", ExportNames.date(newYear, newYork))
        assertEquals("2024-02-29", ExportNames.date(IsoTime.parse("2024-02-29T12:00:00Z")!!, utc))
        assertEquals("1970-01-01", ExportNames.date(0, utc))
        assertEquals("1969-12-31", ExportNames.date(-1, utc))
    }

    @Test
    fun theDigitsAreAsciiWhateverTheLanguageAndTheDefaultZoneDoesNotMatter() {
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            for (tag in listOf("fa-IR", "ar-SA", "th-TH-u-nu-thai", "tr-TR", "uz-Cyrl-UZ", "ru-RU")) {
                for (zoneId in listOf("UTC", "America/New_York", "Pacific/Kiritimati")) {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                    assertEquals("$tag $zoneId", "PasswordVault-backup-2026-10-06.pvault", ExportNames.backup(T0, TASHKENT))
                    assertEquals("$tag $zoneId", "passwords-2026-10-06-part3.csv", ExportNames.csv(T0, TASHKENT, 3, 4))
                }
            }
        } finally {
            Locale.setDefault(locale)
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun aNameKeepsItsShapeWhateverTheClockSays() {
        val shape = Regex("passwords-\\d{4}-\\d{2}-\\d{2}\\.json")
        for (ms in listOf(Long.MIN_VALUE, -62_167_219_200_001L, 0L, T0, 253_402_300_800_000L, Long.MAX_VALUE)) {
            val name = ExportNames.json(ms, utc)
            assertTrue(name, shape.matches(name))
        }
        assertEquals("passwords-0000-01-01.json", ExportNames.json(Long.MIN_VALUE, utc)) // before the year 0
        assertEquals("passwords-0000-01-01.json", ExportNames.json(-62_167_219_200_001L, utc)) // its last millisecond
        assertEquals("passwords-0000-01-01.json", ExportNames.json(-62_167_219_200_000L, utc)) // 0000-01-01 00:00
        assertEquals("passwords-9999-12-31.json", ExportNames.json(253_402_300_799_999L, utc))
        assertEquals("passwords-9999-12-31.json", ExportNames.json(253_402_300_800_000L, utc)) // the year 10000
        assertEquals("passwords-9999-12-31.json", ExportNames.json(Long.MAX_VALUE, TASHKENT))
        assertEquals("passwords-0000-01-01.json", ExportNames.json(Long.MIN_VALUE, TimeZone.getTimeZone("America/New_York")))
    }

    @Test
    fun theMimeTypesMatchTheExtensions() {
        // A provider appends the type's own extension when the name's belongs to another type; so each
        // name is handed to the file picker with exactly this type. octet-stream is exempt (formats.md §2 rule 4).
        assertEquals("application/octet-stream", ExportNames.BACKUP_MIME)
        assertEquals("text/csv", ExportNames.CSV_MIME)
        assertEquals("application/json", ExportNames.JSON_MIME)
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ExportNames.XLSX_MIME)
        assertTrue(ExportNames.backup(T0, TASHKENT).endsWith("." + ExportNames.BACKUP_EXTENSION))
        assertTrue(ExportNames.csv(T0, TASHKENT, 2, 3).endsWith("." + ExportNames.CSV_EXTENSION))
        assertTrue(ExportNames.json(T0, TASHKENT).endsWith("." + ExportNames.JSON_EXTENSION))
        assertTrue(ExportNames.xlsx(T0, TASHKENT).endsWith("." + ExportNames.XLSX_EXTENSION))
        // Nothing in a name that a file system or a provider could object to.
        for (name in listOf(ExportNames.backup(T0, TASHKENT), ExportNames.csv(T0, TASHKENT, 2, 3), ExportNames.json(T0, TASHKENT))) {
            assertTrue(name, Regex("[A-Za-z0-9.-]+").matches(name))
        }
    }
}
