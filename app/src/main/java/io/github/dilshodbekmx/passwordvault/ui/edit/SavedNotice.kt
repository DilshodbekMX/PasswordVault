package io.github.dilshodbekmx.passwordvault.ui.edit

/**
 * "A new account opens its detail screen with the snackbar 'Saved'" (ux.md §5.14). Save of a new
 * account opens `Route.Entry(id)` in place of the form, and a route key holds only the id, so the
 * form leaves this one-time mark: the entry detail screen (build plan 3.7) calls [take] with its id
 * when it opens and shows "Saved" when it gets true. The mark is in memory only (an id, nothing
 * secret); a later save or [take] replaces or uses it up, and a new process has none. Main thread only.
 */
object SavedNotice {
    private var added: String? = null

    /** Save has just added the account [entryId]. */
    fun mark(entryId: String) {
        added = entryId
    }

    /** Whether the account [entryId] was just added; true once, then the mark is gone. */
    fun take(entryId: String): Boolean {
        if (added != entryId) return false
        added = null
        return true
    }
}
