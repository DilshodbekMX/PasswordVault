package io.github.dilshodbekmx.passwordvault.lock

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MasterProof
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Notice

/**
 * The app's top-level state as the lock decides it (architecture.md §3.5, §5.4; ux.md §4): which of
 * the three back stacks `NavState` shows (setup, locked, unlocked), and what the lock screen shows.
 * Like `LockScreen` and `Notice` it holds facts, never a sentence.
 */
sealed interface LockUiState {
    /** The lock records and the vault files are being read at process start (lock.md §5.11). Nothing is shown. */
    data object Starting : LockUiState

    /**
     * No vault on this phone (lock.md §13.1 row 1): Welcome, with "Open my previous passwords" for
     * each vault in [aside] (F17 step 4).
     */
    data class NoVault(val aside: List<AsideVault>) : LockUiState

    /**
     * No generation can be read (lock.md §13.1 row 2): "Your passwords can't be opened", with "Try
     * again" for [CurrentVault.Why.IO]. Never an offer to delete; [aside] as in [NoVault].
     */
    data class Unreadable(val why: CurrentVault.Why, val aside: List<AsideVault>) : LockUiState

    /** Locked: the lock screen [screen], chosen by `EntryPolicy` (lock.md §13.1). */
    data class Locked(val screen: LockScreen) : LockUiState

    /**
     * Unlocked. [notices] are shown one at a time, in the order of lock.md §13.4, and answered with
     * `LockIntent.NoticeAnswered`. [proof] is the master password of this unlock when it was typed
     * (lock.md §5.8), handed to the next step of the same flow (a PIN after setup or "Forgot PIN?",
     * the backup offer); null after a PIN or fingerprint unlock, and dropped on lock.
     */
    data class Unlocked(val notices: List<Notice>, val proof: MasterProof?) : LockUiState
}
