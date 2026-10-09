package io.github.dilshodbekmx.passwordvault.ui.list

/**
 * "This account no longer exists." (ux.md §5.12, "States"; architecture.md §3.6): an account's
 * screen whose account is not in the vault at all (removed for good from Recently deleted, or gone
 * with a restore or an undone import while its route was kept) goes back to the list by itself and
 * leaves this one-time mark; the list takes it each time it is shown again
 * ([PasswordsViewModel.shown]) and says so once in its snackbar. A route key holds ids only, so the
 * mark waits here, in memory only: a flag, not even the account's id, and nothing secret. A later
 * [mark] or [take] replaces or uses it up, the list [clear]s it when the vault locks, and a new
 * process has none. Called on the main thread; volatile only so that a test on another thread reads
 * what a screen left.
 */
object GoneNotice {
    @Volatile private var marked = false

    /** An account's screen found its account gone and goes back to the list. */
    fun mark() {
        marked = true
    }

    /** Nothing is to be said: the vault locked, or the screen could not go back. */
    fun clear() {
        marked = false
    }

    /** Whether an account's screen just went back because its account is gone; true once, then the mark is gone. */
    fun take(): Boolean {
        val was = marked
        marked = false
        return was
    }
}
