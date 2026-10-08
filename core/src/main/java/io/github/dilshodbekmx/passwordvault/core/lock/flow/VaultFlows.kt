package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.model.Vault

/**
 * The flows that make, move and merge vaults (lock.md §12): **F12** backup and export, **F13**
 * restore on a new phone, **F17** start with an empty app and previous passwords. Build plan 3.14
 * fills the bodies; until then every function throws `NotImplementedError`, and `LockEngine`
 * already sends its entry points here.
 *
 * Each function says whether it **holds the mutex** or **takes the mutex itself**, as in
 * `ShortcutFlows`.
 *
 * **What the core already does, so that it is not done twice.**
 * - **F14**, a vault without lock records: process start reads three MISSING records (`StartUp`),
 *   `EntryPolicy` shows the master-password screen titled "Welcome back", and after a correct
 *   master password `UnlockFlows.completeUnlock` writes `state.rec` (§5.3: step 0, `last_ok` now)
 *   and offers "Choose a PIN" with the proof of that unlock (F1 step 5).
 * - **F18**, a phone that cannot keep keys in secure hardware: `LockCore.canHoldKeys` is F1 step 5;
 *   when it says `NoSecureHardware` no PIN is offered, the summary says so for the PIN and the
 *   fingerprint, and the reason `MpReason.NoSecureHardware` is shown until the engine's owner has
 *   called `LockEngine.noSecureHardwareShown` ("first time only").
 * - **F1**: `UnlockFlows.newVault` makes a vault, its `state.rec` and its first proof; [restore]
 *   uses it with the backup's `Vault` and `restored = true` (practice step 3).
 * - The lock screen of any vault: `UnlockFlows` works on `LockCore.target`, which may be a vault put
 *   aside. Its pad, fingerprint and master-password field then use that vault's own records and
 *   counts (`core.lockOf(vaultId)`), and `VaultPort.open` and `openWithPassword` open it in place.
 *   What is left for this file is to put that target up ([showAside]), to take it down ([goBack]),
 *   and what follows when it has opened ([asideOpened]).
 * - The master-password check in the open app, for a backup or an export: `LockEngine.checkMasterPassword`.
 *
 * **What the core gives.**
 * - `core.scan` lists the vaults put aside (`core.rescan()` after every call that moved one);
 *   `core.lockOf(vaultId)` is that vault's lock state, read from its own directory.
 * - `core.vault` is the port: `putAside`, `swapIn`, `mergeAside`, `deleteAside`. The engine runs
 *   under `NonCancellable` inside `LockCore.locked`, so their answers are never dropped.
 * - `core.unlock.relock()` shows the lock screen (or Welcome) of what is current now;
 *   `core.unlock.completeUnlock` is everything an unlock does once the vault that opened is the
 *   current one.
 * - The lock never deletes a lock directory except after `VaultPort.deleteAside` returned true
 *   (architecture.md §3.4): `core.files.deleteDir`, then `core.deviceKeys.delete` for its two
 *   aliases, then `core.forgetLock(vaultId)`.
 */
internal class VaultFlows(private val core: LockCore) {

    /**
     * Takes the mutex itself. F13 steps 4 to 6, and F17 step 3 when a vault is on the phone:
     * [first] and [again] are the backup's master password (the backup was opened with it, so both
     * are the same typing), [from] the vault it held. `UnlockFlows.newVault` with `restored = true`,
     * and `putCurrentAside` when a vault is current. The vault that goes aside keeps its lock
     * directory and its keys (F17: the lock does nothing to them).
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun restore(first: CharArray, again: CharArray, from: Vault, created: suspend () -> Unit): CreateOutcome =
        notYet("F13, F17 step 3: restore from a backup file")

    /**
     * Holds the mutex. F17 step 2, "Start with an empty app", offered when no shortcut is usable now:
     * `VaultPort.putAside`. The lock does nothing else: `lock/<vault_id>/` and its keys stay.
     * Welcome follows (`core.unlock.relock()`). False: nothing was put aside.
     */
    suspend fun startEmpty(): Boolean = notYet("F17 step 2: start with an empty app")

    /**
     * Holds the mutex. F17 step 4, "Open my previous passwords": the vault [vaultId] put aside shows
     * its own lock screen. `core.target` becomes a `LockTarget(VaultRef.Aside(vaultId), core.lockOf(vaultId), <its AsideVault>)`,
     * a visit starts (`core.startVisit()` runs that vault's counter start-up step, §6.3 S), and
     * `core.showLockScreen()` lets `EntryPolicy` choose among its pad, fingerprint-only and
     * master-password screen, without practice, and with the master password only for a vault
     * marked `replaced`.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun showAside(vaultId: String): Unit = notYet("F17 step 4: the lock screen of a vault put aside")

    /**
     * Holds the mutex. The vault put aside of [target] has opened in place, by [by]. For one marked
     * `replaced` the question of F16b step 3 comes first: `OlderCopies.asideFromBeforeOpened`.
     * Otherwise F17 step 4: `VaultPort.swapIn` puts the current vault aside and makes this one
     * current; then `core.rescan()`, `core.retarget()` and `core.unlock.completeUnlock`. If the swap
     * fails the vault is dropped again (`VaultPort.lock`) and its lock screen stays.
     *
     * @return the proof of [proofBytes] when the master password opened it, else null.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun asideOpened(target: LockTarget, by: UnlockBy, opened: OpenVault, proofBytes: ByteArray?): MasterProof? =
        notYet("F17 step 4: a vault put aside has opened")

    /** Holds the mutex. Back on the lock screen of a vault put aside: the current vault's screen (or Welcome) again; nothing changed. */
    suspend fun goBack(): Unit = notYet("F17 step 4: back from the lock screen of a vault put aside")

    /**
     * Takes the mutex itself. F17 step 5, Settings › Old passwords › "Open": [chars] is that vault's
     * own master password, checked under that vault's own guard (`core.lockOf(vaultId).master`,
     * §6.8) in a prompt of the open app; then `VaultPort.mergeAside` adds its accounts to the
     * current vault.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun mergeAside(vaultId: String, chars: CharArray): AsideOutcome = notYet("F17 step 5: add the accounts of a vault put aside")

    /**
     * Takes the mutex itself. F17 step 5, "Delete": that vault's own master password and nothing
     * else, under its own guard. If another vault's `replaced` names it, that other vault is settled
     * first as process start would (§5.11 step 3, first row). Then `VaultPort.deleteAside`, and only
     * after it returned true the lock directory and the two aliases go.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideOutcome = notYet("F17 step 5: delete a vault put aside")

    /**
     * Holds the mutex. F12 step 2: the master password was typed a moment ago in this flow (the
     * unlocked state still holds its proof) and the app says a backup is due: `Notice.BackupNow` is
     * added (`core.addNotice`). Not while a new master password is pending (F11 step 10).
     */
    suspend fun offerBackup(): Unit = notYet("F12: offer a backup on the entry just made")

    private fun notYet(what: String): Nothing = throw NotImplementedError("$what comes with build plan 3.14 (VaultFlows)")
}
