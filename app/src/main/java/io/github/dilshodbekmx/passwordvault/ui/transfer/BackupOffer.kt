package io.github.dilshodbekmx.passwordvault.ui.transfer

import io.github.dilshodbekmx.passwordvault.AppGraph
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice
import io.github.dilshodbekmx.passwordvault.lock.LockUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one exception to "the master password, always" of a backup (ux.md §5.19 step 2; lock.md F12
 * step 2, §8.2): right after the master password was typed in the same flow and a backup is due,
 * the app offers "Make a backup now?", and the backup that "Back up now" starts uses that entry.
 * Nothing else skips the master password: a backup made by someone who has forgotten it would be
 * worthless and look safe.
 *
 * The backup screen takes the entry once, when its ViewModel is made ([take]). Two kinds come here:
 *
 * - **The lock's offer** (lock.md §13.4 item 7, `Notice.BackupNow`), shown after a master password
 *   typed on a lock screen. Its entry is the proof of that unlock, `LockUiState.Unlocked.proof`. The
 *   lock keeps that proof only when the offer is answered with "Back up now"; any other answer drops
 *   it, and so does the app stopping being visible (`Notice.BackupNow`'s KDoc, lock.md §5.8). So an
 *   offer was accepted when the proof the offer was shown with is still the state's proof and the
 *   offer is no longer listed. This object watches the lock's state from the start of the process
 *   ([of] is called when the app's routes are registered) to see which proof an offer was shown
 *   with. The answer "Back up now" of another notice (an older copy was opened, part of the storage
 *   could not be read) is not this offer: a backup it starts asks for the master password.
 * - **A flow of the open app that typed the master password itself** and offers the backup at its
 *   end: the change of the master password (lock.md F8 step 6) and the end of a replacement (F11
 *   step 19, ux.md §5.24), build plan 5.5. It hands its proof to [offer] just before it opens the
 *   backup screen.
 *
 * An entry is given out once. One that nobody takes is dropped when the app locks; the lock drops
 * its own proofs when the app stops being visible. Should the answer not have reached the lock's
 * state yet when the backup screen is made (the lock was busy at that moment), the offer is still
 * listed and the screen asks for the master password: the safe side. Main thread.
 */
class BackupOffer(private val lockState: StateFlow<LockUiState>, scope: CoroutineScope) {
    /** The proof of the unlock on which the lock showed "Make a backup now?", until that unlock ends. */
    private var offeredOn: MasterProof? = null

    /** The proof a flow of the open app handed over ([offer]). */
    private var handed: MasterProof? = null

    init {
        scope.launch { lockState.collect(::seen) }
    }

    /**
     * The backup screen that opens next uses [proof], the master password a flow of the open app has
     * just checked (lock.md F8 step 6, F11 step 19). The flow opens the backup screen right after.
     */
    fun offer(proof: MasterProof) {
        handed?.takeIf { it !== proof }?.drop()
        handed = proof
    }

    /**
     * The entry the backup screen being made may use, or null: it asks for the master password. A
     * proof handed to [offer], else the proof of an unlock whose "Make a backup now?" was answered
     * with "Back up now". Each is given out once.
     */
    fun take(): MasterProof? {
        handed?.let {
            handed = null
            return it.takeIf { lockState.value is LockUiState.Unlocked }
        }
        val state = lockState.value as? LockUiState.Unlocked ?: return null
        val proof = offeredOn ?: return null
        if (state.proof !== proof || state.notices.any { it is Notice.BackupNow }) return null
        offeredOn = null
        return proof
    }

    private fun seen(state: LockUiState) {
        if (state !is LockUiState.Unlocked) {
            offeredOn = null
            handed?.drop()
            handed = null
            return
        }
        val proof = state.proof
        when {
            proof == null -> offeredOn = null
            state.notices.any { it is Notice.BackupNow } -> offeredOn = proof
            proof !== offeredOn -> offeredOn = null
        }
    }

    companion object {
        @Volatile
        private var instance: BackupOffer? = null

        /** The process's one, watching [AppGraph.lock] from its first use on. */
        fun of(graph: AppGraph): BackupOffer = instance ?: synchronized(this) {
            instance ?: BackupOffer(graph.lock.state, graph.appScope).also { instance = it }
        }
    }
}
