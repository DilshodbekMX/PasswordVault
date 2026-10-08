package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.files.DocumentNames
import io.github.dilshodbekmx.passwordvault.files.DocumentTypes
import java.util.TimeZone

/**
 * The rows of "Choose a format" (ux.md §5.20), in the order shown: the locked backup first, as the
 * recommended one, then the three formats that are not protected.
 */
enum class ExportChoice(
    /** The plain format this row exports; null for the backup, which is the backup's own screen (ux.md §5.19). */
    val format: ExportFormat?,
) {
    BACKUP(null),
    XLSX(ExportFormat.XLSX),
    CSV(ExportFormat.CSV),
    JSON(ExportFormat.JSON),
}

/**
 * A format that is not protected (formats.md §0): what [mime] type the file picker gets, which
 * keeps the file's extension (formats.md §2 rule 4, `DocumentTypes`), and the [code] its step
 * starts with ([ExportFile.step]). The code is kept in the settings with an unfinished task, so it
 * never changes.
 */
enum class ExportFormat(val code: Int, val mime: String) {
    CSV(1, DocumentTypes.CSV),
    JSON(2, DocumentTypes.JSON),
    XLSX(3, DocumentTypes.XLSX),
    ;

    /** The row of "Choose a format" that leads to this format. */
    val choice: ExportChoice get() = ExportChoice.entries.first { it.format == this }
}

/**
 * One file of an export: its [format] and, for a CSV export cut into several files (formats.md
 * §4.4, "Splitting"), which one ([part], from 1). JSON and Excel have one file, part 1.
 */
data class ExportFile(val format: ExportFormat, val part: Int = 1) {
    init {
        require(part in 1 until STEP_BASE) { "no such part" }
        require(format == ExportFormat.CSV || part == 1) { "only a CSV export has parts" }
    }

    /**
     * The step of the export's file task (`FileTask.step`, architecture.md §3.5): the format's code
     * times [STEP_BASE], plus the part. 10001 is the first CSV file, 20001 the JSON file, 30001 the
     * Excel file. Kept in the settings while the task is unfinished, so that a new process knows what
     * the chosen document is for (ux.md §4).
     */
    val step: Int get() = format.code * STEP_BASE + part

    /**
     * The name the file picker suggests (formats.md §2 rule 5): `passwords-2026-10-06.csv`, or
     * `passwords-2026-10-06-part2.csv` when the CSV export has more than one of [parts]. The date is
     * [nowMs]'s day in [zone], in ASCII digits.
     */
    fun suggestedName(parts: Int, nowMs: Long, zone: TimeZone): String {
        val day = DocumentNames.day(nowMs, zone)
        return when (format) {
            ExportFormat.CSV -> DocumentNames.csv(day, if (parts > 1) part else null)
            ExportFormat.JSON -> DocumentNames.json(day)
            ExportFormat.XLSX -> DocumentNames.xlsx(day)
        }
    }

    companion object {
        /** The steps of the formats are this far apart; a CSV export never has this many files. */
        const val STEP_BASE = 10_000

        /** The file of [step]; null for a step that names none (0, the start, or a step of an older version). */
        fun of(step: Int): ExportFile? {
            val format = ExportFormat.entries.firstOrNull { it.code == step / STEP_BASE } ?: return null
            val part = step % STEP_BASE
            if (part < 1 || (format != ExportFormat.CSV && part != 1)) return null
            return ExportFile(format, part)
        }
    }
}
