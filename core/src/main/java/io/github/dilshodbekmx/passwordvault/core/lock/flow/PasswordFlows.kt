package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place

/**
 * The flows that change the master password (lock.md §12): **F8** change it, **F11** replace a
 * forgotten one. Build plan 4.4 fills the bodies; until then every function throws
 * `NotImplementedError`, and `LockEngine` already sends its entry points here. Package 4.4 may also
 * edit `LockEngine.kt` and `UnlockFlows.kt`.
 *
 * Each function says whether it **holds the mutex** or **takes the mutex itself**, as in
 * `ShortcutFlows`.
 *
 * **What the core already does, so that it is not done twice.**
 * - The wraps of §5.4 at every unlock, and F8 step 10: `UnlockFlows.completeUnlock` asks storage to
 *   retire the old keys after a master-password unlock and trims both shortcut records with the
 *   `vk_id`s that are still kept; `mp_change` is forgotten by a typed master password
 *   (`MasterPasswordGuard`) and by any unlock that finds no kept generation with its `to_vk_id`.
 * - Process start settles `replaced` (§5.11 step 3) and observes every credit (§6.9), as do
 *   `ON_START`, `ON_STOP`, every unlock and `LockEngine.refresh` (`LockCore.observeCredits`).
 * - The summary and the notices of a pending and of a replaced master password come from the
 *   records (`LockCore.publishSummary`; `completeUnlock` shows `Notice.Pending`, `PendingReady`,
 *   `PendingCancelled` and `Replaced`). A correct current master password cancels a pending one
 *   (`MasterPasswordGuard`, F11 step 9). `EntryPolicy` asks for no practice while one is pending.
 * - On the lock screen a master password that opens no slot while a pending one is ready is handed
 *   to [typedPending], inside the guard's attempt (F11 step 13).
 *
 * **What the core gives.**
 * - `core.unlock.pinInPrompt(pin, useKek)`: the counted PIN check of F8 step 3 and F11 step 3 in the
 *   open app; `useKek` receives `KEK_pin` for F8 step 5 (`PinGuard.changeWraps`).
 * - `core.vault.newKey`, `changePassword`, `replaceVault`; `core.kdf` and `core.aead` for the
 *   pending wrap (§4.2, `AAD_pending` of §4.3); `core.random` for its salt and nonce.
 * - `core.lockOf(vaultId).master.update(bestEffort = false)` for a write that must reach the disk
 *   (F11 step 15: "If the write fails … the flow stops").
 * - After a call that wrote vault data: `core.rescan()`, so that the next unlock trims the wraps
 *   with the keys that are kept now, and `core.publishSummary()`.
 * - `LockVisit.practiceSkipped` and `LockVisit.afterUnlock` for a flow that begins on the lock
 *   screen ([cantRemember]).
 */
internal class PasswordFlows(private val core: LockCore) {

    /**
     * Takes the mutex itself. F8 steps 2 to 9, with [proof] the current master password of this
     * flow, [first] and [again] the two typings of the new one (compared before anything is
     * derived), and [pin] the PIN typed at step 3 to keep it, or null. The order of steps 5 to 8 is
     * decision 20: `mp_change`, the PIN's wrap for the new key, the vault, the fingerprint.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeOutcome =
        notYet("F8: change the master password")

    /**
     * Holds the mutex. "I can't remember it" on a practice screen (F10 step 4): F11 from the lock
     * screen. Practice is skipped for this visit (`LockVisit.practiceSkipped`), so that the PIN, or
     * the fingerprint when no PIN exists, opens the app as an ordinary entry of the visit (F11 step
     * 3); `LockVisit.afterUnlock` says how it was opened.
     *
     * The first option of the "Forgot master password?" sheet, "Use my PIN" or "Use my fingerprint"
     * (F11 step 1, F17 step 1), arrives here too: the screens send the same intent
     * (`LockIntent.CantRemember`) from any master-password screen of the current vault, a practice
     * screen or not.
     */
    suspend fun cantRemember(): Unit = notYet("F10 step 4, F11: I can't remember it")

    /**
     * Takes the mutex itself. F11 steps 3 to 5: the proof now (the PIN whenever there is one, a
     * fingerprint only when there is none, never one in place of the other), then the pending wrap
     * under [first], compared with [again], written to `state.rec`.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestOutcome =
        notYet("F11: ask for a new master password")

    /** Holds the mutex. F11 step 8, "Cancel it": `pending_mp = null`; no proof. Also the answer CANCEL_IT of its notices. */
    suspend fun cancelPending(): Unit = notYet("F11 step 8: cancel the pending master password")

    /**
     * Takes the mutex itself. "Finish now" (F11 steps 13 to 19) in the open app: [chars] must open
     * the pending wrap of a request whose 24 hours are credited; then the mark, the new vault, its
     * lock directory.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun finishPending(chars: CharArray): MasterPasswordOutcome = notYet("F11 steps 13 to 19: finish with the new master password")

    /**
     * Holds the mutex, inside `MasterPasswordGuard.attempt` on the lock screen. F11 step 13: no
     * password slot of [lock]'s vault opened with [passwordBytes], and its pending password is
     * ready (`LockCore.pendingReady`). One more Argon2id over the pending salt; if the wrap opens,
     * storage opens the vault with the VK it gives and steps 14 to 19 follow, for the visit [at].
     *
     * @return what the entry came to when the pending wrap opened (the guard then gives the try
     *   back); null when it did not open, and the password is wrong.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun typedPending(lock: VaultLock, passwordBytes: ByteArray, at: Place): MasterPasswordOutcome? =
        notYet("F11 step 13: the pending master password typed on the lock screen")

    private fun notYet(what: String): Nothing = throw NotImplementedError("$what comes with build plan 4.4 (PasswordFlows)")
}
