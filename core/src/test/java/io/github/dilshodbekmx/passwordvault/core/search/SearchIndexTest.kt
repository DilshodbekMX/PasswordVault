package io.github.dilshodbekmx.passwordvault.core.search

import io.github.dilshodbekmx.passwordvault.core.search.SampleVault.index
import io.github.dilshodbekmx.passwordvault.core.search.SampleVault.indices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {

    @Test
    fun userExamplesAllFindTheSameEntries() {
        val queries = listOf("o'zbek", "o\u02BBzbek", "o\u2018zbek", "ozbek", "ўзбек", "ЎЗБЕК")
        for (q in queries) {
            assertEquals(q, listOf(0, 1, 22), indices(q).take(3))
        }
    }

    @Test
    fun telegramInBothScripts() {
        for (q in listOf("telegram", "телеграм", "Телеграм", "TELEGRAM")) assertEquals(q, 2, indices(q).first())
    }

    @Test
    fun sberFindsSberbank() {
        assertEquals(3, indices("сбер").first())
        assertEquals(3, indices("sber").first())
    }

    @Test
    fun clickFindsClickUz() = assertEquals(4, indices("click").first())

    @Test
    fun emptyQueryListsEverythingInOrder() {
        val all = SampleVault.entries.indices.toList()
        for (q in listOf("", "   ", "'", "\u02BB", " \u2019 ")) assertEquals("[$q]", all, indices(q))
        assertTrue(index.search("").all { it.score == 0 && it.field == null })
    }

    @Test
    fun startOfWordRanksAboveInsideWord() {
        // Bank card (start of title) > Uzum Bank, Ipak Yoʻli banki (start of a word) >
        // Sberbank, Kapitalbank (inside a word); equal scores keep the list order.
        assertEquals(listOf(24, 10, 12, 3, 9), indices("bank"))
    }

    @Test
    fun titleRanksAboveUsernameAndWebsite() {
        // Mail.ru (title start) > Gmail (inside the title) > a user name containing "mail".
        assertEquals(listOf(13, 6, 1), indices("mail"))
    }

    @Test
    fun usernameAndWebsiteWeighTheSame() {
        // 998... starts the user names of Click and Payme (start of field) but only a later
        // word in Telegram's "+998 90 ..." (start of a word).
        assertEquals(listOf(4, 5, 2), indices("998"))
    }

    @Test
    fun websiteMatchesTheHostOnly() {
        assertEquals(listOf(3), indices("online"))
        assertTrue(indices("csafront").isEmpty()) // path
        assertTrue(indices("https").isEmpty()) // scheme
        assertFalse(indices("www").contains(17)) // www.
        assertFalse(indices("menu").contains(17)) // path
        assertEquals(listOf(17), indices("cafe-central"))
    }

    @Test
    fun subdomainCountsAsTheStartOfAWord() {
        val hit = index.search("google").single()
        assertEquals(6, hit.index)
        assertEquals(MatchField.WEBSITE, hit.field)
        assertEquals(SearchIndex.WEBSITE_WEIGHT + SearchIndex.WORD * SearchIndex.POSITION_STEP, hit.score)
    }

    @Test
    fun queryWordsMatchInAnyOrderAndAnyField() {
        assertEquals(listOf(4), indices("uz click"))
        assertEquals(listOf(6), indices("gmail dilshod"))
        val inOrder = index.search("click uz").single()
        val reversed = index.search("uz click").single()
        assertTrue("phrase bonus", inOrder.score > reversed.score)
    }

    @Test
    fun spacesAndPunctuationAreIgnoredInsideAMatch() {
        assertEquals(4, indices("clickuz").first())
        assertEquals(listOf(13), indices("mailru"))
        assertEquals(listOf(16), indices("wifi"))
        assertEquals(listOf(11), indices("mygov"))
    }

    @Test
    fun aMatchThatRunsIntoTheNextWordMustStartAWord() {
        assertFalse(indices("kuz").contains(4)) // clic[k uz]
        assertTrue(indices("clickuz").contains(4))
    }

    @Test
    fun russianKeyboardSpellingsFormASecondTier() {
        // "узбек" is the Russian spelling: it matches "Узбектелеком" exactly and the Uzbek
        // spellings Oʻzbekiston, Ўзбек..., Özbekiston only in the second tier, 5 points lower.
        assertEquals(listOf(21, 0, 1, 22), indices("узбек"))
        val hits = index.search("узбек")
        assertEquals(hits[0].score - SearchIndex.ALT_PENALTY, hits[1].score)
        assertEquals(listOf(18), indices("хисоб")) // х for ҳ
        assertEquals(listOf(19), indices("кукон")) // к for қ, у for ў
        assertEquals(listOf(20), indices("елка")) // е for ё
        assertEquals(listOf(12), indices("ипак йули"))
    }

    @Test
    fun alphabetOf2026MatchesThe1995Spelling() {
        assertEquals(listOf(23), indices("toshkent"))
        assertEquals(listOf(23), indices("тошкент"))
        assertEquals(listOf(23), indices("toşkent"))
        assertEquals(listOf(0, 1, 22, 21), indices("özbek"))
    }

    @Test
    fun digitsMatch() {
        assertEquals(listOf(18), indices("2024"))
    }

    @Test
    fun emojiAndOtherScriptsMatchThemselves() {
        assertEquals(listOf(16), indices("🔑"))
        assertEquals(listOf(14), indices("微信"))
        assertEquals(listOf(14), indices("wechat"))
        assertEquals(listOf(15), indices("الراجحي"))
    }

    @Test
    fun composedAndDecomposedQueriesAreEqual() {
        assertEquals(listOf(17), indices("cafe\u0301"))
        assertEquals(listOf(17), indices("CAFÉ"))
        assertEquals(listOf(17), indices("cafe"))
    }

    @Test
    fun punctuationOnlyQueryIsLiteral() {
        assertEquals(listOf(1, 6, 13, 17), indices("@"))
        assertEquals(MatchField.USERNAME, index.search("@").first().field)
    }

    @Test
    fun notesAndExtraFieldsRankLast() {
        val note = index.search("router").single()
        assertEquals(MatchField.NOTES, note.field)
        assertEquals(SearchIndex.NOTES_WEIGHT + SearchIndex.PREFIX * SearchIndex.POSITION_STEP, note.score)
        val extra = index.search("code").single()
        assertEquals(24, extra.index)
        assertEquals(MatchField.EXTRA, extra.field)
        assertEquals(listOf(24), indices("mother"))
    }

    @Test
    fun equalScoresKeepTheListOrder() {
        // Whole user name "dilshod" first (in list order), then user names starting with it.
        assertEquals(
            listOf(0, 7, 8, 9, 10, 12, 15, 18, 20, 21, 22, 23, 1, 3, 6, 13, 14, 17),
            indices("dilshod"),
        )
    }

    @Test
    fun capitalLetterInsideANameStartsAWord() {
        val found = indices("me")
        // "PayMe Business" (M starts a word) ties with "Toşkent metrosi" and ranks above
        // "Payme" (me inside a word), which is still found.
        assertEquals(listOf(23, 25), found.take(2))
        assertTrue(found.indexOf(25) < found.indexOf(5))
        val payMe = SampleVault.index.search("me").first { it.index == 25 }
        assertEquals(SearchIndex.TITLE_WEIGHT + SearchIndex.WORD * SearchIndex.POSITION_STEP, payMe.score)
    }

    @Test
    fun noMatchGivesNothing() = assertTrue(indices("zzzz").isEmpty())

    /** The examples of docs/design/i18n.md §2.7. */
    @Test
    fun i18nDesignTestVectors() {
        val ozbek = listOf("o'zbek", "o\u02BBzbek", "o\u2018zbek", "o\u2019zbek", "o`zbek", "O\u02BCzbek", "Özbek", "ўзбек", "ЎЗБЕК")
        for (q in ozbek) assertEquals(q, "ozbek", searchKey(q))
        for (q in listOf("g\u02BBalaba", "Ğalaba", "ғалаба")) assertEquals(q, "galaba", searchKey(q))
        for (q in listOf("ma\u02BClumot", "ma'lumot", "маълумот")) assertEquals(q, "malumot", searchKey(q))
        assertEquals("shifokor", searchKey("Şifokor"))
        assertEquals("shifokor", searchKey("шифокор"))
        assertEquals("chorsu", searchKey("Çorsu"))
        assertEquals("chorsu", searchKey("Чорсу"))
        assertEquals("sberbank", searchKey("Сбербанк"))
        assertEquals("telegram", searchKey("Телеграм"))
        assertEquals("yoqut", searchKey("Ёқут"))
        assertEquals("yer", searchKey("Ер"))
        assertEquals("hamkorbank", searchKey("Ҳамкорбанк"))
        fun finds(title: String, query: String) = SearchIndex(listOf(SearchFields(title))).search(query).isNotEmpty()
        assertTrue(finds("Ўзбекистон почтаси", "o\u02BBzbek"))
        assertTrue(finds("Ўзбекистон почтаси", "pochta"))
        assertTrue(finds("Семёнов", "семенов"))
        assertTrue(finds("Alyona", "алёна"))
        // i18n.md accepts that this one fails; the Russian-keyboard tier finds it (х ~ h).
        assertTrue(finds("Хамкорбанк", "Hamkorbank"))
    }

    /** The test vectors of docs/design/ux.md §5.11, each as a one-entry vault. */
    @Test
    fun uxDesignTestVectors() {
        fun finds(title: String, query: String) = SearchIndex(listOf(SearchFields(title))).search(query).isNotEmpty()
        for (t in listOf("O\u02BBzbekiston", "O'zbekiston", "O\u2018zbekiston", "Özbekiston")) assertTrue(t, finds(t, "ozbek"))
        assertTrue(finds("Toshkent", "Toşkent"))
        assertTrue(finds("Toşkent", "Toshkent"))
        assertTrue(finds("ma\u02BClumot", "ma'lumot"))
        assertTrue(finds("Ёлка", "елка"))
        assertTrue(finds("Köln", "koln"))
        assertFalse(finds("мой", "мои"))
        assertFalse(finds("мои", "мой"))
    }

    @Test
    fun turkishDefaultLocaleDoesNotChangeMatching() {
        assertEquals(2, indices("TELEGRAM").first())
        assertEquals(listOf(11), indices("XIZMATLARI"))
        assertEquals(listOf(4), indices("CLICK UZ"))
    }

    @Test
    fun honestLimits() {
        // Brand spelled the Russian way: Яндекс folds to "yandeks", not "yandex".
        assertFalse(indices("yandex").contains(8))
        assertTrue(indices("yandeks").contains(8))
        // An English name written in Cyrillic by sound: клик is "klik", not "click".
        assertFalse(indices("клик").contains(4))
        // Russian spelling without ў, searched with plain "o": nothing marks the o as oʻ.
        assertFalse(indices("ozbek").contains(21))
        // ç and ş (search.md §6 item 4). Changed on 2026-10-06 by architecture.md D-11: accent-free
        // typing used to miss them ("garcon" did not find "Garçon"); now the second tier writes ç as
        // c and ş as s, so "garcon" and "besiktas" find them 5 points lower (AccentFreeTierTest).
        // What is still a limit: German ue and oe do not meet ü and ö.
        fun finds(title: String, query: String) = SearchIndex(listOf(SearchFields(title))).search(query).isNotEmpty()
        assertFalse(finds("Müller", "mueller"))
        assertFalse(finds("Mueller", "müller"))
    }

    @Test
    fun largeVaultSmoke() {
        val many = List(5000) { SearchFields("Account $it", "user$it@example.com", listOf("https://site$it.uz/login")) }
        val big = SearchIndex(many)
        assertEquals(5000, big.search("account").size)
        assertEquals(listOf(4321), big.searchIndices("site4321").toList())
        assertEquals(5000, big.searchIndices("").size)
    }
}
