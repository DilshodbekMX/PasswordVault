package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.FIRST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.LAST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.MIDDLE
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.ONLY
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build plan §12, `w5-carry` step 2, [JVM]: how the list groups its items on cards where the style
 * does (Emerald; docs/design/looks/README.md "List and account screen", ux.md §5.11).
 */
class ListCardsTest {

    @Test
    fun theFavouritesAreOneCard_withShowAllFavouritesLast() {
        assertEquals(listOf(ONLY), ListCards.favourites(1, showAll = false))
        assertEquals(listOf(FIRST, MIDDLE, LAST), ListCards.favourites(3, showAll = false))
        // Ten favourites shown and "Show all favourites" under them: the button closes the card.
        val withButton = ListCards.favourites(10, showAll = true)
        assertEquals(11, withButton.size)
        assertEquals(FIRST, withButton.first())
        assertEquals(List(9) { MIDDLE }, withButton.subList(1, 10))
        assertEquals(LAST, withButton.last())
    }

    @Test
    fun allAccountsAreOneCard_lettersAndRows() {
        // "A" with two rows, "B" with one, "C" with three.
        val parts = ListCards.allAccounts(listOf(2, 1, 3))
        assertEquals(3, parts.size)
        assertEquals(FIRST, parts[0].header)
        assertEquals(listOf(MIDDLE, MIDDLE), parts[0].rows)
        assertEquals(MIDDLE, parts[1].header)
        assertEquals(listOf(MIDDLE), parts[1].rows)
        assertEquals(MIDDLE, parts[2].header)
        assertEquals(listOf(MIDDLE, MIDDLE, LAST), parts[2].rows)
        // One account: its letter opens the card, its row closes it.
        val one = ListCards.allAccounts(listOf(1)).single()
        assertEquals(FIRST, one.header)
        assertEquals(listOf(LAST), one.rows)
        assertEquals(emptyList<SectionParts>(), ListCards.allAccounts(emptyList()))
    }

    @Test
    fun theResultsAreOneCard_andRecentlyDeletedACardOfItsOwn() {
        assertEquals(listOf(ONLY), ListCards.results(1))
        assertEquals(listOf(FIRST, LAST), ListCards.results(2))
        assertEquals(ONLY, ListCards.recentlyDeleted)
    }
}
