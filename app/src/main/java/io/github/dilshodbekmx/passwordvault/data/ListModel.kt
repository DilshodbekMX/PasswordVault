package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import kotlinx.coroutines.flow.StateFlow

/**
 * The Passwords list in display order (ux.md §5.11, search.md §7), derived from the open vault and,
 * for the order and the letter headers, from the app's language through `Collation`. It holds the
 * entries that are not deleted; Recently deleted reads the vault itself.
 *
 * @property favourites the favourite entries, A to Z.
 * @property sections every entry A to Z, cut at the letter headers; favourites are in them too.
 * @property index the search index over [entries], in that order, so that a `SearchHit.index` is a
 *   position in [entries]. It never holds a password, a previous password or a secret field value
 *   (search.md §7).
 */
class ListModel(val favourites: List<Entry>, val sections: List<Section>, val index: SearchIndex) {
    /** Every entry of [sections], in display order: the order of [index]. */
    val entries: List<Entry> by lazy { sections.flatMap { it.entries } }

    override fun toString(): String = "ListModel(favourites=${favourites.size}, sections=${sections.size}, entries=${entries.size})"
}

/** One letter header of the list and its entries, A to Z (ux.md §5.11, "Letter headers"). */
class Section(val header: String, val entries: List<Entry>) {
    override fun toString(): String = "Section(entries=${entries.size})"
}

/**
 * The list of the open vault: `ListModelProvider` (build plan 3.1). Null while locked; rebuilt when
 * the vault or the language changes (architecture.md §5.1), and dropped with the vault on lock.
 */
interface ListModelSource {
    val model: StateFlow<ListModel?>
}
