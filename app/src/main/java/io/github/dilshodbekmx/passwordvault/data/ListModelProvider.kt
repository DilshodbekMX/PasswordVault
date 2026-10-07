package io.github.dilshodbekmx.passwordvault.data

import io.github.dilshodbekmx.passwordvault.core.model.Entry
import io.github.dilshodbekmx.passwordvault.core.search.SearchFields
import io.github.dilshodbekmx.passwordvault.core.search.SearchIndex
import io.github.dilshodbekmx.passwordvault.i18n.Collation
import io.github.dilshodbekmx.passwordvault.i18n.SortKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The Passwords list of the open vault (architecture.md §3.5 `ListModelSource`, §5.1; ux.md §5.11;
 * search.md §7): the entries outside Recently deleted, A to Z in the language the screens show, cut
 * at the letter headers, the favourites apart, and the `SearchIndex` over the same order.
 *
 * - **Rebuilt when the vault or the language changes.** [vaults] is `VaultRepository.state`; [locales]
 *   is the locale the screens sort in, and [collationOf] makes its `Collation` (`SortKeys` in the
 *   app), once per locale. A new vault whose entries are the very list that was built from (a save
 *   that changed only vault-level data) is not built again.
 * - **Dropped with the vault.** [model] is null while the vault is locked. The change to null is
 *   made in the thread that publishes `VaultState.Locked`, before that publication returns, and a
 *   build still running for the vault that went is never published.
 * - **Off the main thread.** Sorting and the index are built on [dispatcher] (`Dispatchers.Default`,
 *   architecture.md §5.3); the newest vault and language win: an older build is cancelled and stops
 *   at its next step, and one already running for the same entries and language is not started again.
 * - **Never a secret in the index:** a password, a previous password or the value of a hidden field
 *   is not put into `SearchFields` (search.md §7). The name of a hidden field is.
 *
 * Call [start] once.
 */
class ListModelProvider(
    private val vaults: StateFlow<VaultState>,
    private val locales: StateFlow<Locale>,
    private val collationOf: (Locale) -> Collation,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) : ListModelSource {
    private val lock = Any()
    private val current = MutableStateFlow<ListModel?>(null)
    override val model: StateFlow<ListModel?> = current.asStateFlow()

    private var build: Job? = null // guarded by lock
    private var buildFrom: List<Entry>? = null // guarded by lock: the entries of the build running
    private var buildFor: Locale? = null // guarded by lock: its locale
    private var builtFrom: List<Entry>? = null // guarded by lock: the entries of the model shown
    private var builtFor: Locale? = null // guarded by lock: its locale
    private var collation: Pair<Locale, Collation>? = null // guarded by lock: the last one made

    /**
     * Follows [vaults] and [locales] from now on, in [scope]. Both are collected unconfined, so that
     * a lock drops the list at once, in the thread that locked.
     */
    fun start() {
        scope.launch(Dispatchers.Unconfined) { vaults.collect { refresh() } }
        scope.launch(Dispatchers.Unconfined) { locales.collect { refresh() } }
    }

    private fun refresh() {
        synchronized(lock) {
            val state = vaults.value
            if (state !is VaultState.Unlocked) {
                build?.cancel()
                build = null
                buildFrom = null
                buildFor = null
                builtFrom = null
                builtFor = null
                current.value = null
                return
            }
            val entries = state.vault.entries
            val locale = locales.value
            if (current.value != null && builtFrom === entries && builtFor == locale) return
            if (build?.isActive == true && buildFrom === entries && buildFor == locale) return // already on its way
            build?.cancel()
            buildFrom = entries
            buildFor = locale
            build = scope.launch(dispatcher) {
                val made = collationFor(locale)
                ensureActive()
                val list = buildList(entries, made) { ensureActive() }
                ensureActive()
                synchronized(lock) {
                    val now = vaults.value
                    if (now is VaultState.Unlocked && now.vault.entries === entries && locales.value == locale) {
                        current.value = list
                        builtFrom = entries
                        builtFor = locale
                    }
                }
            }
        }
    }

    private fun collationFor(locale: Locale): Collation {
        synchronized(lock) { collation?.let { (made, c) -> if (made == locale) return c } }
        val made = collationOf(locale)
        synchronized(lock) { collation = locale to made }
        return made
    }

    internal companion object {
        /**
         * The list of [entries] in display order (ux.md §5.11): Recently deleted left out, A to Z by
         * [collation] (by collation keys when it is `SortKeys`), entries that sort alike in the order
         * of the vault, a section for each run of one header, and the index over the same order.
         * [checkpoint] runs between the steps: a build that is no longer wanted stops there.
         */
        fun buildList(entries: List<Entry>, collation: Collation, checkpoint: () -> Unit = {}): ListModel {
            val shown = entries.filter { it.deletedAt == null }
            val sorted = if (collation is SortKeys) {
                shown.map { it to collation.key(it.title) }.sortedWith { a, b -> a.second.compareTo(b.second) }.map { it.first }
            } else {
                shown.sortedWith { a, b -> collation.compare(a.title, b.title) }
            }
            checkpoint()
            val sections = ArrayList<Section>()
            var header: String? = null
            var run = ArrayList<Entry>()
            for (entry in sorted) {
                val h = collation.header(entry.title)
                if (h != header && run.isNotEmpty()) {
                    sections += Section(header!!, run)
                    run = ArrayList()
                }
                header = h
                run += entry
            }
            if (run.isNotEmpty()) sections += Section(header!!, run)
            checkpoint()
            val index = SearchIndex(sorted.map(::searchFieldsOf))
            return ListModel(sorted.filter { it.favourite }, sections, index)
        }

        /** What the index may hold of [entry] (search.md §7): never its password, its previous passwords or a hidden value. */
        fun searchFieldsOf(entry: Entry): SearchFields = SearchFields(
            title = entry.title,
            username = entry.username,
            websites = entry.urls,
            extras = entry.fields.flatMap { field -> if (field.secret) listOf(field.name) else listOf(field.name, field.value) },
            notes = entry.notes,
        )
    }
}
