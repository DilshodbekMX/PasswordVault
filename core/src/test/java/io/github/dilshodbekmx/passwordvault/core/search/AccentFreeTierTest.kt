package io.github.dilshodbekmx.passwordvault.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * architecture.md D-11: the second tier also writes ş (and Romanian ș) as s and ç as c, so that
 * accent-free Turkish, Azerbaijani and French typing finds them, 5 points below an exact spelling
 * (search.md §1). The first tier still writes them sh and ch, as the 2026 Uzbek letters.
 */
class AccentFreeTierTest {

    private val secondTierWhole = SearchIndex.TITLE_WEIGHT + SearchIndex.WHOLE * SearchIndex.POSITION_STEP - SearchIndex.ALT_PENALTY
    private val firstTierWhole = SearchIndex.TITLE_WEIGHT + SearchIndex.WHOLE * SearchIndex.POSITION_STEP

    private fun hits(titles: List<String>, query: String) = SearchIndex(titles.map { SearchFields(it) }).search(query)

    private fun finds(title: String, query: String) = hits(listOf(title), query).isNotEmpty()

    private fun bold(text: String, query: String) = matchedRanges(text, query).map { text.substring(it.first, it.last + 1) }

    @Test
    fun besiktasFindsBesiktasWithItsLetters() {
        val hit = hits(listOf("Beşiktaş"), "besiktas").single()
        assertEquals(MatchField.TITLE, hit.field)
        assertEquals(secondTierWhole, hit.score)
    }

    @Test
    fun garconFindsGarcon() {
        val hit = hits(listOf("Garçon"), "garcon").single()
        assertEquals(MatchField.TITLE, hit.field)
        assertEquals(secondTierWhole, hit.score)
    }

    @Test
    fun theFirstTierIsUnchanged() {
        assertEquals("beshiktash", searchKey("Beşiktaş"))
        assertEquals("garchon", searchKey("Garçon"))
        for (q in listOf("beshiktash", "бешикташ", "Beşiktaş")) assertEquals(q, firstTierWhole, hits(listOf("Beşiktaş"), q).single().score)
        assertEquals(firstTierWhole, hits(listOf("Garçon"), "garchon").single().score)
        assertEquals(firstTierWhole, hits(listOf("Toşkent"), "toshkent").single().score)
    }

    @Test
    fun theExactSpellingRanksFirst() {
        val vault = listOf("Beşiktaş", "Besiktas")
        assertEquals(listOf(1, 0), hits(vault, "besiktas").map { it.index })
        assertEquals(listOf(0, 1), hits(vault, "beşiktaş").map { it.index })
        assertEquals(listOf(firstTierWhole, secondTierWhole), hits(vault, "beşiktaş").map { it.score })
    }

    @Test
    fun itWorksBothWays() {
        assertTrue(finds("Besiktas", "Beşiktaş"))
        assertTrue(finds("Garcon", "Garçon"))
    }

    @Test
    fun capitalsUnderAnyDefaultLocale() {
        // The build runs these tests under tr-TR (where "I".lowercase(locale) is a dotless ı) and fa-IR.
        assertTrue(finds("BEŞİKTAŞ", "besiktas"))
        assertTrue(finds("Beşiktaş", "BESIKTAS"))
        assertTrue(finds("GARÇON", "garcon"))
    }

    @Test
    fun romanianCommaBelowAndADecomposedCedilla() {
        assertTrue(finds("Timișoara", "timisoara")) // ș, U+0219
        assertTrue(finds("Timişoara", "timisoara")) // ş, U+015F
        val cedilla = Char(0x0327).toString() // composed with its letter by NFKC in step 1
        assertTrue(finds("Bes" + cedilla + "iktas" + cedilla, "besiktas"))
        assertTrue(finds("Garc" + cedilla + "on", "garcon"))
    }

    @Test
    fun theRussianKeyboardSpellingStillReadsShForS() {
        // Why the second tier has two spellings: typed on a Russian keyboard, "кушик" (ш for ş)
        // finds "Qöşiq", the 2026 spelling of қўшиқ. Were ş written s in that spelling too, it
        // would not.
        assertTrue(finds("Qöşiq", "кушик"))
        // Both habits at once: s for ş, and k for q and u for ö.
        assertTrue(finds("Qöşiq", "kusik"))
    }

    @Test
    fun the2026ConsonantsCountBeforeYo() {
        // ş, ç and ğ are consonants, so a yo after them is spelled as е would be there (search.md §1):
        // the 2026 spelling meets щетка as the 1995 "Shyotka" does.
        assertTrue(finds("щетка", "Shyotka"))
        assertTrue(finds("щетка", "Şyotka"))
        assertTrue(finds("Şyotka", "щетка"))
    }

    @Test
    fun theAccentFreeMatchIsBold() {
        assertEquals(listOf("Beşiktaş"), bold("Beşiktaş", "besiktas"))
        assertEquals(listOf("Beşiktaş"), bold("Beşiktaş JK", "besiktas"))
        assertEquals(listOf("Garçon"), bold("Café Garçon", "garcon"))
        assertEquals(listOf("Qöşiq"), bold("Qöşiq kitobi", "кушик"))
        assertEquals(listOf("Qöşiq"), bold("Qöşiq kitobi", "kusik"))
    }
}
