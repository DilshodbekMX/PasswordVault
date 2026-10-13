package io.github.dilshodbekmx.passwordvault.ui.common

import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.FIRST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.LAST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.MIDDLE
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.ONLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Build plan §12, `w5-carry` step 2, [JVM]: an item's part of a card of rows
 * (docs/design/looks/README.md "List and account screen"): the card is rounded where it starts and
 * where it ends, so a lone item is rounded at both, and the items between are square.
 */
class CardPartTest {

    @Test
    fun aCardIsRoundedWhereItStartsAndWhereItEnds() {
        assertEquals(listOf(ONLY), CardPart.group(1))
        assertEquals(listOf(FIRST, LAST), CardPart.group(2))
        assertEquals(listOf(FIRST, MIDDLE, MIDDLE, LAST), CardPart.group(4))
        assertEquals(emptyList<CardPart>(), CardPart.group(0))
        assertEquals(listOf(true, false, false, true), CardPart.entries.map { it.starts })
        assertEquals(listOf(false, false, true, true), CardPart.entries.map { it.ends })
    }

    @Test
    fun anItemIsInItsGroup() {
        assertEquals(MIDDLE, CardPart.of(1, 3))
        assertThrows(IllegalArgumentException::class.java) { CardPart.of(3, 3) }
        assertThrows(IllegalArgumentException::class.java) { CardPart.of(-1, 3) }
    }
}
