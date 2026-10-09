package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.FIRST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.LAST
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.MIDDLE
import io.github.dilshodbekmx.passwordvault.ui.common.CardPart.ONLY
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.OLD_PASSWORD
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.bare
import io.github.dilshodbekmx.passwordvault.ui.entry.EntryKit.Companion.gmail
import io.github.dilshodbekmx.passwordvault.ui.theme.CardsLook
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Build plan §12, `w5-carry` step 2, [JVM]: which rows of an account's screen lie on cards, and
 * where (docs/design/looks/README.md "List and account screen", ux.md §5.12), in each kind of look:
 * Emerald's values on one card and its previous passwords on another, Night vault's password block,
 * Bank blue's none.
 */
class EntryCardsTest {

    private fun details(entry: Entry = gmail(), previousExpanded: Boolean = false) =
        EntryDetails.of(entry, sameAsMasterPassword = false, revealed = emptySet(), notesExpanded = false, previousExpanded = previousExpanded)

    @Test
    fun emeraldGroupsTheValuesOnOneCard_inTheirOrder() {
        // Gmail: a username, a password, two websites, notes and two extra fields.
        val parts = entryCardParts(details(), CardsLook.ROW_GROUPS)
        assertEquals(
            linkedMapOf<EntryValue, CardPart>(
                EntryValue.Username to FIRST,
                EntryValue.Password to MIDDLE,
                EntryValue.Website(0) to MIDDLE,
                EntryValue.Website(1) to MIDDLE,
                EntryValue.Notes to MIDDLE,
                EntryValue.Field(0) to MIDDLE,
                EntryValue.Field(1) to LAST,
            ),
            parts,
        )
    }

    @Test
    fun emeraldPutsThePreviousPasswordsOnACardOfTheirOwn_whileTheyAreShown() {
        val open = entryCardParts(details(previousExpanded = true), CardsLook.ROW_GROUPS)
        assertEquals(FIRST, open[EntryValue.Previous(0)])
        assertEquals(LAST, open[EntryValue.Previous(1)])
        assertEquals(LAST, open[EntryValue.Field(1)])
        assertEquals(null, entryCardParts(details(previousExpanded = false), CardsLook.ROW_GROUPS)[EntryValue.Previous(0)])
    }

    @Test
    fun nightVaultPutsThePasswordBlockOnACard() {
        assertEquals(mapOf(EntryValue.Password to ONLY), entryCardParts(details(), CardsLook.PASSWORD_BLOCK))
        // While a change is pending, the old and the new password are the block.
        val pending = details(gmail(changePending = true, pendingOldPassword = OLD_PASSWORD), previousExpanded = true)
        assertEquals(mapOf(EntryValue.OldPassword to FIRST, EntryValue.Password to LAST), entryCardParts(pending, CardsLook.PASSWORD_BLOCK))
        // An account without a password has no block.
        assertEquals(emptyMap<EntryValue, CardPart>(), entryCardParts(details(gmail(password = "")), CardsLook.PASSWORD_BLOCK))
    }

    @Test
    fun bankBlueHasNoCards_andANameAloneNoneEither() {
        assertEquals(emptyMap<EntryValue, CardPart>(), entryCardParts(details(previousExpanded = true), CardsLook.NONE))
        assertEquals(emptyMap<EntryValue, CardPart>(), entryCardParts(details(bare()), CardsLook.ROW_GROUPS))
    }
}
