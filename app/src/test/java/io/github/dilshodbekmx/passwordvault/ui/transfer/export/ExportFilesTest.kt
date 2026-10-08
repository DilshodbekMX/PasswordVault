package io.github.dilshodbekmx.passwordvault.ui.transfer.export

import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.files.DocumentTypes
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportChoice
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFile
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFiles
import io.github.dilshodbekmx.passwordvault.ui.transfer.ExportFormat
import java.util.TimeZone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build plan 5.3, [JVM]: what the export writes and how it names it (formats.md §2 rules 4 and 5,
 * §4.4 "Splitting", §4.5), and the steps its unfinished task keeps in the settings.
 */
class ExportFilesTest {
    private val tashkent = TimeZone.getTimeZone("Asia/Tashkent")
    private val newYork = TimeZone.getTimeZone("America/New_York")

    /** 2026-10-06 21:30 UTC: already the 7th in Tashkent, still the 6th in New York. */
    private val lateEvening = 1_791_244_800_000L + 21 * 3_600_000L + 30 * 60_000L

    @Test
    fun theStepOfEachFileReadsBackAndNoOtherStepNamesAFile() {
        val files = listOf(ExportFile(ExportFormat.CSV), ExportFile(ExportFormat.CSV, 2), ExportFile(ExportFormat.JSON), ExportFile(ExportFormat.XLSX))
        assertEquals(listOf(10_001, 10_002, 20_001, 30_001), files.map { it.step })
        for (file in files) assertEquals(file, ExportFile.of(file.step))
        // 0 is the start of the route; a JSON or Excel file has no second part; unknown formats.
        for (step in listOf(0, 1, 10_000, 20_002, 30_002, 40_001, -10_001, 9_999)) assertNull("step $step", ExportFile.of(step))
    }

    @Test
    fun theNamesSayWhatIsInsideAndWhenInThePhonesTimeZone() {
        assertEquals("passwords-2026-10-07.csv", ExportFile(ExportFormat.CSV).suggestedName(1, lateEvening, tashkent))
        assertEquals("passwords-2026-10-06.csv", ExportFile(ExportFormat.CSV).suggestedName(1, lateEvening, newYork))
        assertEquals("passwords-2026-10-07-part1.csv", ExportFile(ExportFormat.CSV, 1).suggestedName(2, lateEvening, tashkent))
        assertEquals("passwords-2026-10-07-part2.csv", ExportFile(ExportFormat.CSV, 2).suggestedName(2, lateEvening, tashkent))
        assertEquals("passwords-2026-10-07.json", ExportFile(ExportFormat.JSON).suggestedName(1, lateEvening, tashkent))
        assertEquals("passwords-2026-10-07.xlsx", ExportFile(ExportFormat.XLSX).suggestedName(1, lateEvening, tashkent))
        // The MIME types that keep those extensions (formats.md §2 rule 4).
        assertEquals(DocumentTypes.CSV, ExportFormat.CSV.mime)
        assertEquals(DocumentTypes.JSON, ExportFormat.JSON.mime)
        assertEquals(DocumentTypes.XLSX, ExportFormat.XLSX.mime)
    }

    @Test
    fun theChoicesAreInTheOrderOfUx520WithTheBackupFirst() {
        assertEquals(listOf(ExportChoice.BACKUP, ExportChoice.XLSX, ExportChoice.CSV, ExportChoice.JSON), ExportChoice.entries.toList())
        assertNull(ExportChoice.BACKUP.format)
        for (format in ExportFormat.entries) assertEquals(format, format.choice.format)
    }

    @Test
    fun eachFileIsWhatItsWriterWritesAndAPartThatIsGoneIsNone() {
        val vault = ExportKit.sampleVault()
        val now = lateEvening
        val csv = ExportFiles.build(ExportFile(ExportFormat.CSV), vault, now, tashkent, "PasswordVault 1.0", VaultXlsx.Labels())!!
        assertArrayEquals(GoogleCsv.export(vault.entries).parts.single(), csv.bytes)
        assertEquals(1, csv.parts)
        assertNull(ExportFiles.build(ExportFile(ExportFormat.CSV, 2), vault, now, tashkent, "PasswordVault 1.0", VaultXlsx.Labels()))

        val xlsx = ExportFiles.build(ExportFile(ExportFormat.XLSX), vault, now, tashkent, "PasswordVault 1.0", ExportKit.LABELS)!!
        assertArrayEquals(VaultXlsx.export(vault.entries, tashkent, ExportKit.LABELS).bytes, xlsx.bytes)
        assertEquals(0, xlsx.truncated)

        // Wiped once written: nothing of the passwords stays in the array.
        csv.wipe()
        assertTrue(csv.bytes.all { it == 0.toByte() })
    }

    @Test
    fun theCsvCardCountsTheAccountsGoogleSkipsForEachReasonByName() {
        val vault = Vault(
            entries = listOf(
                entry("1", "No website", url = null),
                entry("2", "No password", password = ""),
                entry("3", "Long note", notes = "x".repeat(1_001)),
                entry("4", "Two sites, one bad", urls = listOf("https://good.example", "https://bad host.example")),
                entry("5", "Fine"),
                entry("6", "Deleted, no website", url = null, deletedAt = 1L),
            ),
        )
        val card = ExportFiles.csvSummary(vault)
        assertEquals(1, card.parts)
        assertEquals(emptyList<Int>(), card.partsTooLarge)
        val byReason = card.skipped.associate { it.reason to it.titles }
        assertEquals(listOf("No password"), byReason[GoogleCsv.Refusal.NO_PASSWORD])
        assertEquals(listOf("Long note"), byReason[GoogleCsv.Refusal.LONG_NOTE])
        assertEquals("the deleted account is not in the file", listOf("No website"), byReason[GoogleCsv.Refusal.NO_WEBSITE])
        assertEquals(listOf("Two sites, one bad"), byReason[GoogleCsv.Refusal.BAD_WEBSITE])
        assertEquals("only the reasons that apply, in Chrome's order", GoogleCsv.Refusal.entries.filter { it in byReason }, card.skipped.map { it.reason })
        assertTrue("never a password in what it shows", "pw" !in card.toString())
    }

    @Test
    fun aLargeExportIsCutIntoFilesGoogleTakes() {
        val vault = Vault(entries = (1..6_001).map { entry("$it", "Account $it") })
        assertEquals(3, ExportFiles.parts(ExportFormat.CSV, vault))
        assertEquals(1, ExportFiles.parts(ExportFormat.JSON, vault))
        assertEquals(1, ExportFiles.parts(ExportFormat.XLSX, vault))
        val third = ExportFiles.build(ExportFile(ExportFormat.CSV, 3), vault, lateEvening, tashkent, "PasswordVault 1.0", VaultXlsx.Labels())!!
        assertArrayEquals(GoogleCsv.export(vault.entries).parts[2], third.bytes)
        assertEquals(3, third.parts)
    }

    private fun entry(
        n: String,
        title: String,
        password: String = "pw-$n",
        url: String? = "https://site$n.example",
        urls: List<String> = listOfNotNull(url),
        notes: String = "",
        deletedAt: Long? = null,
    ) = Entry(
        id = "00000000-0000-4000-8000-" + n.padStart(12, '0'),
        title = title,
        password = password,
        urls = urls,
        notes = notes,
        createdAt = ExportKit.ADDED_MS,
        updatedAt = ExportKit.ADDED_MS,
        deletedAt = deletedAt,
    )
}
