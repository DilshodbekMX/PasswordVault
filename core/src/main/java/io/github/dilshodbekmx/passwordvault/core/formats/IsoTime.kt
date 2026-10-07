package io.github.dilshodbekmx.passwordvault.core.formats

/**
 * ISO 8601 times without `java.time`, which needs API 26 or core-library desugaring (formats.md §2
 * rule 9, architecture.md D-18). Digits are ASCII whatever the default locale. Civil-date arithmetic
 * after H. Hinnant, "chrono-compatible low-level date algorithms".
 */
object IsoTime {
    const val MS_PER_DAY = 86_400_000L

    /** Days since 1970-01-01 of the proleptic Gregorian date [year]-[month]-[day]. */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    /** The date of [days] since 1970-01-01: year, month (1 to 12), day (1 to 31). */
    fun civilFromDays(days: Long): IntArray {
        val z = days + 719_468
        val era = (if (z >= 0) z else z - 146_096) / 146_097
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return intArrayOf(y, m, d)
    }

    fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeapYear(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /**
     * `2026-10-06T07:05:09.123Z`: UTC with milliseconds, the shape of JavaScript's
     * `Date.toISOString()` and of Bitwarden's files. A year outside 0 to 9999 is written as
     * JavaScript writes it, with a sign and six digits or more.
     */
    fun format(ms: Long): String {
        val days = ms.floorDiv(MS_PER_DAY)
        val inDay = ms.mod(MS_PER_DAY)
        val date = civilFromDays(days)
        val sb = StringBuilder(24)
        val year = date[0]
        if (year in 0..9999) {
            pad(sb, year.toLong(), 4)
        } else {
            sb.append(if (year < 0) '-' else '+')
            pad(sb, kotlin.math.abs(year.toLong()), 6)
        }
        sb.append('-')
        pad(sb, date[1].toLong(), 2)
        sb.append('-')
        pad(sb, date[2].toLong(), 2)
        sb.append('T')
        pad(sb, inDay / 3_600_000, 2)
        sb.append(':')
        pad(sb, inDay / 60_000 % 60, 2)
        sb.append(':')
        pad(sb, inDay / 1_000 % 60, 2)
        sb.append('.')
        pad(sb, inDay % 1_000, 3)
        sb.append('Z')
        return sb.toString()
    }

    /** Appends [value] (not negative) in ASCII digits, with leading zeros up to [width]. */
    private fun pad(sb: StringBuilder, value: Long, width: Int) {
        val digits = value.toString() // Long.toString writes ASCII digits in every locale
        for (k in digits.length until width) sb.append('0')
        sb.append(digits)
    }

    private val ISO = Regex(
        """^\s*([+-]\d{6}|\d{4})-(\d{2})-(\d{2})""" +
            """(?:[Tt ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?""" +
            """\s*([Zz]|[+-]\d{2}(?::?\d{2})?)?\s*$""",
    )

    /**
     * Parses the date forms that Bitwarden, KeePassXC and spreadsheets write: a date, optionally a
     * time (`T`, `t` or a space between) with optional seconds and fraction (`.` or `,`, up to nine
     * digits, cut to milliseconds), optionally `Z` or an offset `±HH`, `±HHMM` or `±HH:MM`. A time
     * without an offset is UTC. Returns null for anything else, including a day the month does not
     * have; the importer then keeps "unknown". A second of 60 (a leap second) is the next second.
     */
    fun parse(s: String?): Long? {
        if (s == null || s.length > 64) return null
        val m = ISO.matchEntire(s) ?: return null
        val g = m.groupValues
        val year = g[1].toInt() // "+012345" and "-000001" parse too
        val month = g[2].toInt()
        val day = g[3].toInt()
        if (month !in 1..12 || day !in 1..daysInMonth(year, month)) return null
        val hour = if (g[4].isEmpty()) 0 else g[4].toInt()
        val minute = if (g[5].isEmpty()) 0 else g[5].toInt()
        val second = if (g[6].isEmpty()) 0 else g[6].toInt()
        if (hour > 23 || minute > 59 || second > 60) return null
        val millis = if (g[7].isEmpty()) 0 else g[7].padEnd(3, '0').substring(0, 3).toInt()
        var ms = daysFromCivil(year, month, day) * MS_PER_DAY +
            hour * 3_600_000L + minute * 60_000L + second * 1_000L + millis
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val oh = digits.substring(0, 2).toInt()
            val om = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            if (oh > 23 || om > 59) return null
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
