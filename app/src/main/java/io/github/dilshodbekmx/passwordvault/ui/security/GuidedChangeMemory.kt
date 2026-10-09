package io.github.dilshodbekmx.passwordvault.ui.security

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.data.VaultState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the guided change (ux.md §5.17) remembers outside the account, in memory only. The change
 * itself is in the account (`changePending`, `pendingOldPassword`), which survives a lock and the
 * end of the process; two things are not, and live here, as ids and times only (nothing secret):
 *
 * - **The end of a change.** After "Yes, it's changed" the screen shows "Gmail now has its own
 *   password." with "Next: Facebook" or "Done for now". A lock takes the screen's ViewModel away and
 *   brings the route back after the unlock (architecture.md §5.5), and the account alone cannot say
 *   that its change has just ended, so the end is marked here ([markDone]) until the screen is left
 *   while the app is unlocked ([forget]: Back, "Next", "Done for now"). A mark stands only while the
 *   account is as "Yes, it's changed" left it ([doneAt] gives the account's `updatedAt` then); a new
 *   process has none.
 * - **"Once per session"** for the backup line ("Gmail's new password is only on this phone until
 *   you make a backup."): a session is the time from an unlock to the next lock. [markDone] decides
 *   whether the end it marks shows the line, at most once per session; the end keeps its line for as
 *   long as it is marked.
 *
 * [of] gives the process's one, which watches the vault's state from the moment the app's routes are
 * registered, so that every lock ends a session. Main thread only.
 */
class GuidedChangeMemory(vaultState: StateFlow<VaultState>, scope: CoroutineScope) {
    private class Done(val updatedAt: Long, val backupLine: Boolean)

    private val done = HashMap<String, Done>()

    /** The backup line was shown in this session. */
    private var backupLineShown = false

    init {
        scope.launch {
            vaultState.collect { if (it is VaultState.Locked) backupLineShown = false }
        }
    }

    /**
     * "Yes, it's changed" ended the change of [entryId], whose `updatedAt` is now [updatedAt]. The
     * end shows the backup line when [notInBackup] (the account's change is in no backup yet) and the
     * line was not shown in this session; the return value says whether it does.
     */
    fun markDone(entryId: String, updatedAt: Long, notInBackup: Boolean): Boolean {
        val line = notInBackup && !backupLineShown
        if (line) backupLineShown = true
        done[entryId] = Done(updatedAt, line)
        return line
    }

    /** The `updatedAt` of [entryId] when its change ended here; null when no end is marked. */
    fun doneAt(entryId: String): Long? = done[entryId]?.updatedAt

    /** Whether the marked end of [entryId] shows the backup line. */
    fun backupLine(entryId: String): Boolean = done[entryId]?.backupLine == true

    /** The end of the change of [entryId] is no longer shown. */
    fun forget(entryId: String) {
        done.remove(entryId)
    }

    companion object {
        @Volatile
        private var instance: GuidedChangeMemory? = null

        /** The process's one, watching the vault from its first use on. */
        fun of(graph: AppGraph): GuidedChangeMemory = instance ?: synchronized(this) {
            instance ?: GuidedChangeMemory(graph.data.repository.state, graph.appScope).also { instance = it }
        }
    }
}
