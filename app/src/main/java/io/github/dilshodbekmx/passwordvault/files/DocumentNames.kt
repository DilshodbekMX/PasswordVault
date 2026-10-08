package io.github.dilshodbekmx.passwordvault.files

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The names the file picker suggests (formats.md §2 rule 5): the local date in ASCII digits, and a
 * name that says what is inside, so that the user finds the file and deletes it. A provider may
 * still change the name (for example "(1)" on a clash); the screens show the name it kept.
 *
 * No `java.time` (API 26, build check R-11): the day comes from a Gregorian [Calendar] in
 * [Locale.ROOT], so a phone set to a Thai or Persian calendar still gets 2026-10-06.
 */
object DocumentNames {
    /** `PasswordVault-backup-2026-10-06.pvault`. */
    fun backup(day: String): String = "PasswordVault-backup-$day.pvault"

    /** `passwords-2026-10-06.csv`, or `passwords-2026-10-06-part1.csv` for [part] 1 of a split export. */
    fun csv(day: String, part: Int? = null): String {
        require(part == null || part >= 1) { "parts are counted from 1" }
        return "passwords-$day" + (if (part == null) "" else "-part$part") + ".csv"
    }

    /** `passwords-2026-10-06.json`. */
    fun json(day: String): String = "passwords-$day.json"

    /** `passwords-2026-10-06.xlsx`. */
    fun xlsx(day: String): String = "passwords-$day.xlsx"

    /** The local date of [nowMs] in [zone] as `yyyy-MM-dd`, with ASCII digits. */
    fun day(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val calendar = Calendar.getInstance(zone, Locale.ROOT)
        calendar.timeInMillis = nowMs
        return String.format(
            Locale.ROOT,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
    }
}
