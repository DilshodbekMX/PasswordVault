package io.github.dilshodbekmx.passwordvault.core.formats.writers

import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.formats.Xlsx
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.DAY
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.T0
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.TASHKENT
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.entry
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.hidden
import io.github.dilshodbekmx.passwordvault.core.formats.writers.WriterFixtures.visible
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.HistoryItem
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The Excel export (formats.md §6), read back with `java.util.zip` and the JDK's XML parser. The
 * workbook itself (parts, escapes, zip times, limits) is `XlsxTest`'s; here it is the vault's sheet.
 */
class VaultXlsxTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun workbook(entries: List<Entry>, labels: VaultXlsx.Labels = VaultXlsx.Labels(), zone: TimeZone = TASHKENT): XlsxReader =
        XlsxReader.read(VaultXlsx.export(entries, zone, labels).bytes)

    private val header = listOf("Name", "Username", "Password", "Website", "Other websites", "Notes", "Extra fields", "Favourite",
        "Added", "Last changed", "Password changed")

    private val full = entry(
        1, "Full entry", "full@example.com", "Current#1",
        listOf("https://a.example/login", "https://b.example", "android://AbC123==@com.example.app/", "androidapp://org.example.other"),
        "Everything set\nsecond line",
        listOf(hidden("PIN code", "4321"), visible("Security question", "First school?"), visible("Address", "line one\nline two")),
        favourite = true, checkIgnored = true, createdAt = T0 - 400 * DAY, updatedAt = T0, passwordChangedAt = T0 - 30 * DAY,
        history = listOf(HistoryItem("Old#2", T0 - 30 * DAY)),
    )

    // ------------------------------------------------------------------ the sheet

    @Test
    fun theSheetHasTheElevenColumnsOfFormatsMdWithTheirWidths() {
        val book = workbook(listOf(full))
        assertEquals("Passwords", book.sheetName)
        assertEquals(header, book.grid()[0])
        assertEquals(listOf(24.0, 26.0, 22.0, 30.0, 24.0, 40.0, 30.0, 10.0, 16.0, 16.0, 16.0), book.widths)
        assertEquals(header, VaultXlsx.columns().map { it.header })
        // The seven parts and no more: no docProps, so no author and no time (formats.md §6.1).
        assertEquals(
            listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/sharedStrings.xml"),
            book.names,
        )
        val sheet = book.xml(XlsxReader.SHEET).documentElement
        fun element(name: String) = sheet.getElementsByTagNameNS(XlsxReader.NS, name).item(0) as Element
        assertEquals("A1:K2", element("dimension").getAttribute("ref"))
        assertEquals("frozen", element("pane").getAttribute("state")) // the header row stays in view
        assertEquals("1", element("pane").getAttribute("ySplit"))
        assertEquals("A1:K2", element("autoFilter").getAttribute("ref"))
        assertEquals("landscape", element("pageSetup").getAttribute("orientation"))
        assertEquals("&LPasswordVault&R&D", element("oddHeader").textContent) // the app's name, and the date
        val names = book.xml("xl/workbook.xml").documentElement.getElementsByTagNameNS(XlsxReader.NS, "definedName")
        assertEquals("'Passwords'!\$1:\$1", names.item(1).textContent) // the header on every printed page
        // The header row in the header's style, every data cell in the text format "@".
        assertEquals(List(11) { "1" }, book.styles()[0])
        assertTrue(book.styles()[1].all { it == "2" })
    }

    @Test
    fun anAccountIsOneRowWithItsCellsAsFormatsMdSaysThem() {
        val row = workbook(listOf(full)).grid()[1]
        assertEquals(
            listOf(
                "Full entry",
                "full@example.com",
                "Current#1",
                "https://a.example/login",
                "https://b.example\napp: com.example.app\napp: org.example.other", // one per line; app links by package
                "Everything set\nsecond line",
                "PIN code (hidden): 4321\nSecurity question: First school?\nAddress: line one\nline two",
                "Yes",
                "2025-09-01 12:05", // local time in the zone given: Tashkent, UTC+5
                "2026-10-06 12:05",
                "2026-09-06 12:05",
            ),
            row,
        )
        // Not in the file: previous passwords, "left out of the security check", the id (formats.md §6.3).
        val strings = workbook(listOf(full)).sharedStrings
        assertFalse(strings.any { it.contains("Old#2") || it.contains(full.id) })
    }

    @Test
    fun emptyValuesLeaveTheirCellsEmpty() {
        val row = workbook(listOf(entry(1, "Only a name"))).grid()[1]
        assertEquals(listOf("Only a name", "", "", "", "", "", "", "", "2026-10-06 12:05", "2026-10-06 12:05", ""), row)
        assertEquals(listOf("Only a name", "", "", null, null, "", null, null, "2026-10-06 12:05", "2026-10-06 12:05", null),
            VaultXlsx.row(entry(1, "Only a name"), TASHKENT))
        // One website: nothing under "Other websites". A blank one (never stored by the app) is no website.
        val one = VaultXlsx.row(entry(1, "One", urls = listOf("  ", "https://one.example")), TASHKENT)
        assertEquals("https://one.example", one[3])
        assertNull(one[4])
    }

    @Test
    fun everyAccountOfTheVaultIsOneRowInTheOrderGiven() {
        val entries = WriterFixtures.specialEntries()
        val live = entries.filter { it.deletedAt == null }
        val exported = VaultXlsx.export(entries, TASHKENT)
        val grid = XlsxReader.read(exported.bytes).grid()
        assertEquals(live.size + 1, grid.size)
        assertEquals(live.size, exported.stats.rows)
        assertEquals(1, exported.stats.truncatedCells) // the 40,000-character note
        // Every cell is what the writer meant, after the workbook's own rules for a cell.
        for ((i, e) in live.withIndex()) {
            if (e.title.contains('\uD800')) continue // the entry with unpaired surrogates is checked below
            val meant = VaultXlsx.row(e, TASHKENT).map { cell ->
                val text = Xlsx.limitLineFeeds(Xlsx.normalizeNewlines(cell ?: ""))
                if (text.length > Xlsx.MAX_CELL_CHARS) Xlsx.truncate(text, Xlsx.DEFAULT_TRUNCATION_NOTE) else text
            }
            assertEquals(e.title, meant, grid[i + 1])
        }
        // Values a spreadsheet would change when read as CSV are text here, exactly.
        val formula = grid.first { it[0].startsWith("=HYPERLINK") }
        assertEquals(listOf("=HYPERLINK(\"http://evil.example\",\"click\")", "+998901234567", "-2+3"), formula.subList(0, 3))
        assertEquals("@SUM(A1:A2)", formula[5])
        assertEquals("calc: =1+1", formula[6])
        val sheet = XlsxReader.read(exported.bytes).text(XlsxReader.SHEET)
        for (banned in listOf("<f>", "<f ", "inlineStr", "t=\"str\"", "hyperlink")) assertFalse(banned, sheet.contains(banned))
        // An unpaired surrogate is U+FFFD in the workbook, as in the other two files, and is counted.
        val lone = grid.first { it[0] == "T�x" }
        assertEquals(listOf("T�x", "u�", "p�", "https://x.example/a�"), lone.subList(0, 4))
        assertEquals("F� (hidden): v�", lone[6])
        assertEquals(8, exported.stats.replacedSurrogates)
    }

    @Test
    fun deletedAccountsHaveNoRow() {
        val deleted = entry(2, "Deleted account", "gone@example.com", "Deleted#Pass", listOf("https://deleted.example"),
            "must not be exported", listOf(hidden("Deleted PIN", "9753")), deletedAt = T0 - DAY)
        val exported = VaultXlsx.export(listOf(entry(1, "Kept"), deleted, entry(3, "Kept too")), TASHKENT)
        val book = XlsxReader.read(exported.bytes)
        assertEquals(listOf("Name", "Kept", "Kept too"), book.grid().map { it[0] })
        assertEquals(2, exported.stats.rows)
        for (gone in listOf("Deleted", "gone@example.com", "deleted.example", "must not be exported", "9753")) {
            assertFalse(gone, book.sharedStrings.any { it.contains(gone) })
        }
        assertEquals(2, VaultXlsx.rows(listOf(entry(1, "Kept"), deleted, entry(3, "Kept too")), TASHKENT).size)
        // An empty vault, or one with deleted accounts only: the header row alone.
        assertEquals(listOf(header), workbook(emptyList()).grid())
        assertEquals(listOf(header), workbook(listOf(deleted)).grid())
    }

    @Test
    fun aPendingChangeShowsTheNewPasswordAndKeepsTheOldOneInTheExtraFields() {
        val pending = entry(1, "Gmail", "aziz@gmail.com", "N3w!pass", listOf("https://mail.google.com"), "",
            listOf(hidden("PIN", "1234")), changePending = true, pendingOldPassword = "Old#2")
        val row = workbook(listOf(pending)).grid()[1]
        assertEquals("N3w!pass", row[2])
        assertEquals("PIN (hidden): 1234\nOld password (hidden): Old#2", row[6])
        // Without extra fields the line stands alone; without a pending change there is none.
        assertEquals("Old password (hidden): Old#2", workbook(listOf(pending.copy(fields = emptyList()))).grid()[1][6])
        assertEquals("PIN (hidden): 1234", workbook(listOf(pending.copy(changePending = false))).grid()[1][6])
        assertEquals("PIN (hidden): 1234", workbook(listOf(pending.copy(pendingOldPassword = null))).grid()[1][6])
    }

    @Test
    fun hiddenFieldsAreShownInClearAndMarked() {
        // D-17: the file exists to be read and printed; "(hidden)" still says which values are secret.
        val e = entry(1, "Bank", "ali", "B@nk1", listOf("https://bank.uz"), "",
            listOf(hidden("PIN", "1234"), visible("Code (hidden)", "77"), visible("Q: mother's name", "Aziza")))
        assertEquals("PIN (hidden): 1234\nCode (hidden): 77\nQ: mother's name: Aziza", workbook(listOf(e)).grid()[1][6])
    }

    // ------------------------------------------------------------------ the labels

    @Test
    fun theLabelsComeFromTheApp() {
        val labels = VaultXlsx.Labels(
            sheet = "Пароллар", name = "Номи", username = "Фойдаланувчи номи", password = "Парол", website = "Сайт",
            otherWebsites = "Бошқа сайтлар", notes = "Изоҳ", fields = "Қўшимча майдонлар", favourite = "Сараланган",
            added = "Қўшилган", lastChanged = "Ўзгартирилган", passwordChanged = "Парол ўзгартирилган", yes = "Ҳа",
            hidden = "яширин", oldPassword = "Эски парол", app = "илова", pageHeader = "PasswordVault",
            truncationNote = { n -> "\n[… яна $n та белги]" },
        )
        val pending = full.copy(changePending = true, pendingOldPassword = "Old#9", notes = "я".repeat(40_000))
        val exported = VaultXlsx.export(listOf(pending), TASHKENT, labels)
        val book = XlsxReader.read(exported.bytes)
        assertEquals("Пароллар", book.sheetName)
        assertEquals(
            listOf("Номи", "Фойдаланувчи номи", "Парол", "Сайт", "Бошқа сайтлар", "Изоҳ", "Қўшимча майдонлар", "Сараланган",
                "Қўшилган", "Ўзгартирилган", "Парол ўзгартирилган"),
            book.grid()[0],
        )
        val row = book.grid()[1]
        assertEquals("https://b.example\nилова: com.example.app\nилова: org.example.other", row[4])
        assertEquals(
            "PIN code (яширин): 4321\nSecurity question: First school?\nAddress: line one\nline two\nЭски парол (яширин): Old#9",
            row[6],
        )
        assertEquals("Ҳа", row[7])
        // The cut cell ends with the app's own note, in the user's language.
        assertEquals(1, exported.stats.truncatedCells)
        assertTrue(row[5].length <= Xlsx.MAX_CELL_CHARS)
        assertTrue(row[5].endsWith(" та белги]"))
        // Dates stay ASCII digits whatever the language.
        assertEquals("2026-10-06 12:05", row[9])
    }

    @Test
    fun aLabelThatExcelRefusesAsASheetNameDoesNotStopTheExport() {
        for (bad in listOf("", "Parollar: hammasi", "a/b", "[x]", "'quoted'", "History", "x".repeat(32), "bad\uD800")) {
            val book = workbook(listOf(full), VaultXlsx.Labels(sheet = bad))
            assertEquals(bad, VaultXlsx.DEFAULT_SHEET, book.sheetName)
            assertEquals(2, book.grid().size)
        }
        assertEquals("Passwords", VaultXlsx.DEFAULT_SHEET)
        assertEquals("Parollar", workbook(listOf(full), VaultXlsx.Labels(sheet = "Parollar")).sheetName)
        assertEquals("x".repeat(31), workbook(listOf(full), VaultXlsx.Labels(sheet = "x".repeat(31))).sheetName)
    }

    // ------------------------------------------------------------------ dates and links

    @Test
    fun datesAreLocalTextInTheZoneGivenWithAsciiDigits() {
        assertEquals("2026-10-06 12:05", VaultXlsx.localTime(T0, TASHKENT))
        assertEquals("2026-10-06 07:05", VaultXlsx.localTime(T0, utc))
        assertEquals("2026-10-06 03:05", VaultXlsx.localTime(T0, TimeZone.getTimeZone("America/New_York"))) // daylight time
        assertEquals("2026-10-06 12:35", VaultXlsx.localTime(T0, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals("2026-10-07 01:05", VaultXlsx.localTime(T0 + 13 * 3_600_000L, TASHKENT)) // the next day there
        assertEquals("1970-01-01 00:00", VaultXlsx.localTime(0, utc))
        assertEquals("1969-12-31 23:59", VaultXlsx.localTime(-1, utc))
        assertEquals("2024-02-29 23:59", VaultXlsx.localTime(1_709_251_199_999L, utc))
        assertEquals("2000-01-01 00:00", VaultXlsx.localTime(946_684_800_000L, utc))
        // Text of this shape sorts in the order of time.
        val times = listOf(T0 - 400 * DAY, T0 - DAY, T0, T0 + 60_000, T0 + DAY)
        assertEquals(times.map { VaultXlsx.localTime(it, TASHKENT) }, times.map { VaultXlsx.localTime(it, TASHKENT) }.sorted())
    }

    @Test
    fun androidAppLinksAreShownByTheirPackage() {
        assertEquals("app: com.example.app", VaultXlsx.displayUrl("android://AbC123==@com.example.app/"))
        assertEquals("app: com.example.app", VaultXlsx.displayUrl("ANDROID://AbC123==@com.example.app"))
        assertEquals("app: com.example.app", VaultXlsx.displayUrl("androidapp://com.example.app"))
        assertEquals("ilova: com.example.app", VaultXlsx.displayUrl("androidapp://com.example.app/", "ilova"))
        assertEquals("https://example.com/a@b", VaultXlsx.displayUrl("https://example.com/a@b"))
        assertEquals("android://no-hash/", VaultXlsx.displayUrl("android://no-hash/"))
        assertEquals("gmail.com", VaultXlsx.displayUrl("gmail.com"))
    }

    // ------------------------------------------------------------------ the same bytes everywhere

    @Test
    fun theBytesDependOnNeitherTheDefaultZoneNorTheDefaultLocale() {
        val entries = WriterFixtures.specialEntries()
        val zone = TimeZone.getDefault()
        val locale = Locale.getDefault()
        try {
            val reference = VaultXlsx.export(entries, TASHKENT).bytes
            for (zoneId in listOf("UTC", "Asia/Tashkent", "America/New_York", "Pacific/Kiritimati")) {
                for (tag in listOf("en-US", "tr-TR", "fa-IR", "ar-SA", "th-TH-u-nu-thai", "uz-Cyrl-UZ")) {
                    TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    // The dates in the cells are those of the zone that is passed in, not of the default one.
                    assertArrayEquals("$zoneId $tag", reference, VaultXlsx.export(entries, TASHKENT).bytes)
                }
            }
            // And an unchanged vault gives the same file every time.
            assertArrayEquals(reference, VaultXlsx.export(entries, TASHKENT).bytes)
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
        }
    }

    @Test
    fun writeAndExportGiveTheSameWorkbook() {
        val out = ByteArrayOutputStream()
        val stats = VaultXlsx.write(out, listOf(full), TASHKENT)
        val exported = VaultXlsx.export(listOf(full), TASHKENT)
        assertArrayEquals(out.toByteArray(), exported.bytes)
        assertEquals(stats.rows, exported.stats.rows)
        assertEquals(1, stats.rows)
        assertFalse(exported.toString().contains("Full entry"))
    }
}
