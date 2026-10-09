package io.github.dilshodbekmx.passwordvault.ui.list

import io.github.dilshodbekmx.passwordvault.ui.common.CardPart

/**
 * Where the list's items lie on cards in a style that groups rows on cards (Emerald,
 * `CardsLook.ROW_GROUPS`; docs/design/looks/README.md "List and account screen", ux.md §5.11): the
 * favourites, with "Show all favourites" after them, on one card; every letter header and row of
 * "All accounts" on one card; the search results on one card; "Recently deleted" on a card of its
 * own. The headings "Favourites" and "All accounts · 48", the banners and the empty, loading and
 * no-results states lie on the screen. In the other styles nothing lies on a card, and the screen
 * draws no part.
 */
internal object ListCards {
    /** The parts of [rows] favourites, and last of "Show all favourites" when [showAll]. */
    fun favourites(rows: Int, showAll: Boolean): List<CardPart> = CardPart.group(rows + if (showAll) 1 else 0)

    /** For each section of "All accounts", given by its number of rows: the parts of its letter header and of its rows. */
    fun allAccounts(sections: List<Int>): List<SectionParts> {
        val count = sections.sumOf { it + 1 }
        var index = 0
        return sections.map { rows ->
            val header = CardPart.of(index++, count)
            SectionParts(header, List(rows) { CardPart.of(index++, count) })
        }
    }

    /** The parts of [rows] search results. */
    fun results(rows: Int): List<CardPart> = CardPart.group(rows)

    /** "Recently deleted (3)": a card of its own. */
    val recentlyDeleted: CardPart = CardPart.ONLY
}

/** The parts of one letter header of "All accounts" and of its rows. */
internal class SectionParts(val header: CardPart, val rows: List<CardPart>)
