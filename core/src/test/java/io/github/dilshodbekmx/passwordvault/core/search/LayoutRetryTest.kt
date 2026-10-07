package io.github.dilshodbekmx.passwordvault.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SearchIndex.searchOrRetry]: a query that finds nothing in either tier is retried once as typed
 * on the other keyboard layout (search.md §2, architecture.md D-11, ux.md §5.11).
 */
class LayoutRetryTest {

    private val vault = listOf(
        SearchFields("Instagram", "dilshod"), // 0
        SearchFields("Привет банк", "dilshod"), // 1
        SearchFields("Telegram", "+998 90 123 45 67"), // 2
        SearchFields("Beşiktaş JK", "fan"), // 3
    )
    private val index = SearchIndex(vault)

    private fun bold(text: String, query: String) = matchedRanges(text, query).map { text.substring(it.first, it.last + 1) }

    @Test
    fun shtyeRetriedGivesTheHitsOfInst() {
        assertTrue(index.search("штые").isEmpty())
        val outcome = index.searchOrRetry("штые")
        assertEquals(SearchOutcome("inst", index.search("inst"), retried = true), outcome)
        assertEquals(listOf(0), outcome.hits.map { it.index })
    }

    @Test
    fun latinKeysRetriedAsCyrillic() {
        val outcome = index.searchOrRetry("ghbdtn")
        assertEquals(SearchOutcome("привет", index.search("привет"), retried = true), outcome)
        assertEquals(listOf(1), outcome.hits.map { it.index })
    }

    @Test
    fun theRetriedQueryIsTheOneMadeBold() {
        val outcome = index.searchOrRetry("штые")
        assertEquals(listOf("Inst"), bold(vault[0].title, outcome.query))
        assertEquals(emptyList<String>(), bold(vault[0].title, "штые"))
    }

    @Test
    fun aQueryThatFindsSomethingIsNotRetried() {
        assertEquals(SearchOutcome("inst", index.search("inst"), retried = false), index.searchOrRetry("inst"))
        // Found in the second tier only is found: the retry is for a query that finds nothing.
        val accentFree = index.searchOrRetry("besiktas")
        assertFalse(accentFree.retried)
        assertEquals(listOf(3), accentFree.hits.map { it.index })
    }

    @Test
    fun aShortQueryOnTheWrongLayoutThatMatchesIsNotRetried() {
        // search.md §6 item 12: "ш" (the key of i) folds to "sh", which Beşiktaş and dilshod
        // contain, so its matches are shown and nothing is retried.
        val outcome = index.searchOrRetry("ш")
        assertFalse(outcome.retried)
        assertEquals("ш", outcome.query)
        assertEquals(listOf(3, 0, 1), outcome.hits.map { it.index })
    }

    @Test
    fun aRetryThatFindsNothingKeepsTheQuery() {
        assertEquals(SearchOutcome("zzzz", emptyList(), retried = false), index.searchOrRetry("zzzz"))
        assertEquals(SearchOutcome("яяяя", emptyList(), retried = false), index.searchOrRetry("яяяя"))
    }

    @Test
    fun aQueryWithNothingToSwitchIsNotRetried() {
        assertEquals(SearchOutcome("55555", emptyList(), retried = false), index.searchOrRetry("55555"))
    }

    @Test
    fun anEmptyQueryListsEverythingAndAnEmptyVaultFindsNothing() {
        assertEquals(SearchOutcome("", index.search(""), retried = false), index.searchOrRetry(""))
        assertEquals(4, index.searchOrRetry("").hits.size)
        assertEquals(SearchOutcome("штые", emptyList(), retried = false), SearchIndex(emptyList()).searchOrRetry("штые"))
    }
}
