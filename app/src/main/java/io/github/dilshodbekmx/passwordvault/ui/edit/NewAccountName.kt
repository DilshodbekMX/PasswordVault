package io.github.dilshodbekmx.passwordvault.ui.edit

/**
 * 'Add "gmal" as a new account' (ux.md §5.11): the list hands what was typed in its search field to
 * the new-account form it opens, which starts with it as the account's name. A route key holds only
 * ids and saved state never a search query (architecture.md §3.6, §5.6), so the text waits here, in
 * memory only, for that one form: the list calls [offer] just before it opens `Route.Edit()`, and the
 * form of a new account calls [take] when its ViewModel is made, which uses it up. The list calls
 * [clear] for a plain "Add" and when the vault locks; a new process has none. Called on the main
 * thread; volatile only so that a test on another thread reads what a tap left.
 */
object NewAccountName {
    @Volatile private var offered: String? = null

    /** The list is about to open a new form for [text], the query that found nothing. */
    fun offer(text: String) {
        offered = text
    }

    /** Nothing is to be handed over: a plain "Add", or the vault locked. */
    fun clear() {
        offered = null
    }

    /** What was offered for the form that is being made, once; null when nothing was. */
    fun take(): String? {
        val text = offered
        offered = null
        return text
    }
}
