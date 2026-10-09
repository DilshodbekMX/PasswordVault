package io.github.dilshodbekmx.passwordvault.ui.entry

import io.github.dilshodbekmx.passwordvault.ui.common.CardPart
import io.github.dilshodbekmx.passwordvault.ui.theme.CardsLook

/**
 * Which rows of an account's screen lie on cards, and where on them, in a look whose cards are
 * [cards] (docs/design/looks/README.md "List and account screen"; ux.md §5.12):
 * - [CardsLook.ROW_GROUPS] (Emerald): the values (the username, the password or the old and the new
 *   one, the websites, the notes, the extra fields) on one card, and the previous passwords, while
 *   they are shown, on another;
 * - [CardsLook.PASSWORD_BLOCK] (Night vault): the password block, the password or the old and the
 *   new one while a change is pending, on a card;
 * - [CardsLook.NONE]: none.
 *
 * The name, the banners, "No details yet", the dates and "Previous passwords (2)" always lie on the
 * screen. A row that is not in the map lies on the screen.
 */
internal fun entryCardParts(details: EntryDetails, cards: CardsLook): Map<EntryValue, CardPart> = when (cards) {
    CardsLook.NONE -> emptyMap()
    CardsLook.PASSWORD_BLOCK -> grouped(details.passwords.map { it.value })
    CardsLook.ROW_GROUPS -> {
        val values = buildList {
            if (details.username.isNotEmpty()) add(EntryValue.Username)
            details.passwords.forEach { add(it.value) }
            details.websites.forEach { add(it.value) }
            if (details.notes.isNotEmpty()) add(EntryValue.Notes)
            details.fields.forEach { add(it.value) }
        }
        grouped(values) + grouped(details.previous.map { it.value })
    }
}

/** [values] on one card, in their order. */
private fun grouped(values: List<EntryValue>): Map<EntryValue, CardPart> =
    values.mapIndexed { index, value -> value to CardPart.of(index, values.size) }.toMap()
