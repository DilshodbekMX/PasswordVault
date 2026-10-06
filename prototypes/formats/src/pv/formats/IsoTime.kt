package pv.formats

/**
 * ISO 8601 date helpers without java.time (API 26, or desugaring, on minSdk 24).
 * Civil-date arithmetic after H. Hinnant, "chrono-compatible low-level date algorithms".
 */
object IsoTime {
    private const val MS_PER_DAY = 86_400_000L

    fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = (if (m <= 2) y0 - 1 else y0).toLong()
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Returns year, month (1-12), day (1-31). */
    fun civilFromDays(z0: Long): IntArray {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return intArrayOf(y, m, d)
    }

    /** "2026-10-06T07:05:09.123Z", the shape JavaScript's Date.toISOString() and Bitwarden write. */
    fun format(ms: Long): String {
        val days = Math.floorDiv(ms, MS_PER_DAY)
        val rem = Math.floorMod(ms, MS_PER_DAY)
        val (y, mo, d) = civilFromDays(days).let { Triple(it[0], it[1], it[2]) }
        val h = rem / 3_600_000; val mi = rem / 60_000 % 60; val s = rem / 1000 % 60; val f = rem % 1000
        // Locale.ROOT: some locales format %d with non-ASCII digits.
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ", y, mo, d, h, mi, s, f)
    }

    private val ISO = Regex(
        """^\s*(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?\s*$""",
    )

    /**
     * Parses the date forms that Bitwarden, KeePassXC and spreadsheets write. A time without an offset
     * is taken as UTC. Returns null for anything else (the importer then keeps "unknown").
     */
    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val m = ISO.matchEntire(s) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..31) return null
        val h = g[4].ifEmpty { "0" }.toInt(); val mi = g[5].ifEmpty { "0" }.toInt(); val sec = g[6].ifEmpty { "0" }.toInt()
        if (h > 23 || mi > 59 || sec > 60) return null
        val frac = g[7].padEnd(3, '0').take(3).ifEmpty { "0" }.toInt()
        var ms = daysFromCivil(y, mo, d) * MS_PER_DAY + h * 3_600_000L + mi * 60_000L + sec * 1000L + frac
        val tz = g[8]
        if (tz.isNotEmpty() && tz != "Z") {
            val sign = if (tz[0] == '-') -1 else 1
            val digits = tz.substring(1).replace(":", "")
            val oh = digits.take(2).toInt(); val om = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
            ms -= sign * (oh * 3_600_000L + om * 60_000L)
        }
        return ms
    }
}
