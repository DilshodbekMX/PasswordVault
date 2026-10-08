package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Bytes

/**
 * Older copies and older master passwords (lock.md **F16**, build plan 4.4). `LockEngine` sends its
 * entry points here; every function here **holds the mutex**.
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
 * **F16b after an ordinary change** (F8). While `mp_change` is set, the change did not finish, and
 * the screen offers "Open my passwords from before the change". Its step 3 says that the vault then
 * opens "with the VK just unwrapped": storage hands the lock no key (architecture.md §3.4), so this
 * flow keeps a copy of the bytes that were typed ([olderPasswordTyped]) and, once storage has put the
 * newer generations aside, checks them again as a master-password entry of the same visit. They are
 * in memory for that screen only, like the PIN digits (§5.8): an answer or the end of the visit wipes
 * them ([visitMayHaveEnded]).
 *
 * **F16b after a replacement** (F11). The passwords as they were are a vault of their own, put
 * aside and marked `replaced`. Its master password opens it in place, on its own lock screen
 * ([asideFromBeforeOpened]); the question "Open the older set instead?" is `LockVisit.olderSet`,
 * which makes the lock screen `LockScreen.OpenOlderSet` until [answer] has the answer. Only "Open my
 * passwords from 7 Oct" swaps the vaults (steps 4 to 6). "Cancel", and a visit that ends before an
 * answer, drop the vault that opened in place (`VaultPort.dropAside`, I11).
 */
internal class OlderCopies(private val core: LockCore) {

    /**
     * What this flow keeps for the lock-screen visit [visit] until its screen is answered or the visit
     * ends: [typed], a copy of the master-password bytes behind "This is an older master password"
     * while the change is unfinished; or [question], whose vault is open in place in the vault port.
     */
    private class Kept(val visit: LockVisit, val typed: ByteArray?, val question: OlderSetQuestion?)

    private var kept: Kept? = null

    /**
     * The typed master password [bytes] opens only a copy from before a change (F16b), and
     * `LockVisit.olderPassword` of [visit] has storage's answer. While the screen offers "Open my
     * passwords from before the change" (`LockCore.changeUnfinished`: the change is marked
     * unfinished on the current vault, and its copy is the newest on disk), a copy of [bytes] is kept
     * for it; otherwise nothing is kept: the screen has only "OK".
     */
    fun olderPasswordTyped(visit: LockVisit, bytes: ByteArray) {
        forget()
        if (!core.changeUnfinished(visit.target)) return
        kept = Kept(visit, bytes.copyOf(), null)
    }

    /**
     * An answer on "This is an older master password" (F16b, after an ordinary change). TRY_AGAIN and
     * OK: `LockVisit.olderPassword = null` and the field again. OPEN_FROM_BEFORE, after its question
     * was answered with "Open", only where the screen offers it (`LockCore.changeUnfinished`: on the
     * current vault, while `mp_change` is set and the copy the change made is the newest on disk):
     *
     * 2. storage puts the generations above the copy that opened aside (`VaultPort.putNewerAside`),
     *    never deleting them; the step can be repeated after an interruption;
     * 3. `max_generation` becomes that copy, so that opening it is not reported as "older than last
     *    time" (F16c), and the password typed a moment ago is checked again as a master-password
     *    entry of this visit (`UnlockFlows.checkOnLockScreen`): it now opens the newest copy, which
     *    brings what every correct master password brings (§6.8 step 5), `mp_change = null` among it.
     *    No shortcut is removed; the wraps for the abandoned key go by the rule of §5.4 at that
     *    unlock. When the guard makes the field wait, or the bytes are gone, the field is shown: the
     *    old master password, typed there, opens the vault.
     *
     * And the two answers on "Open the older set instead?" (`LockScreen.OpenOlderSet`, F16b after a
     * replacement, step 3), only while `LockVisit.olderSet` is set. OPEN_OLDER_SET: steps 4 to 6.
     * KEEP_NEWER_SET ("Cancel"): nothing changes; the question is taken off the visit, its bytes are
     * wiped and the vault that opened in place is dropped (`VaultPort.dropAside`). An answer that
     * does not belong to the screen shown does nothing.
     */
    suspend fun answer(answer: OlderPasswordAnswer) {
        if (core.state.value !is LockState.Locked) return
        val visit = core.visit?.takeIf { !it.place.hasEnded && !it.opening } ?: return
        when (answer) {
            OlderPasswordAnswer.TRY_AGAIN, OlderPasswordAnswer.OK -> {
                if (visit.olderSet != null || visit.olderPassword == null) return
                visit.olderPassword = null
                forget()
                core.showLockScreen()
            }
            OlderPasswordAnswer.OPEN_FROM_BEFORE -> if (visit.olderSet == null) openFromBefore(visit)
            OlderPasswordAnswer.OPEN_OLDER_SET -> openOlderSet(visit)
            OlderPasswordAnswer.KEEP_NEWER_SET -> keepNewerSet(visit)
        }
    }

