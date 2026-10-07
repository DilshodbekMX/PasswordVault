package io.github.dilshodbekmx.passwordvault.i18n

import android.icu.util.VersionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Build plan 2.9, [device] on API 24, 28 and 37: the Uzbek and Russian order and letter headers of
 * ux.md §5.11 and i18n.md §2.7 through [SortKeys], on the ICU of each Android version
 * (architecture.md §8.3): Android 7 has ICU 56, which has no Uzbek order, Android 9 ICU 60 and
 * Android 17 ICU 78, which sort Oʻ, Gʻ, Sh and Ch after Z as the alphabet does.
 */
@RunWith(AndroidJUnit4::class)
class SortKeysTest {
    private val icu = VersionInfo.ICU_VERSION.major

    /** Whether this Android's ICU has the Uzbek order (ICU 60 and 78 here; ICU 56 has none). */
    private val uzbekOrder = icu >= 60

    private fun keys(tag: String) = SortKeys(Locale.forLanguageTag(tag))

    /** [titles] in the order of [tag], each with its header. */
    private fun list(tag: String, titles: List<String>): List<Pair<String, String>> {
        val keys = keys(tag)
        return titles.sortedWith(keys::compare).map { it to keys.header(it) }
    }

    private val uzbekTitles = listOf(
        "Zamin", "Choyxona", "Shahar", "Sanoat", "Gʻalaba", "Golf", "Oʻzbekiston banki", "Olma", "Click", "Xalq banki",
        "Ғалаба", "Қишлоқ", "Алиса", "Ҳамкорбанк", "123",
    )

    @Test
    fun uzbekLatinOrderAndHeaders() {
        val expected = if (uzbekOrder) {
            listOf(
                "123" to "#", "Click" to "C", "Golf" to "G", "Olma" to "O", "Sanoat" to "S", "Xalq banki" to "X", "Zamin" to "Z",
                "Oʻzbekiston banki" to "Oʻ", "Gʻalaba" to "Gʻ", "Shahar" to "Sh", "Choyxona" to "Ch",
                "Алиса" to "А", "Ғалаба" to "Ғ", "Қишлоқ" to "Қ", "Ҳамкорбанк" to "Ҳ",
            )
        } else {
            // ICU 56: no Uzbek order, so C, G, O and S, which again match its sort (ux.md §5.11).
            listOf(
                "123" to "#", "Choyxona" to "C", "Click" to "C", "Gʻalaba" to "G", "Golf" to "G", "Oʻzbekiston banki" to "O",
                "Olma" to "O", "Sanoat" to "S", "Shahar" to "S", "Xalq banki" to "X", "Zamin" to "Z",
                "Алиса" to "А", "Ғалаба" to "Г", "Қишлоқ" to "К", "Ҳамкорбанк" to "Х",
            )
        }
        assertEquals("ICU $icu", expected, list("uz", uzbekTitles))
    }

    @Test
    fun uzbekCyrillicOrderAndHeaders() {
        // ICU's Uzbek Cyrillic order is the Uzbek one: Latin titles too sort Oʻ, Gʻ, Sh and Ch after Z,
        // and SortKeys gives them those headers (not "Z").
        val expected = if (uzbekOrder) list("uz", uzbekTitles) else list("en", uzbekTitles).map { (title, header) -> title to header }
        assertEquals("ICU $icu", expected, list("uz-Cyrl", uzbekTitles))
        if (uzbekOrder) assertEquals("Sh", keys("uz-Cyrl").header("Shahar"))
    }

    @Test
    fun russianOrderAndHeaders() {
        val titles = listOf("Яндекс", "Ёлка", "Ежик", "Елена", "Алиса", "Ўзбекистон", "Telegram", "Apple", "Шахар", "Щука", "1С", "Ωmega")
        // Cyrillic before Latin; ё sorts with е, Ў with У; Greek after both.
        val expected = listOf(
            "1С" to "#", "Алиса" to "А", "Ежик" to "Е", "Елена" to "Е", "Ёлка" to "Е", "Ўзбекистон" to "У", "Шахар" to "Ш",
            "Щука" to "Щ", "Яндекс" to "Я", "Apple" to "A", "Telegram" to "T", "Ωmega" to SortKeys.OVERFLOW_LABEL,
        )
        assertEquals("ICU $icu", expected, list("ru", titles))
        val headers = keys("ru").headers
        assertTrue(headers.indexOf("Я") < headers.indexOf("A"))
    }

