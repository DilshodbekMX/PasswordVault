package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpAttempt
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpCheck
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.record.FileRecordStore
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * The flows that make, move and merge vaults (lock.md §12, build plan 3.14): **F12** backup and
 * export, **F13** restore on a new phone, **F17** start with an empty app and previous passwords.
 * `LockEngine` sends its entry points here.
 *
 * Each function says whether it **holds the mutex** (it is called inside `LockCore.locked`) or
 * **takes the mutex itself**, as in `ShortcutFlows`.
 *
 * **What the core does, so that it is not done twice.**
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
 *   This file puts that target up ([showAside]), takes it down ([goBack]), and says what follows
 *   when it has opened ([asideOpened]).
 * - The master-password check in the open app, for a backup or an export (F12 step 2):
 *   `LockEngine.checkMasterPassword`, which counts as practice and changes no other record.
 *
 * **What this file keeps to.**
 * - Nothing here deletes a vault or its lock state except [deleteAside], after that vault's own
 *   master password, and its lock directory only after `VaultPort.deleteAside` returned true
 *   (architecture.md §3.4). Nothing reachable from the lock screen deletes anything (I9): from
 *   there the strongest steps are putting a vault aside ([startEmpty], [restore]) and making one
 *   current again ([asideOpened]).
 * - The calls that write vault data are only those of F13 and F17 (I1): `createVault` in
 *   [restore], `putAside` in [startEmpty], `swapIn` in [asideOpened], `mergeAside` and
 *   `deleteAside`. [showAside], [goBack] and [offerBackup] never write.
 * - A vault that goes aside keeps its lock directory and its keys (F17 steps 2 and 3, decision 21);
 *   a vault put aside is checked under its own guard (§6.8, F17 steps 4 and 5).
 */
internal class VaultFlows(private val core: LockCore) {

    /**
     * Whether a vault put aside held a pending new master password when its own lock screen was put
     * up ([showAside]): when its master password then opens it, the guard has cancelled that request
     * (F11 step 9), and the unlock that follows the swap says so ("You typed your master password,
     * so the new one was cancelled."). `UnlockFlows` does not hand that fact to [asideOpened].
     */
    private val pendingWhenShown = HashMap<String, Boolean>()

    // ---------------------------------------------------------------------------------------------
    // F13 and F17 step 3: restore from a backup file

