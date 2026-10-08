package io.github.dilshodbekmx.passwordvault.core.lock.flow

/**
 * Older copies and older master passwords (lock.md **F16**). Build plan 4.4 fills the bodies; until
 * then every function throws `NotImplementedError`, and `LockEngine` already sends its entry points
 * here. Every function here **holds the mutex**.
 *
 * **What the core already does, so that it is not done twice.**
 * - **F16a**, the newest copy is damaged and storage opens an older one: `EntryPolicy` names the
 *   copy on the master-password screen when no shortcut holds its key (`VaultOpen.NeedsOtherKey`
 *   becomes `LockVisit.needsOtherKeyCreatedMs`), and `UnlockFlows.completeUnlock` shows the
 *   "older copy" notice. No shortcut is removed (I6).
 * - **F16c**, rollback: `completeUnlock` compares the generation that opened with
 *   `max_generation`, stores a higher one and reports a lower one; the answer to that notice makes
 *   the copy that is open the one this phone has opened.
 * - **F16b**, the screen: a master password that opens only a copy from before a change stays
 *   counted (§6.8 step 6), `LockVisit.olderPassword` holds storage's answer, and the lock screen
 *   shows `LockScreen.OlderPassword` until [answer] takes it down. A wrong master password names
 *   the replacement (`LockCore.replacedLine`).
 *
 * **What the core gives.** `core.visit` and its `olderPassword` (the copy that the old password
 * opens: `PasswordOpen.OlderPassword.generation`); `core.vault.putNewerAside`; `core.lockOf(vaultId)`
 * for `mp_change`, `max_generation`, `replaced` and the two slots; `core.vaults.showAside` for the
 * old vault's own lock screen; `core.unlock.completeUnlock`. For the question of F16b step 3:
 * `LockVisit.olderSet` (an `OlderSetQuestion`), which makes the lock screen show
 * `LockScreen.OpenOlderSet` until [answer] takes it off, and whose bytes `LockCore.closeVisit` zeroes
 * when the visit ends; and `core.vault.dropAside` for the vault that opened in place.
 */
internal class OlderCopies(private val core: LockCore) {

    /**
     * An answer on "This is an older master password" (F16b, after an ordinary change). TRY_AGAIN and
     * OK: `LockVisit.olderPassword = null` and the field again. OPEN_FROM_BEFORE, only while
     * `mp_change` is set: storage puts the generations above the copy that opened aside
     * (`VaultPort.putNewerAside`), the old master password is asked for again, and the unlock that
     * follows sets `mp_change = null` and `max_generation` to that copy (steps 2 and 3).
     *
     * And the two answers on "Open the older set instead?" (`LockScreen.OpenOlderSet`, F16b after a
     * replacement, step 3), only while `LockVisit.olderSet` is set. OPEN_OLDER_SET: steps 4 to 6 of
     * [asideFromBeforeOpened]. KEEP_NEWER_SET ("Cancel"): nothing changes; the question is taken off
     * the visit, its bytes are wiped and the vault that opened in place is dropped
     * (`VaultPort.dropAside`). An answer that does not belong to the screen shown does nothing.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun answer(answer: OlderPasswordAnswer): Unit = notYet("F16b: an older master password")

    /**
     * "It wasn't me" on the "replaced" notice, or "Open my passwords from before 7 Oct" on the lock
     * screen (F16b, after a replacement, step 1): the old vault's own lock screen, which has only
     * its master-password field. [oldVaultId] null: the master password was replaced more than
     * once, and the list of the sheet is shown instead.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun itWasntMe(oldVaultId: String?): Unit = notYet("F16b: open the passwords from before a replacement")

    /**
     * The vault of [target], put aside and marked `replaced`, has opened in place with its own
     * master password (F16b steps 3 to 6): the question first; then the swap of F17 step 4, the old
     * vault's records (both slots removed if start-up has not, `pending_mp = null`,
     * `replaced = null`), and "Choose a new PIN. Someone else may know the old one."
     *
     * The question is a screen of its own, and its answer comes in a later call ([answer]): this
     * call puts an `OlderSetQuestion` on the visit (`LockVisit.olderSet`, with [opened] and a copy
     * of [proofBytes]), shows the lock screen again, which is then `LockScreen.OpenOlderSet`, and
     * returns null: the app stays locked until the answer. If the visit ends before an answer, the
     * vault that opened in place is dropped (I11).
     *
     * @return the proof of [proofBytes] once the old vault is the current one, else null.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun asideFromBeforeOpened(target: LockTarget, opened: OpenVault, proofBytes: ByteArray?): MasterProof? =
        notYet("F16b steps 3 to 6: the passwords from before a replacement have opened")

    private fun notYet(what: String): Nothing = throw NotImplementedError("$what comes with build plan 4.4 (OlderCopies)")
}
