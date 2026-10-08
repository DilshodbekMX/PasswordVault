package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.core.formats.BitwardenJson
import io.github.dilshodbekmx.passwordvault.core.formats.GoogleCsv
import io.github.dilshodbekmx.passwordvault.core.formats.VaultXlsx
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import java.util.TimeZone

/**
 * What the export writes, through the writers of build plan 3.11 (formats.md §4 to §6): the bytes of
 * one [ExportFile] from the open vault, and the summary card of a CSV export (ux.md §5.20.2). Pure,
 * so that it is tested on the JVM; the ViewModel runs it on the default dispatcher (architecture.md
 * §5.3).
 *
 * Every writer leaves out the accounts in Recently deleted (D-10e) and writes a pending change's new
 * password with the old one kept (D-10d); this object adds nothing to what they write.
 */
object ExportFiles {
    /**
     * One file's bytes, which hold every password of the export in clear: the caller writes them and
     * then [wipe]s them. [parts] is how many files the export has (more than one only for a large
     * CSV export); [replaced] how many unpaired surrogates were written as U+FFFD (formats.md §2 rule
     * 8); [truncated] how many cells were cut at Excel's limit (formats.md §6.2).
     */
    class Built(val bytes: ByteArray, val parts: Int, val replaced: Int, val truncated: Int) {
        /** Zeroes the bytes once they are written. */
        fun wipe() = bytes.fill(0)

        override fun toString(): String = "Built(${bytes.size} bytes, parts=$parts, replaced=$replaced, truncated=$truncated)"
    }

    /**
     * The bytes of [file] for [vault] at the wall-clock time [nowMs], with the Excel file's dates in
     * [zone] and its texts from [labels]; [generator] is the JSON file's `"PasswordVault 1.0"`
     * (`BuildInfo.generator`). Null when the export no longer has that part: the vault became
     * smaller since the parts were counted.
     */
    fun build(
        file: ExportFile,
        vault: Vault,
        nowMs: Long,
        zone: TimeZone,
        generator: String,
        labels: VaultXlsx.Labels,
    ): Built? = when (file.format) {
        ExportFormat.CSV -> {
            val export = GoogleCsv.export(vault.entries)
            val bytes = export.parts.getOrNull(file.part - 1)
            // The other files of the export are not needed now: their passwords do not stay in memory.
            export.parts.forEachIndexed { index, part -> if (index != file.part - 1) part.fill(0) }
            bytes?.let { Built(it, export.parts.size, export.report.replacedSurrogates, 0) }
        }
        ExportFormat.JSON -> {
            val written = BitwardenJson.export(vault, nowMs, generator)
            Built(written.bytes, 1, written.replacedSurrogates, 0)
        }
        ExportFormat.XLSX -> {
            val written = VaultXlsx.export(vault.entries, zone, labels)
            Built(written.bytes, 1, written.stats.replacedSurrogates, written.stats.truncatedCells)
        }
    }

    /** How many files the export of [format] from [vault] has: Google's limits cut a CSV export (formats.md §4.4). */
    fun parts(format: ExportFormat, vault: Vault): Int =
        if (format == ExportFormat.CSV) GoogleCsv.report(vault.entries).parts.size else 1

    /** The summary card of a CSV export of [vault] (formats.md §4.5): what Google will skip, and the files. */
    fun csvSummary(vault: Vault): ExportCsvSummary {
        val report = GoogleCsv.report(vault.entries)
        val titles = vault.entries.associate { it.id to it.title }
        val skipped = GoogleCsv.Refusal.entries.mapNotNull { reason ->
            val ids = report.accounts(reason)
            if (ids.isEmpty()) null else ExportCsvSummary.Skipped(reason, ids.map { titles[it].orEmpty() })
        }
        return ExportCsvSummary(report.parts.size, skipped, report.partsTooLarge)
    }
}

/**
 * The card on the warning of a CSV export (ux.md §5.20.2; formats.md §4.5, its counts from
 * `GoogleCsv.report`, which follows Chrome's own checks): the accounts Google Password Manager will
 * skip, for each reason, with their names for "See which"; how many files the export is cut into;
 * and the files that are still over Google's limit. Names only: never a password.
 */
data class ExportCsvSummary(
    val parts: Int,
    /** For each reason Google refuses a row, in Chrome's order, the accounts it applies to; only the reasons that apply. */
    val skipped: List<Skipped>,
    /** The numbers, from 1, of the files Google will refuse as a whole. */
    val partsTooLarge: List<Int>,
) {
    /** The accounts, by name and in the vault's order, that have a row Google refuses for [reason]. */
    data class Skipped(val reason: GoogleCsv.Refusal, val titles: List<String>) {
        override fun toString(): String = "Skipped($reason, ${titles.size} accounts)"
    }
}
