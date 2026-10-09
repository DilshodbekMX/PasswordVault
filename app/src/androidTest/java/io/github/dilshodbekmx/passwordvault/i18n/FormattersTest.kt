package io.github.dilshodbekmx.passwordvault.i18n

import android.icu.util.VersionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.TimeZone

/**
 * Build plan 2.9, [device] on API 24, 28 and 37: [Formatters] in the four languages, on the ICU of
 * each Android version (architecture.md §8.3, i18n.md §4.6). On Android 17 (ICU 78) the dates,
 * times and numbers are those of i18n.md §4.6's table; Android 7 (ICU 56) and 9 (ICU 60) write some
 * of them differently, which is the phone's own format, so each version has its expected texts.
 */
@RunWith(AndroidJUnit4::class)
class FormattersTest {
    private val icu = VersionInfo.ICU_VERSION.major
    private lateinit var zone: TimeZone

    /** 6 October 2026, 14:05 in Tashkent (09:05 UTC), the example of i18n.md §4.6. */
    private val at = 1_791_277_500_000L

    @Before
    fun inTashkent() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tashkent"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun f(tag: String, hour24: Boolean = true) = Formatters(Locale.forLanguageTag(tag), hour24)

    /** U+202F, which ICU 78 writes before "PM" and "г.". */
    private val narrow = if (icu >= 72) " " else " "

    @Test
    fun datesAsI18nSection4Point6ShowsThem() {
        assertEquals("Oct 6, 2026", f("en").date(at))
        assertEquals("6 Oct 2026", f("en-GB").date(at))
        assertEquals("6-okt, 2026", f("uz").date(at))
        assertEquals(if (icu == 56) "2026 Окт 6" else "6 окт, 2026", f("uz-Cyrl").date(at))
        assertEquals("6 окт. 2026${narrow}г.", f("ru").date(at))

        assertEquals("Oct 6", f("en").dayMonth(at))
        assertEquals("6-okt", f("uz").dayMonth(at))
        assertEquals(if (icu == 56) "Окт 6" else "6 окт", f("uz-Cyrl").dayMonth(at))
        assertEquals("6 окт.", f("ru").dayMonth(at))
    }

    @Test
    fun timesFollowThePhonesTwelveOrTwentyFourHourClock() {
        for (tag in listOf("en", "uz", "uz-Cyrl", "ru")) assertEquals(tag, "14:05", f(tag).time(at))
        assertEquals("2:05${narrow}PM", f("en", hour24 = false).time(at))
        assertEquals("2:05${narrow}TK", f("uz", hour24 = false).time(at))
        val ruPm = when {
            icu == 56 -> "2:05 ПП"
            else -> "2:05${narrow}PM"
        }
        assertEquals(ruPm, f("ru", hour24 = false).time(at))
        assertEquals("Oct 6, 2026, 14:05", f("en").dateTime(at))
        assertEquals("6-okt, 2026, 14:05", f("uz").dateTime(at))
        assertEquals("6 окт. 2026${narrow}г., 14:05", f("ru").dateTime(at))
    }

    @Test
    fun numbersAreGroupedAsTheLanguageGroupsThem() {
        assertEquals("1,234,567", f("en").number(1_234_567L))
        for (tag in listOf("uz", "uz-Cyrl", "ru")) assertEquals(tag, "1 234 567", f(tag).number(1_234_567L))
        assertEquals("48", f("ru").number(48L))
    }

    @Test
    fun relativeTimesInTheFourLanguages() {
        val ago = listOf(10_000L, 5 * MINUTE, 3 * HOUR, DAY, 3 * DAY, 15 * DAY, 70 * DAY, 400 * DAY)
        fun past(tag: String) = ago.map { f(tag).relative(at - it, at) }
        assertEquals(
            listOf("now", "5 minutes ago", "3 hours ago", "yesterday", "3 days ago", "2 weeks ago", "3 months ago", "1 year ago"),
            past("en"),
        )
        assertEquals(
            listOf("hozir", "5 daqiqa oldin", "3 soat oldin", "kecha", "3 kun oldin", "2 hafta oldin", "3 oy oldin", "1 yil oldin"),
            past("uz"),
        )
        assertEquals(
            listOf("ҳозир", "5 дақиқа олдин", "3 соат олдин", "кеча", "3 кун олдин", "2 ҳафта олдин", "3 ой аввал", "1 йил аввал"),
            past("uz-Cyrl"),
        )
        assertEquals(
            listOf("сейчас", "5 минут назад", "3 часа назад", "вчера", "3 дня назад", "2 недели назад", "3 месяца назад", "1 год назад"),
            past("ru"),
        )
        assertEquals("tomorrow", f("en").relative(at + DAY, at))
        assertEquals("in 3 days", f("en").relative(at + 3 * DAY, at))
        assertEquals("через 5 минут", f("ru").relative(at + 5 * MINUTE, at))
        assertEquals(if (icu == 56) "5 daqiqadan so‘ng" else "5 daqiqadan keyin", f("uz").relative(at + 5 * MINUTE, at))
    }

    /**
     * Less than a minute, before or after, is the screen's "just now" (`common_just_now`, i18n.md
     * §4.6), not ICU's "now", which reads wrong in "Password changed now"; a minute is ICU's again.
     */
    @Test
    fun lessThanAMinuteBeforeOrAfterIsJustNow() {
        for (tag in listOf("en", "uz", "uz-Cyrl", "ru")) {
            val f = Formatters(Locale.forLanguageTag(tag), hour24 = true, justNow = "just now ($tag)")
            for (span in listOf(0L, 1L, 10_000L, MINUTE - 1)) {
                assertEquals("$tag, $span ms before", "just now ($tag)", f.relative(at - span, at))
                assertEquals("$tag, $span ms after", "just now ($tag)", f.relative(at + span, at))
            }
        }
        val en = Formatters(Locale.forLanguageTag("en"), hour24 = true, justNow = "just now")
        assertEquals("1 minute ago", en.relative(at - MINUTE, at))
        assertEquals("in 1 minute", en.relative(at + MINUTE, at))
        assertEquals("1 minute ago", en.relative(at - MINUTE - 59_999L, at))
        // Without it (a Formatters made outside a screen), ICU's own word, as relativeTimesInTheFourLanguages shows.
        assertEquals("now", f("en").relative(at - 10_000L, at))
    }

    @Test
    fun yesterdayIsTheCalendarDayBefore() {
        // 00:30 on 7 October is 10 hours and 25 minutes after 14:05 on the 6th: hours, not "yesterday".
        val justAfterMidnight = at + 10 * HOUR + 25 * MINUTE
        assertEquals("10 hours ago", f("en").relative(at, justAfterMidnight))
        // 25 hours later is the next calendar day: "yesterday"; 47 hours later is two calendar days on.
        assertEquals("yesterday", f("en").relative(at, at + 25 * HOUR))
        assertEquals("2 days ago", f("en").relative(at, at + 47 * HOUR))
    }

    @Test
    fun theCountdownOfTheClipboardRow() {
        for (tag in listOf("en", "uz", "uz-Cyrl", "ru")) {
            val f = f(tag)
            assertEquals("0:52", f.countdown(51_001L))
            assertEquals("0:52", f.countdown(52_000L))
            assertEquals("1:00", f.countdown(60_000L))
            assertEquals("10:01", f.countdown(601_000L))
            assertEquals("0:00", f.countdown(0L))
            assertEquals("0:00", f.countdown(-5L))
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 3_600_000L
        const val DAY = 86_400_000L
    }
}
