package io.github.dilshodbekmx.passwordvault.files

import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Build plan 5.1, formats.md §2 rules 4 and 5: the suggested names (the local date in ASCII digits,
 * whatever the phone's calendar and digits), and MIME types with which a provider keeps those names.
 */
@RunWith(AndroidJUnit4::class)
class DocumentNamesTest {
    @Test
    fun theNamesSayWhatIsInside() {
        assertEquals("PasswordVault-backup-2026-10-06.pvault", DocumentNames.backup("2026-10-06"))
        assertEquals("passwords-2026-10-06.csv", DocumentNames.csv("2026-10-06"))
        assertEquals("passwords-2026-10-06-part1.csv", DocumentNames.csv("2026-10-06", 1))
        assertEquals("passwords-2026-10-06-part2.csv", DocumentNames.csv("2026-10-06", 2))
        assertEquals("passwords-2026-10-06.json", DocumentNames.json("2026-10-06"))
        assertEquals("passwords-2026-10-06.xlsx", DocumentNames.xlsx("2026-10-06"))
        try {
            DocumentNames.csv("2026-10-06", 0)
            fail("parts are counted from 1")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun theDayIsTheLocalDateInAsciiDigits() {
        val midnightUtc = 1_791_244_800_000L // 2026-10-06T00:00Z, architecture.md §4.13's "created"
        val tashkent = TimeZone.getTimeZone("Asia/Tashkent")
        assertEquals("2026-10-06", DocumentNames.day(midnightUtc, tashkent))
        assertEquals("2026-10-05", DocumentNames.day(midnightUtc, TimeZone.getTimeZone("America/Los_Angeles")))
        assertEquals("2026-10-06", DocumentNames.day(midnightUtc + 86_399_999L - 5 * 3_600_000L, tashkent))
        val before = Locale.getDefault()
        try {
            for (tag in listOf("fa-IR", "ar-EG", "th-TH-u-ca-buddhist", "ja-JP-u-ca-japanese", "uz-Cyrl-UZ", "ru-RU")) {
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(tag, "2026-10-06", DocumentNames.day(midnightUtc, tashkent))
            }
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test
    fun aProviderKeepsTheSuggestedNamesWithTheirTypes() {
        // formats.md §2 rule 4. A documents provider names a new document with FileUtils.splitFileName
        // (Android 7 to 17): the display name stays as it is when the requested type is the one the
        // platform gives the name's extension, when the platform's extension for the type is the name's
        // own, or when the platform knows no extension for the type (application/octet-stream never has
        // one); otherwise the type's extension is appended ("passwords.csv.txt").
        val types = MimeTypeMap.getSingleton()
        val names = mapOf(
            DocumentNames.backup("2026-10-08") to DocumentTypes.BACKUP,
            DocumentNames.csv("2026-10-08") to DocumentTypes.CSV,
            DocumentNames.csv("2026-10-08", 2) to DocumentTypes.CSV,
            DocumentNames.json("2026-10-08") to DocumentTypes.JSON,
            DocumentNames.xlsx("2026-10-08") to DocumentTypes.XLSX,
        )
        for ((name, type) in names) {
            val extension = name.substringAfterLast('.')
            val typeOfExtension = types.getMimeTypeFromExtension(extension) ?: DocumentTypes.BACKUP
            val extensionOfType = if (type == DocumentTypes.BACKUP) null else types.getExtensionFromMimeType(type)
            val kept = type == typeOfExtension || extension == extensionOfType || extensionOfType == null
            assertTrue(
                "$name as $type: this platform gives .$extension the type $typeOfExtension and $type the extension .$extensionOfType",
                kept,
            )
        }
    }
}