    /** F16b after an ordinary change, steps 2 and 3. */
    private suspend fun openFromBefore(visit: LockVisit) {
        val older = visit.olderPassword ?: return
        val target = visit.target
        val lock = target.lock
        // Only an unfinished change may be given up, and only the copy it made (`putNewerAside` acts
        // on the current vault): where the screen has only "OK", this answer does nothing.
        if (!core.changeUnfinished(target)) return
        if (!core.vault.putNewerAside(older.generation)) {
            core.rescan()
            core.showLockScreen() // nothing was put aside: the screen stays, with "Try again"
            return
        }
        core.rescan()
        lock.master.update { it.copy(maxGeneration = StateRecord.MaxGeneration(older.generation, core.clock.wallMs())) }
        val typed = kept?.takeIf { it.visit === visit }?.typed
        kept = null // the bytes are this call's from here on
        visit.olderPassword = null
        try {
            core.showLockScreen()
            if (typed != null) core.unlock.checkOnLockScreen(visit, typed)
        } finally {
            Bytes.wipe(typed)
        }
    }

    /** F16b after a replacement, steps 4 to 6: "Open my passwords from 7 Oct". */
    private suspend fun openOlderSet(visit: LockVisit) {
        val question = visit.olderSet ?: return
        val target = visit.target
        if (target.aside == null) return
        val lock = target.lock
        visit.olderSet = null // from here on its bytes are this flow's, and so is the vault that opened in place
        kept = null
        try {
            // Step 4: the current vault goes aside and the old one is current, in storage's one step.
            if (!core.vault.swapIn(target.vaultId)) {
                dropAsideHeld() // nothing changed: that vault's own lock screen again
                if (core.visit === visit && !visit.place.hasEnded) core.showLockScreen()
                core.publishSummary()
                return
            }
            core.rescan()
            core.retarget()
            // Step 5: the old vault's records. A write that fails is made good by start-up (§5.11 step 3),
            // and start-up does that only while the mark is there: the mark goes with both slots, never
            // before. While a slot could not be removed (its record cannot be read now, or its write did
            // not reach the disk), the mark stays, and the next start removes that slot and then the mark
            // ("the owner went back, and step 5 there did not finish"). Without it, the PIN or the finger of
            // whoever replaced the master password would open these passwords again once that record reads.
            lock.startPin()
            val pinGone = lock.pin.removeSlot(PinRecord.OffReason.REPLACED) && noSlotCanComeBack(lock.pinRead)
            val fingerprintGone = lock.fingerprint.removeSlot(FingerprintRecord.OffReason.REPLACED) && noSlotCanComeBack(lock.fingerprint.read)
            lock.master.update { it.copy(pendingMp = null, replaced = if (pinGone && fingerprintGone) null else it.replaced) }
            if (core.visit !== visit || visit.place.hasEnded) {
                // The visit ended while the vaults changed places: the swap stands, and nothing opens (I11).
                try {
                    core.vault.lock()
                } finally {
                    core.unlock.relock()
                }
                return
            }
            // Step 6 comes with the unlock: "Choose a new PIN. Someone else may know the old one."
            // (`off = replaced` in `pin.rec`), with the proof of the master password just typed.
            core.unlock.completeUnlock(lock, UnlockBy.MASTER_PASSWORD, question.opened, question.proofBytes, pendingCancelled = false)
        } finally {
            Bytes.wipe(question.proofBytes)
        }
    }

    /** F16b after a replacement, step 3: "Cancel". Nothing changes. */
    private suspend fun keepNewerSet(visit: LockVisit) {
        val question = visit.olderSet ?: return
        visit.olderSet = null
        kept = null
        Bytes.wipe(question.proofBytes)
        dropAsideHeld()
        core.showLockScreen()
        core.publishSummary()
    }

