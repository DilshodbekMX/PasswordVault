package io.github.dilshodbekmx.passwordvault.i18n

import android.icu.text.DateFormat
import android.icu.text.NumberFormat
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Calendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Dates, times, relative times and numbers on the screens, in one language (i18n.md §4.6; build plan
 * 2.9): `android.icu` with [locale], in the phone's time zone, with the phone's 12- or 24-hour clock
 * ([hour24]). Times are kept as milliseconds since 1970 UTC (architecture.md D-18) and shown in the
 * zone the phone has when they are formatted. ICU decides the wording, so Android 7 (ICU 56) writes
 * some dates differently from Android 17 (ICU 78): that is the phone's own format (i18n.md §4.6).
 *
 * Make one per screen language with [rememberFormatters]; [locale] is then
 * [AppLanguages.screenLocale] of the composition's configuration. Durations in settings ("After 30
 * seconds") and counted waits ("in about 5 hours") are plural strings of their screens, not these.
 * Thread-safe; the ICU objects are made on first use.
 */
class Formatters(val locale: Locale, private val hour24: Boolean) {
    private val uLocale = ULocale.forLocale(locale)
    private val dates = HashMap<String, DateFormat>()
    private var numbers: NumberFormat? = null
    private var relatives: RelativeDateTimeFormatter? = null

    /** The date with a short month: "Oct 6, 2026", "6-okt, 2026", "6 окт, 2026", "6 окт. 2026 г." (skeleton yMMMd). */
    fun date(ms: Long): String = format("yMMMd", ms)

    /** The day and short month without the year: "Sep 12", "12 сент." (skeleton MMMd), as in "since 12 Sep". */
    fun dayMonth(ms: Long): String = format("MMMd", ms)

    /** The time of day in the phone's 12- or 24-hour clock: "2:05 PM" or "14:05". */
    fun time(ms: Long): String = format(if (hour24) "Hm" else "hm", ms)

    /** [date] and [time] together, as the language joins them. */
    fun dateTime(ms: Long): String = format(if (hour24) "yMMMdHm" else "yMMMdhm", ms)

    /** A whole number with the language's grouping: "1,234,567" or "1 234 567". */
    @Synchronized
    fun number(value: Long): String {
        val format = numbers ?: NumberFormat.getInstance(uLocale).also { numbers = it }
        return format.format(value)
    }

    /**
     * How long ago [thenMs] was at [nowMs], or how far ahead, in words: "now" under a minute, then
     * minutes, hours under a day, "yesterday" or "tomorrow" for the day before or after, days under a
     * week, weeks under 31 days, months under a year, then years ("5 minutes ago", "3 days ago",
     * "2 months ago"; "kecha", "вчера"). Days and months are counted on the calendar of the phone's
     * time zone; the number is rounded down. Fit for "Last backup: 3 days ago".
     */
    @Synchronized
    fun relative(thenMs: Long, nowMs: Long): String {
        val format = relatives ?: RelativeDateTimeFormatter.getInstance(uLocale).also { relatives = it }
        val direction = if (thenMs <= nowMs) RelativeDateTimeFormatter.Direction.LAST else RelativeDateTimeFormatter.Direction.NEXT
        val spanMs = if (thenMs <= nowMs) nowMs - thenMs else thenMs - nowMs
        fun count(n: Long, unit: RelativeDateTimeFormatter.RelativeUnit) = format.format(n.toDouble(), direction, unit)
        if (spanMs < 0 || spanMs < MINUTE_MS) {
            return format.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
        }
        if (spanMs < HOUR_MS) return count(spanMs / MINUTE_MS, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
        if (spanMs < DAY_MS) return count(spanMs / HOUR_MS, RelativeDateTimeFormatter.RelativeUnit.HOURS)
        val zone = zone()
        val days = abs(localDay(nowMs, zone) - localDay(thenMs, zone))
        if (days <= 1) return format.format(direction, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
        if (days < 7) return count(days, RelativeDateTimeFormatter.RelativeUnit.DAYS)
        if (days < 31) return count(days / 7, RelativeDateTimeFormatter.RelativeUnit.WEEKS)
        val months = max(1L, abs(monthIndex(nowMs, zone) - monthIndex(thenMs, zone)))
        if (months < 12) return count(months, RelativeDateTimeFormatter.RelativeUnit.MONTHS)
        return count(months / 12, RelativeDateTimeFormatter.RelativeUnit.YEARS)
    }

    /**
     * The time left until a planned clear, in minutes and seconds, rounded up: "0:52" in "Copied text ·
     * clears in 0:52" (ux.md §6, i18n.md glossary 149). Never below "0:00".
     */
    fun countdown(remainingMs: Long): String {
        val seconds = (max(remainingMs, 0L) + 999L) / 1000L
        return String.format(locale, "%d:%02d", seconds / 60L, seconds % 60L)
    }

    @Synchronized
    private fun format(skeleton: String, ms: Long): String {
        val format = dates.getOrPut(skeleton) { DateFormat.getInstanceForSkeleton(skeleton, uLocale) }
        format.timeZone = zone()
        return format.format(Date(ms))
    }

    /** The phone's time zone now: it may change while the app runs. */
    private fun zone(): TimeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)

    /** The day number of [ms] on the calendar of [zone]. */
    private fun localDay(ms: Long, zone: TimeZone): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)

    /** Year × 12 + month of [ms] on the calendar of [zone]. */
    private fun monthIndex(ms: Long, zone: TimeZone): Long {
        val calendar = Calendar.getInstance(zone, uLocale)
        calendar.timeInMillis = ms
        return calendar.get(Calendar.YEAR) * 12L + calendar.get(Calendar.MONTH)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 86_400_000L
    }
}

/**
 * The [Formatters] of this composition: the language its screens show
 * ([AppLanguages.screenLocale] of `LocalConfiguration`) and the phone's 12- or 24-hour setting. Made
 * again when either changes, as after a language switch, which recreates the activity.
 */
@Composable
fun rememberFormatters(): Formatters {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val locale = AppLanguages.screenLocale(AppLanguages.localesOf(configuration))
    val hour24 = android.text.format.DateFormat.is24HourFormat(context)
    return remember(locale, hour24) { Formatters(locale, hour24) }
}
