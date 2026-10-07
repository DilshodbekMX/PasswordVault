package io.github.dilshodbekmx.passwordvault.fake

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.search.SearchFields
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import io.github.dilshodbekmx.passwordvault.data.ListModel
import io.github.dilshodbekmx.passwordvault.data.ListModelSource
import io.github.dilshodbekmx.passwordvault.data.Section
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale

/**
 * A [ListModelSource] whose [model] a test sets (architecture.md §2.2 `fake`), directly or with
 * [show], which builds the list from entries the way `ListModelProvider` (build plan 3.1) does:
 * deleted entries left out, A to Z by the [Collation], cut at its headers, and the search index
 * over the same order (search.md §7).
 */
class FakeListModelSource(initial: ListModel? = null) : ListModelSource {
    override val model = MutableStateFlow(initial)

    /** Shows the list of [entries], ordered by [collation]. */
    fun show(entries: List<Entry>, collation: Collation = FakeCollation) {
        model.value = build(entries, collation)
    }

    companion object {
        /** The list of [entries] in display order (ux.md §5.11): never a password in the index. */
        fun build(entries: List<Entry>, collation: Collation = FakeCollation): ListModel {
            val shown = entries.filter { it.deletedAt == null }.sortedWith { a, b -> collation.compare(a.title, b.title) }
            val sections = mutableListOf<Section>()
            var header: String? = null
            var run = mutableListOf<Entry>()
            for (entry in shown) {
                val h = collation.header(entry.title)
                if (h != header && run.isNotEmpty()) {
                    sections += Section(header!!, run)
                    run = mutableListOf()
                }
                header = h
                run += entry
            }
            if (run.isNotEmpty()) sections += Section(header!!, run)
            val index = SearchIndex(
                shown.map { e ->
                    SearchFields(
                        title = e.title,
                        username = e.username,
                        websites = e.urls,
                        extras = e.fields.flatMap { f -> if (f.secret) listOf(f.name) else listOf(f.name, f.value) },
                        notes = e.notes,
                    )
                },
            )
            return ListModel(shown.filter { it.favourite }, sections, index)
        }
    }
}

/**
 * A simple [Collation] for tests and previews: titles compared without case (root locale), and the
 * header is the first letter in upper case, or "#" for anything else. The real one is `SortKeys`.
 */
object FakeCollation : Collation {
    override fun compare(a: String, b: String): Int {
        val byCase = a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT))
        return if (byCase != 0) byCase else a.compareTo(b)
    }

    override fun header(title: String): String {
        val first = title.trimStart().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase(Locale.ROOT) else "#"
    }
}