    /**
     * Takes the mutex itself. F13 steps 4 to 6, and F17 step 3 when a vault is on the phone. The app
     * has already opened and checked the backup (F13 steps 1 to 3, `VaultRepository.openBackup`,
     * with no waits: a file from outside is never guarded, §6.8); [first] and [again] are its master
     * password (the backup was opened with it, so both are the same typing), [from] the vault it
     * held.
     *
     * - The two typings are compared through `MasterPasswordBytes` before anything is derived.
     *   There is no check for unassigned code points: that rule is for a master password being
     *   created (§8.1), and this one has already opened the backup; refusing it would leave the
     *   backup unrestorable on this phone (requirement 1).
     * - Allowed on Welcome (F13), on "Your passwords can't be opened" (ux.md §5.9) and on a lock
     *   screen ("Forgot master password?", F17 step 3); never in the open app or before start-up has
     *   run: [CreateOutcome.NotSaved], and nothing is written.
     * - `UnlockFlows.newVault` with `restored = true`: storage writes the backup as a new vault with
     *   a new `vault_id`, VK, `vk_id`, salt and slot, proves it from disk, and only then puts a vault
     *   that is current aside and makes the new one current, in one step that survives a crash
     *   (`putCurrentAside` is always asked for, so a vault on the phone is archived, never replaced
     *   and never deleted). The vault that goes aside keeps its lock directory and its keys: the lock
     *   does nothing to them (F17). Then a new lock directory with `state.rec` at practice step 3
     *   (F13 step 5), [created], and F1 step 5: "Choose a PIN" when this phone can hold a key, F18
     *   otherwise (F13 step 6).
     */
    suspend fun restore(first: CharArray, again: CharArray, from: Vault, created: suspend () -> Unit): CreateOutcome {
        if (!MasterPasswordBytes.same(first, again)) return CreateOutcome.Mismatch
        return core.locked {
            when (core.state.value) {
                is LockState.NoVault, is LockState.Unreadable, is LockState.Locked -> Unit
                LockState.Starting, is LockState.Unlocked -> return@locked CreateOutcome.NotSaved
            }
            val firstBytes = MasterPasswordBytes.of(first)
            val againBytes = MasterPasswordBytes.of(again)
            try {
                core.unlock.newVault(firstBytes, againBytes, from, putCurrentAside = true, restored = true, created = created)
            } finally {
                Bytes.wipe(firstBytes, againBytes)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F17 step 2: start with an empty app

    /**
     * Holds the mutex. F17 step 2, "Start with an empty app", confirmed on the "Forgot master
     * password?" sheet of the current vault's lock screen. It is offered only when no shortcut is
     * usable now (F17 step 1, ux.md §5.10), and it is refused here otherwise: a PIN or fingerprint
     * that opens the vault now (or may, while `canAuthenticate` has not answered on this visit) is
     * the way on (F11), and nothing is put aside.
     *
     * `VaultPort.putAside` moves the vault's files to an archive name with one rename. **The lock
     * does nothing else:** `lock/<vault_id>/` and its keys stay where they are, so the vault's own
     * PIN, fingerprint or master password opens it again later (F17 step 4). Welcome follows
     * (`UnlockFlows.relock`), listing it. A port that throws leaves it unknown what moved: the
     * screen of what is there now is shown, and the answer is false.
     *
     * @return true when the vault was put aside; false when nothing was put aside.
     */
    suspend fun startEmpty(): Boolean {
        if (core.state.value !is LockState.Locked) return false
        val target = core.target?.takeIf { it.aside == null } ?: return false
        val visit = core.visit?.takeIf { it.target === target && !it.place.hasEnded }
        val decision = core.decide(target, visit) as? EntryPolicy.Decision.Locked ?: return false
        if (decision.pinUsable || decision.fingerprintUsable || decision.fingerprintWaitsForAnswer) return false
        val putAside = try {
            core.vault.putAside()
        } catch (e: Exception) {
            core.unlock.relock()
            return false
        }
        if (!putAside) return false
        core.unlock.relock()
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // F17 step 4: open my previous passwords

    /**
     * Holds the mutex. F17 step 4, "Open my previous passwords" (on Welcome, on the "Forgot master
     * password?" sheet, on "Your passwords can't be opened"), and F16b step 2, the old vault's own
     * lock screen: the vault [vaultId] put aside shows **its own** lock screen, with the line
     * "Passwords locked on 6 Oct 2026" ("Your passwords from before 7 Oct 2026" for one marked
     * `replaced`).
     *
     * - The vaults are listed again; a [vaultId] that is not a vault put aside (any more) changes
     *   nothing but the screen, which shows what is there now.
     * - `core.target` becomes that vault, with its own `VaultLock`: its pad and fingerprint if their
     *   records and keys still work, otherwise its master-password field, without practice, and
     *   with the master password only for a vault marked `replaced` (`EntryPolicy` on that vault's
     *   records, without rows 1 to 4). A visit starts, which runs that vault's counter start-up step
     *   (§6.3 S): its own five tries (F17 step 7) and its own master-password guard (§6.8) apply.
     * - From the open app (F16b: "It wasn't me" reaches it through `OlderCopies`), the open vault is
     *   locked first, as `LockEngine.lock` locks it: open prompts and a fingerprint launch end, the
     *   proofs are dropped, the vault port drops its session. A vault put aside opens on its own lock
     *   screen only in a locked app, never beside an open one. (No draft is flushed here: nothing in
     *   the lock calls `flushDraft`, architecture.md §5.5; the caller does that first when a form may
     *   be open.)
     *
     * Nothing is written to the vault; only that vault's `pin.rec` may be, by its start-up step.
     */
    suspend fun showAside(vaultId: String) {
        if (core.state.value == LockState.Starting) return
        val listed = core.rescan()
        val currentId = core.open?.vaultId ?: (listed?.current as? CurrentVault.Present)?.vaultId
        val aside = listed?.aside?.firstOrNull { it.vaultId == vaultId }?.takeIf { vaultId != currentId }
        if (aside == null) {
            if (core.state.value !is LockState.Unlocked) core.showLockScreen()
            core.publishSummary()
            return
        }
        if (core.state.value is LockState.Unlocked) closeOpenVault()
        val lock = core.lockOf(vaultId)
        pendingWhenShown[vaultId] = lock.state?.pendingMp != null
        core.target = LockTarget(VaultRef.Aside(vaultId), lock, aside)
        core.startVisit()
        core.showLockScreen()
        core.publishSummary()
    }

    /**
     * Holds the mutex. The vault put aside of [target] has opened in place (`VaultPort.open` or
     * `openWithPassword` on `VaultRef.Aside`), by [by], on its own lock screen (`UnlockFlows` has
     * checked that the visit had not ended when it opened).
     *
     * - One marked `replaced` asks the question of F16b step 3 first:
     *   `OlderCopies.asideFromBeforeOpened` (build plan 4.4).
     * - Otherwise F17 step 4: **only now** does `VaultPort.swapIn` put the current vault aside and
     *   make this one current. The vault that goes aside keeps its records and keys. Then the
     *   vaults are listed again, the current target is the vault that opened, and
     *   `UnlockFlows.completeUnlock` does everything an unlock does (the wraps of §5.4, `state.rec`,
     *   the notices of §13.4, "Choose a PIN" when it opened without a working PIN, ux.md §5.1).
     * - The swap fails: the vault opened in place is dropped again (`VaultPort.lock`), the record of
     *   a right PIN is written all the same (§6.3 E.6), and its lock screen stays. Nothing changed.
     * - The visit ended while the vaults changed places (the screen went off): the swap stands, it
     *   is on disk, but nothing opens (I11). The session is dropped, and the lock screen of the vault
     *   that is current now is what the next visit shows.
     *
     * @return the proof of [proofBytes] when the master password opened it and the app is open now,
     *   else null.
     */
    suspend fun asideOpened(target: LockTarget, by: UnlockBy, opened: OpenVault, proofBytes: ByteArray?): MasterProof? {
        val lock = target.lock
        if (lock.state?.replaced != null) return core.olderCopies.asideFromBeforeOpened(target, opened, proofBytes)
        val visit = core.visit
        val pendingCancelled = by == UnlockBy.MASTER_PASSWORD && pendingWhenShown[target.vaultId] == true && lock.state?.pendingMp == null
        if (!core.vault.swapIn(target.vaultId)) {
            core.vault.lock()
            lock.pin.flush()
            if (visit != null && core.visit === visit && !visit.place.hasEnded) core.showLockScreen()
            core.publishSummary()
            return null
        }
        pendingWhenShown.remove(target.vaultId)
        core.rescan()
        core.retarget()
        if (visit == null || core.visit !== visit || visit.place.hasEnded) {
            core.vault.lock()
            lock.pin.flush()
            core.unlock.relock()
            return null
        }
        return core.unlock.completeUnlock(lock, by, opened, proofBytes, pendingCancelled)
    }

    /**
     * Holds the mutex. Back on the lock screen of a vault put aside (F17 step 4): the current vault's
     * lock screen (or Welcome, or "Your passwords can't be opened") again, and nothing changed, so a
     * stray tap cannot swap the vaults. A charge held on that vault's pad is refunded when its visit
     * ends (§6.3 A); its counts stay as they are.
     */
    suspend fun goBack() {
        if (core.state.value !is LockState.Locked) return
        if (core.target?.aside == null) return
        core.unlock.relock()
    }

    // ---------------------------------------------------------------------------------------------
    // F17 step 5: Settings › Backup & transfer › Old passwords

    /**
     * Takes the mutex itself. F17 step 5, Settings › Old passwords › "Open": [chars] is the master
     * password of the vault [vaultId] put aside, checked under **that vault's own** guard and
     * records (`core.lockOf(vaultId).master`, §6.8: its own count of wrong tries and its own waits)
     * in a prompt of the open app; then `VaultPort.mergeAside` adds its accounts to the current
     * vault (architecture.md §4.11: one ordinary save). The vault put aside stays where it is.
     *
     * Only that vault's own slots are tried: the current master password, a pending one (F11) or a
     * password that opens only an older copy of it do not open it. A right one counts as that
     * vault's master password typed anywhere in the app: its PIN counts return to zero (§6.4) and
     * its practice moves on. A lock or `ON_STOP` while the check runs, or before the merge, merges
     * nothing (I11). Nothing is changed for a vault that is not put aside, or while the app is not
     * open ([AsideOutcome.NotSaved]).
     *
     * [chars] stays the caller's, which wipes it.
     */
    suspend fun mergeAside(vaultId: String, chars: CharArray): AsideOutcome {
        val at = core.openPrompt()
        try {
            return core.locked { mergeLocked(vaultId, chars, at) }
        } finally {
            core.closePrompt(at)
        }
    }

    private suspend fun mergeLocked(vaultId: String, chars: CharArray, at: Place): AsideOutcome {
        val lock = asideToChange(vaultId, at) ?: return AsideOutcome.NotSaved
        refusal(checkOwnPassword(lock, chars, at))?.let { return it }
        if (at.hasEnded) return AsideOutcome.NotSaved
        val write = core.vault.mergeAside(vaultId)
        core.rescan()
        core.publishSummary()
        return if (write is VaultWrite.Done) AsideOutcome.Done else AsideOutcome.NotSaved
    }

    /**
     * Takes the mutex itself. F17 step 5, Settings › Old passwords › "Delete": the master password
     * of the vault [vaultId] put aside, and nothing else will do: not the current vault's master
     * password, not a pending password (F11), not the passing of time. It is checked under that
     * vault's own guard, as in [mergeAside]. Then, in this order:
     *
     * 1. **Another vault's `replaced` mark is settled first.** Every vault on the phone whose
     *    `state.rec` says that it was replaced by [vaultId] gets what start-up would give it (§5.11
     *    step 3, first row): its two slots removed (`off = replaced`) with their keys, its
     *    `pending_mp` cleared; `replaced` stays. Otherwise a later start would find vault N gone and
     *    take the third row ("the replacement did not take place"), which keeps the old vault's
     *    slots, keys and pending password: the PIN that the replacer knew would open the old
     *    passwords again. These writes must be on disk before a file is deleted; if one fails, or a
     *    vault's records cannot be read now (NEWER or UNREADABLE: they may hold such a mark or such
     *    a slot), nothing is deleted ([AsideOutcome.NotSaved]). What was settled stays settled, as
     *    start-up would leave it. When something was settled, the files wait until those writes are
     *    [SETTLED_MS] old, the age a charge must have before its PIN is checked (§6.2, I4): a write
     *    that `fsync` handed over may not be on the flash yet (§2.3, §6.7, [V-S8]), and a power cut
     *    must not keep the deletion and lose the settling.
     * 2. Storage deletes that vault's files (`VaultPort.deleteAside`).
     * 3. Only after that returned true: `lock/<vault_id>/`, then the keys its records name. A
     *    directory that cannot be deleted is left: a lock directory whose vault is gone is left alone
     *    by every start (§5.11), and its keys are deleted here all the same. Keys of slots that were
     *    replaced earlier are named by no record any more, and the next sweep deletes them (§5.7).
     *
     * A lock or `ON_STOP` while the check runs, or before the files are deleted, deletes nothing.
     * The current vault is never deleted here. [chars] stays the caller's, which wipes it.
     */
    suspend fun deleteAside(vaultId: String, chars: CharArray): AsideOutcome {
        val at = core.openPrompt()
        try {
            return core.locked { deleteLocked(vaultId, chars, at) }
        } finally {
            core.closePrompt(at)
        }
    }

    private suspend fun deleteLocked(vaultId: String, chars: CharArray, at: Place): AsideOutcome {
        val lock = asideToChange(vaultId, at) ?: return AsideOutcome.NotSaved
        refusal(checkOwnPassword(lock, chars, at))?.let { return it }
        val onPhone = vaultsOnPhone() ?: return AsideOutcome.NotSaved
        if (vaultId !in onPhone || vaultId == core.open?.vaultId) return AsideOutcome.NotSaved
        val settled = settleMarksNaming(vaultId, onPhone - vaultId)
        if (settled == null) {
            core.publishSummary()
            return AsideOutcome.NotSaved
        }
        if (settled > 0) core.clock.delayUntilElapsed(core.clock.elapsedMs() + SETTLED_MS)
        if (at.hasEnded) return AsideOutcome.NotSaved
        if (!core.vault.deleteAside(vaultId)) {
            core.rescan()
            core.publishSummary()
            return AsideOutcome.NotSaved
        }
        val aliases = aliasesNamedBy(lock)
        try {
            core.files.deleteDir(FileRecordStore.dir(core.root, vaultId))
        } catch (e: Exception) {
            // Left behind: a lock directory whose vault is gone is left alone at every start (§5.11).
        }
        for (alias in aliases) {
            try {
                core.deviceKeys.delete(alias)
            } catch (e: RuntimeException) {
                // Ignored (§5.7): no record will name it, so the next sweep deletes it.
            }
        }
        core.forgetLock(vaultId)
        pendingWhenShown.remove(vaultId)
        core.rescan()
        core.publishSummary()
        return AsideOutcome.Done
    }

    /**
     * Holds the mutex. The lock state of [vaultId] when it may be opened or deleted from Settings now:
     * the app is open, the prompt [at] has not ended, and [vaultId] is a vault put aside, not the
     * current one. Null otherwise.
     */
    private fun asideToChange(vaultId: String, at: Place): VaultLock? {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || at.hasEnded) return null
        val listed = core.rescan() ?: return null
        if (vaultId == open.vaultId || listed.aside.none { it.vaultId == vaultId }) return null
        return core.lockOf(vaultId)
    }

    /** What checking the master password of a vault put aside came to. */
    private sealed interface OwnPassword {
        /** One of its slots opened, and the prompt has not ended. */
        data object Right : OwnPassword

        /** Not opened, or too late: the answer for the screen. */
        data class Refused(val outcome: AsideOutcome) : OwnPassword
    }

    private fun refusal(checked: OwnPassword): AsideOutcome? = (checked as? OwnPassword.Refused)?.outcome

    /**
     * Holds the mutex. lock.md §6.8 for the vault of [lock], put aside, in the prompt [at] of the open
     * app, under **its own** guard: its wait when one is due, the try counted before Argon2id runs,
     * only its own slots tried (`openWithPassword` on `VaultRef.Aside`, which keeps it open in place
     * until `mergeAside`, `deleteAside` or a lock). Right: that vault's PIN counts return to zero
     * (§6.4; its counter's start-up step runs first if its lock screen has not been shown in this
     * process, so that a charge left on disk is counted before the reset), also when the answer
     * comes too late. Wrong, or a password that opens only an older copy of it (§6.8 step 6):
     * counted. Not enough memory: not counted. No generation of it can be used now: neither right
     * nor wrong, and nothing happens.
     */
    private suspend fun checkOwnPassword(lock: VaultLock, chars: CharArray, at: Place): OwnPassword {
        val bytes = MasterPasswordBytes.of(chars)
        var opened: PasswordOpen? = null
        try {
            val attempt = lock.master.attempt(at) {
                val result = core.vault.openWithPassword(VaultRef.Aside(lock.vaultId), bytes)
                opened = result
                when (result) {
                    is PasswordOpen.Opened -> MpCheck.RIGHT
                    PasswordOpen.Wrong, is PasswordOpen.OlderPassword -> MpCheck.WRONG
                    PasswordOpen.NoMemory, PasswordOpen.Damaged, PasswordOpen.Newer, PasswordOpen.Unreadable -> MpCheck.NOT_COUNTED
                }
            }
            return when (attempt) {
                is MpAttempt.Wait -> OwnPassword.Refused(AsideOutcome.Wait(attempt.untilElapsedMs))
                is MpAttempt.Checked -> when (attempt.check) {
                    MpCheck.RIGHT -> {
                        lock.startPin()
                        lock.pin.masterPasswordCorrect()
                        if (attempt.tooLate) OwnPassword.Refused(AsideOutcome.NotSaved) else OwnPassword.Right
                    }
                    MpCheck.WRONG -> OwnPassword.Refused(AsideOutcome.Wrong)
                    MpCheck.NOT_COUNTED ->
                        OwnPassword.Refused(if (opened == PasswordOpen.NoMemory) AsideOutcome.NoMemory else AsideOutcome.NotSaved)
                }
            }
        } finally {
            Bytes.wipe(bytes)
        }
    }

    /**
     * Holds the mutex. The `vault_id`s of the vaults on the phone now: the open one, the current one
     * and those put aside, as storage lists them; null when they cannot be listed.
     */
    private fun vaultsOnPhone(): Set<String>? {
        val listed = core.rescan() ?: return null
        val ids = LinkedHashSet<String>()
        core.open?.let { ids += it.vaultId }
        (listed.current as? CurrentVault.Present)?.let { ids += it.vaultId }
        listed.aside.forEach { ids += it.vaultId }
        return ids
    }

    /**
     * Holds the mutex. F17 step 5: every vault in [others] whose `replaced` names [vaultId] is
     * settled as start-up would settle it (§5.11 step 3, first row), with every write on disk.
     * Returns how many were settled, or null when one could not be: a write failed, or a record that
     * may hold the mark cannot be read now (NEWER, UNREADABLE). A `state.rec` that is MISSING or
     * DAMAGED holds no mark that start-up would act on. The mark is the one the lock means to have
     * (in memory, §6.3), as start-up and the flows that write it keep it.
     */
    private fun settleMarksNaming(vaultId: String, others: Set<String>): Int? {
        var settled = 0
        for (id in others) {
            val other = core.lockOf(id)
            when (other.stateRead) {
                is RecordRead.Ok -> Unit
                RecordRead.Missing, is RecordRead.Damaged -> continue
                RecordRead.Newer, RecordRead.Unreadable -> return null
            }
            if (other.state?.replaced?.newVaultId != vaultId) continue
            if (!removeShortcutsOnDisk(other)) return null
            settled++
        }
        return settled
    }

    /**
     * Holds the mutex. Start-up's first row of §5.11 step 3 for [other]: both slots removed
     * (`off = replaced`), each key at once (§5.7), and `pending_mp = null`; `replaced` stays. The PIN's
     * record is read through its counter first (§6.3 S), as start-up does.
     *
     * True only when all of it is on disk. `state.rec` is written whole even when nothing in it
     * changes. A slot record is looked at on disk: when the slot is still there because a removal
     * was made in memory and its write failed (start-up's, or an earlier try of this flow), the
     * record the lock means to have, without the slot, is written once more; a slot that stayed on
     * disk would come back once vault N is gone. A slot record that cannot be read now (NEWER,
     * UNREADABLE) may still hold a slot and is never written over: false then.
     */
    private fun removeShortcutsOnDisk(other: VaultLock): Boolean {
        other.startPin()
        if (mayHoldUnknownSlot(other.pinRead) || mayHoldUnknownSlot(other.fingerprint.read)) return false
        val pinWritten = other.pin.removeSlot(PinRecord.OffReason.REPLACED)
        val fingerprintWritten = other.fingerprint.removeSlot(FingerprintRecord.OffReason.REPLACED)
        val pendingCleared = other.master.update(bestEffort = false) { it.copy(pendingMp = null) }
        val pinGone = goneOnDisk(pinWritten, FileRecordStore.pin(core.files, core.root, other.vaultId), other.pin.record) { it.slot }
        val fingerprintGone =
            goneOnDisk(fingerprintWritten, FileRecordStore.fingerprint(core.files, core.root, other.vaultId), other.fingerprint.record) { it.slot }
        return pendingCleared && pinGone && fingerprintGone
    }

    private fun mayHoldUnknownSlot(read: RecordRead<*>): Boolean = read is RecordRead.Newer || read is RecordRead.Unreadable

    /**
     * Whether the record of [store] holds no slot on disk after a removal whose write came to
     * [written] (true also when there was nothing to remove in memory). Where it was not written, or
     * the disk still names a slot, [meant] (the record the lock means to have, which holds no slot)
     * is written again, whole. MISSING and DAMAGED hold no slot that could ever be used (§5.3: a
     * damaged record is never used and never repaired in place).
     */
    private fun <T : Any> goneOnDisk(written: Boolean, store: FileRecordStore<T>, meant: T?, slotOf: (T) -> ShortcutSlot?): Boolean {
        val onDisk = store.read()
        if (onDisk is RecordRead.Newer || onDisk is RecordRead.Unreadable) return false
        val slotOnDisk = (onDisk as? RecordRead.Ok)?.record?.let(slotOf)
        if (written && slotOnDisk == null) return true
        if (meant == null) return slotOnDisk == null
        if (slotOf(meant) != null) return false
        return try {
            store.write(meant)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The aliases that the slots of [lock]'s records name now (`pv_pin_…`, `pv_fp_…`). */
    private fun aliasesNamedBy(lock: VaultLock): List<String> {
        val aliases = ArrayList<String>(2)
        (lock.pinRead as? RecordRead.Ok)?.record?.slot?.let { aliases += PinWrap.alias(it.id) }
        lock.fingerprint.slot?.let { aliases += FingerprintKeeper.alias(it.id) }
        return aliases
    }

    /**
     * Holds the mutex, from inside a step that found the app open: the open vault is locked as
     * `LockEngine.lock` locks it (lock.md §10.4 steps 1 and 2): open prompts end (a charge held in
     * one is refunded), the proofs are dropped, a fingerprint launch ends and its prompt is
     * cancelled, and the vault port drops its session. It never writes vault data.
     */
    private suspend fun closeOpenVault() {
        core.promptsEnded(core.endPromptsNow())
        core.dropProofs()
        core.endLaunchNow()
        core.vault.lock()
        core.open = null
    }

    // ---------------------------------------------------------------------------------------------
    // F12: backup and export

    /**
     * Holds the mutex. F12 step 2, encrypted backup only: the master password was typed a moment
     * ago in the same flow (practice, a master-password unlock, setup or a restore, a swap opened
     * with it), the unlocked state still holds that proof, and the app says that a backup is due:
     * `Notice.BackupNow` ("Make a backup now?", §13.4 item 7) is added after the notices shown, and
     * the backup it starts uses that entry. Nothing is offered when there is no such proof (a PIN or
     * fingerprint unlock, or the app stopped being visible since), when it is not the open vault's,
     * while a new master password is pending (F11 step 10: "You can make a backup when your new
     * master password has started working."), or when the offer is shown already. Nothing is
     * written. Whether a backup is due is the app's to say (ux.md §5.19).
     */
    suspend fun offerBackup() {
        val now = core.state.value as? LockState.Unlocked ?: return
        val proof = now.proof ?: return
        val open = core.open ?: return
        if (proof.vaultId != open.vaultId || !core.holds(proof)) return
        if (core.knownLock(open.vaultId)?.state?.pendingMp != null) return
        if (now.notices.any { it is Notice.BackupNow }) return
        core.addNotice { Notice.BackupNow(it) }
    }

    private companion object {
        /**
         * How old the writes that settle another vault's `replaced` mark must be before the files of
         * the vault it names are deleted (F17 step 5): the charge age of lock.md §6.2, which is this
         * design's time for a handed-over write to reach the flash (§2.3, §6.7).
         */
        const val SETTLED_MS = PinGuard.CHARGE_AGE_MS
    }
}