    /**
     * "It wasn't me" on the "replaced" notice, or "Open my passwords from before 7 Oct" on the lock
     * screen (F16b, after a replacement, step 1): the old vault's own lock screen, which has only
     * its master-password field (`VaultFlows.showAside`; from the open app the open vault is locked
     * first). The typed password is not tried against the old vault behind the user's back: nothing
     * is typed here. [oldVaultId] null: the master password was replaced more than once, and the
     * list of the "Forgot master password?" sheet names each; that sheet is the lock screen's, so the
     * open app is locked for it, and on a lock screen nothing changes.
     */
    suspend fun itWasntMe(oldVaultId: String?) {
        if (oldVaultId != null) {
            core.vaults.showAside(oldVaultId)
            return
        }
        if (core.state.value is LockState.Unlocked) core.unlock.lockFromInside()
    }

    /**
     * The vault of [target], put aside and marked `replaced`, has opened in place with its own
     * master password (F16b steps 3 to 6): the question first; then the swap of F17 step 4, the old
     * vault's records (both slots removed if start-up has not, `pending_mp = null`, and
     * `replaced = null` once both slots are gone), and "Choose a new PIN. Someone else may know the
     * old one."
     *
     * The question is a screen of its own, and its answer comes in a later call ([answer]): this
     * call puts an `OlderSetQuestion` on the visit (`LockVisit.olderSet`, with [opened] and a copy
     * of [proofBytes]), shows the lock screen again, which is then `LockScreen.OpenOlderSet`, and
     * returns null: the app stays locked until the answer. If the visit ends before an answer, the
     * vault that opened in place is dropped (I11): [visitMayHaveEnded].
     *
     * @return null: the proof is made when the old vault is the current one, by the answer.
     */
    suspend fun asideFromBeforeOpened(target: LockTarget, opened: OpenVault, proofBytes: ByteArray?): MasterProof? {
        forget()
        val visit = core.visit
        val replaced = target.lock.state?.replaced
        if (visit == null || visit.target !== target || visit.place.hasEnded || target.aside == null || replaced == null) {
            dropAsideHeld() // no screen is there to ask on: the key is dropped (I11)
            return null
        }
        val question = OlderSetQuestion(replaced.atMs, opened, proofBytes?.copyOf())
        visit.mpError = null
        visit.olderSet = question
        kept = Kept(visit, null, question)
        core.showLockScreen()
        core.publishSummary()
        return null
    }

    /**
     * What a visit that ended left behind goes (§5.8, I11): the bytes kept for "Open my passwords
     * from before the change" are wiped, and the vault that opened in place for the question of F16b
     * step 3 is dropped (`LockCore.closeVisit` has zeroed the question's own bytes, and cannot drop
     * the vault, because it does not suspend). `LockEngine` calls it at the end of every step it runs
     * under the mutex, so that it follows the end of the visit in the same turn, before anything else
     * can open a vault in place. It does nothing while the screen it keeps things for is still shown.
     */
    suspend fun visitMayHaveEnded() {
        val held = kept ?: return
        val visit = held.visit
        val shown = core.visit === visit && !visit.place.hasEnded &&
            (if (held.question != null) visit.olderSet === held.question else visit.olderPassword != null)
        if (shown) return
        kept = null
        Bytes.wipe(held.typed)
        if (held.question != null) dropAsideHeld()
    }

    /** What was kept for a screen that is no longer the one to answer: wiped; nothing is open in place for it. */
    private fun forget() {
        kept?.let { Bytes.wipe(it.typed) }
        kept = null
    }

    /**
     * Whether a shortcut record that [read] gives can bring no slot back: one the lock holds (its
     * removal of the slot is then the record), none at all, or a damaged one, which is never used
     * (§5.3). Not one that cannot be read now, which may read fine next time with its slot in it,
     * nor one written by a newer version of the app, which the lock never rewrites.
     */
    private fun noSlotCanComeBack(read: RecordRead<*>): Boolean = when (read) {
        is RecordRead.Ok, RecordRead.Missing, is RecordRead.Damaged -> true
        RecordRead.Unreadable, RecordRead.Newer -> false
    }

    /** The vault put aside that is open in place in the vault port is dropped again, with its key (`VaultPort.dropAside`). */
    private suspend fun dropAsideHeld() {
        try {
            core.vault.dropAside()
        } catch (e: RuntimeException) {
            // It cannot be reached: the next lock drops it with everything else.
        }
    }
}
