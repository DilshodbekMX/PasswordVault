package io.github.dilshodbekmx.passwordvault.core.formats

import java.util.TimeZone

/**
 * The names and MIME types of the files the app saves (formats.md §0 and §2 rules 4 and 5). A name
 * says what is inside and when it was made, so that the user finds the file and deletes it:
 * `PasswordVault-backup-2026-10-06.pvault`, `passwords-2026-10-06.csv` (`-part1`, `-part2`, … when
 * the CSV is split), `passwords-2026-10-06.json`, `passwords-2026-10-06.xlsx`.
 *
 * - The date is the local date in the time zone given (the phone's), written with ASCII digits
 *   whatever the language, without `java.time` (API 26).
 * - The MIME type handed to the file picker must match the extension: Android's document providers
 *   append the type's own extension when the name's maps to another type; `application/octet-stream`
 *   is exempt, which is why the backup uses it.
 * - The name of the locked workbook (`…-locked.xlsx`) is not here: it is not in version 1
 *   (architecture.md D-16).
 */
object ExportNames {
    const val BACKUP_MIME = "application/octet-stream"
    const val CSV_MIME = "text/csv"
    const val JSON_MIME = "application/json"
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    const val BACKUP_EXTENSION = "pvault"
    const val CSV_EXTENSION = "csv"
    const val JSON_EXTENSION = "json"
    const val XLSX_EXTENSION = "xlsx"

    /** The app's name in a backup's file name; the same in every language (architecture.md D-23). */
    private const val BACKUP_PREFIX = "PasswordVault-backup-"
    private const val PLAIN_PREFIX = "passwords-"

    /** `PasswordVault-backup-2026-10-06.pvault` for a backup made at [nowMs] (wall clock) in [zone]. */
    fun backup(nowMs: Long, zone: TimeZone): String = BACKUP_PREFIX + date(nowMs, zone) + "." + BACKUP_EXTENSION

    /**
     * `passwords-2026-10-06.csv`, or `passwords-2026-10-06-part2.csv` for part [part] (from 1) when
     * the export has more than one of [parts] (`GoogleCsv.Export.parts`).
     */
    fun csv(nowMs: Long, zone: TimeZone, part: Int = 1, parts: Int = 1): String {
        require(parts >= 1 && part in 1..parts) { "no such part" }
        val suffix = if (parts > 1) "-part$part" else ""
        return PLAIN_PREFIX + date(nowMs, zone) + suffix + "." + CSV_EXTENSION
    }

    /** `passwords-2026-10-06.json`. */
    fun json(nowMs: Long, zone: TimeZone): String = PLAIN_PREFIX + date(nowMs, zone) + "." + JSON_EXTENSION

    /** `passwords-2026-10-06.xlsx`. */
    fun xlsx(nowMs: Long, zone: TimeZone): String = PLAIN_PREFIX + date(nowMs, zone) + "." + XLSX_EXTENSION

    /**
     * The local date of [nowMs] in [zone] as `2026-10-06`: four digits for the year (a year outside
     * 0 to 9999 is written as the nearest of the two, so that the name keeps its shape), two for the
     * month and the day.
     */
    fun date(nowMs: Long, zone: TimeZone): String {
        val local = nowMs + zone.getOffset(nowMs)
        val date = IsoTime.civilFromDays(local.floorDiv(IsoTime.MS_PER_DAY))
        val sb = StringBuilder(10)
        pad(sb, date[0].coerceIn(0, 9999), 4)
        sb.append('-')
        pad(sb, date[1], 2)
        sb.append('-')
        pad(sb, date[2], 2)
        return sb.toString()
    }

    /** Appends [value] (not negative) in ASCII digits with leading zeros up to [width]. */
    private fun pad(sb: StringBuilder, value: Int, width: Int) {
        val digits = value.toString() // Int.toString writes ASCII digits in every locale
        for (k in digits.length until width) sb.append('0')
        sb.append(digits)
    }
}