    @Test
    fun englishPutsLatinBeforeCyrillicWithRussianHeaders() {
        val titles = listOf("Яндекс", "apple", "Zamin", "Apple", "Алиса", "Ўзбекистон", "#tag", "日本")
        val expected = listOf(
            "#tag" to "#", "apple" to "A", "Apple" to "A", "Zamin" to "Z", "Алиса" to "А", "Ўзбекистон" to "У", "Яндекс" to "Я",
            "日本" to SortKeys.OVERFLOW_LABEL,
        )
        assertEquals("ICU $icu", expected, list("en", titles))
    }

    @Test
    fun theApostropheSpellingsOfOʻSortTogetherInEveryLanguage() {
        // i18n.md §2.7: without the sort text, Russian and English put these three apart.
        val titles = listOf("O‘zbekneftgaz", "Ozon", "O'zbekiston pochtasi", "Olma", "Oʻzbekiston banki", "Oʼzbek tili")
        for (tag in listOf("uz", "uz-Cyrl", "en", "ru")) {
            val sorted = list(tag, titles)
            val names = sorted.map { it.first }
            val together = listOf("Oʼzbek tili", "Oʻzbekiston banki", "O'zbekiston pochtasi", "O‘zbekneftgaz")
            val at = names.indexOf(together.first())
            assertEquals("$tag on ICU $icu: $names", together, names.subList(at, at + together.size))
            assertEquals("$tag: one header for all four", 1, sorted.filter { it.first in together }.map { it.second }.distinct().size)
        }
    }

    @Test
    fun headersFollowTheOrderAndHeadOneRunEach() {
        val titles = uzbekTitles + listOf(
            "O'zbekiston pochtasi", "O‘zbekneftgaz", "Telegram", "Yandex", "Ipak yoʻli", "Nargiz", "Hamkorbank", "Ёқут", "Ер",
            "Ёлка", "Ежик", "Елена", "Apple", "apple", "Сбербанк", "Шахар", "Щука", "Эхо", "Юла", "Яндекс", "Ўзбекистон", "1С",
            "#tag", "Ωmega", "日本", "ابجد", "Ünal", "Çorsu", "Şifokor", "", " Bo'sh joy", "...",
        )
        for (tag in listOf("uz", "uz-Cyrl", "en", "ru")) {
            val keys = keys(tag)
            val order = keys.headers
            val runs = titles.sortedWith(keys::compare).map(keys::header).fold(mutableListOf<String>()) { acc, h ->
                if (acc.lastOrNull() != h) acc += h
                acc
            }
            assertEquals("$tag on ICU $icu: each header heads one run: $runs", runs.distinct(), runs)
            assertEquals("$tag on ICU $icu: runs in the index's order: $runs", runs.sortedBy(order::indexOf), runs)
            assertTrue("$tag: every header is one the index gives", runs.all { it in order })
        }
    }

    @Test
    fun collationKeysSortAsCompareDoes() {
        val titles = uzbekTitles + listOf("O'zbekiston pochtasi", "Яндекс", "Ёлка", "Елена", "apple", "Apple")
        for (tag in listOf("uz", "uz-Cyrl", "en", "ru")) {
            val keys = keys(tag)
            assertEquals(tag, titles.sortedWith(keys::compare), titles.sortedBy(keys::key))
        }
    }

    @Test
    fun theLanguageTheScreensShowGivesItsOwnOrder() {
        assertEquals(Locale.forLanguageTag("uz-Cyrl"), SortKeys.of(AppLanguage.UZ_CYRL).locale)
        assertEquals("Ш", SortKeys.of(AppLanguage.RU).header("Шахар"))
        assertEquals(if (uzbekOrder) "Sh" else "S", SortKeys.of(AppLanguage.UZ_LATN).header("Shahar"))
        assertEquals("S", SortKeys.of(AppLanguage.EN).header("Shahar"))
    }
}
