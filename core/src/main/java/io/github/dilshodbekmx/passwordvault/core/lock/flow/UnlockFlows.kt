package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpAttempt
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpCheck
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinCharge
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.model.Vault
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The lock screen and what opens the vault (lock.md §12, build plan 3.10):
 *
 * - **F1** first setup: [createVault], with [newVault] for the steps a restore shares (F13).
 * - **F2** the daily unlock with the PIN, **F4** a wrong PIN and **F5** the PIN limit: [digit],
 *   [deleteDigit], [clearDigits], over `PinGuard`, which is the protocol of §6.3.
 * - **F3** the daily unlock with the fingerprint: [fingerprint], with the launch numbers of §9.2
 *   and what section 14 says about each result.
 * - **F7** forgot the PIN: [useMasterPassword], then [masterPasswordOnLockScreen], which is the
 *   master-password check of §6.8 on any master-password screen (practice, "Welcome back", no PIN).
 * - The checks of the open app: [masterPasswordInApp] (§6.8 in a prompt) and [pinInPrompt] (§6.3 in
 *   a prompt: F6 step 2, F8 step 3, F11 step 3).
 * - What every unlock does afterwards: [completeUnlock] (the records of §5.3, §5.4 and §5.6, the
 *   notices of §13.4), and the answers to those notices: [answerNotice].
 * - [relock] and [refresh].
 *
 * Every function says whether it **holds the mutex** (it is called inside `LockCore.locked`) or
 * **takes the mutex itself**.
 *
 * Where another package's flow begins, this file calls that flow's file and does nothing of it
 * itself: a vault put aside that opened (`VaultFlows.asideOpened`, F17 step 4), "Not now" on a
 * practice screen (`ShortcutFlows.practiceNotNow`, F10), the pending wrap at a master-password
 * entry (`PasswordFlows.typedPending`, F11 step 13), a master password from before a change
 * (`OlderCopies.olderPasswordTyped`, F16b), cancelling a pending password and "It wasn't me" from
 * their notices. Package 4.4 edited this file for those (build plan 4.4), and for [pinAt],
 * [checkOnLockScreen] and [lockFromInside], which its flows call.
 */
internal class UnlockFlows(private val core: LockCore) {

    // ---------------------------------------------------------------------------------------------
    // Locking and looking again

    /**
     * Holds the mutex: the app is locked now (the vault port has dropped its session, or never had
     * one). The vaults are listed again, the current one's lock screen is chosen (lock.md §13.1), a
     * visit starts if the app is visible (§10.4 step 5), and the notices of the open app are gone.
     */
    fun relock() {
        core.open = null
        core.rescan()
        core.retarget()
        core.startVisit()
        core.showLockScreen()
        core.publishSummary()
    }

    /**
     * Holds the mutex: `LockEngine.refresh`. While a PIN is being typed the pad is not decided
     * again: time alone (practice falling due, a pending password becoming ready) does not take the
     * pad away under the user's fingers; the next decision comes when the entry ends.
     */
    fun refresh() {
        core.observeCredits()
        when (core.state.value) {
            LockState.Starting -> return
            is LockState.NoVault, is LockState.Unreadable -> relock() // "Try again"
            is LockState.Locked -> {
                val typing = core.visit?.takeIf { it.count > 0 }
                if (typing != null) core.showAgain(typing) else core.showLockScreen()
            }
            is LockState.Unlocked -> core.askAvailability()
        }
        core.publishSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // The pad: F2, F4, F5 (lock.md §6.3 C, A and E)

    /** Holds the mutex. [epoch] is `LockCore.inputEpoch` as it was when the key was pressed. */
    suspend fun digit(digit: Int, epoch: Long) {
        require(digit in 0..9) { "a digit key is 0 to 9" }
        val visit = padVisit(epoch) ?: return
        val pin = visit.target.lock.pin
        if (visit.count == 0) {
            // C. The first digit of an entry: the try is written to disk before anything is checked.
            when (pin.charge(visit.place)) {
                PinCharge.CHARGED -> Unit
                PinCharge.STORAGE_FULL -> { // C.3: nothing is counted and nothing is checked
                    visit.pinEvent = EntryPolicy.PinEvent.CHARGE_FAILED
                    core.newEntry(visit)
                    core.showLockScreen()
                    return
                }
                PinCharge.NOT_USABLE -> {
                    core.newEntry(visit)
                    core.showLockScreen()
                    return
                }
            }
        }
        visit.digits[visit.count++] = (ASCII_ZERO + digit).toByte()
        if (visit.count < PIN_SIZE) {
            core.showAgain(visit)
            return
        }
        sixthDigit(visit)
    }

    /** Holds the mutex: ⌫. Deleting back to none gives the entry up (§6.3 A). */
    fun deleteDigit(epoch: Long) {
        val visit = padVisit(epoch) ?: return
        if (visit.count == 0) return
        visit.digits[--visit.count] = 0
        if (visit.count == 0) visit.target.lock.pin.giveUp()
        core.showAgain(visit)
    }

    /** Holds the mutex: every digit goes, and the charge is refunded. */
    fun clearDigits(epoch: Long) {
        val visit = padVisit(epoch) ?: return
        if (visit.count == 0) return
        giveUpEntry(visit)
        core.showAgain(visit)
    }

    /** The visit whose pad takes a key pressed at [epoch], or null when the press is dropped. */
    private fun padVisit(epoch: Long): LockVisit? {
        if (epoch != core.inputEpoch) return null // the pad ignores input until the result is shown
        val shown = (core.state.value as? LockState.Locked)?.screen as? LockScreen.Pad ?: return null
        val visit = core.visit ?: return null
        if (shown.opening || visit.opening || visit.place.hasEnded) return null
        return visit
    }

    /** §6.3 A: the entry is given up before it is checked; its charge is refunded and the digits are zeroed. */
    private fun giveUpEntry(visit: LockVisit) {
        if (visit.count == 0 && visit.target.lock.pin.chargedAt !== visit.place) return
        visit.target.lock.pin.giveUp()
        core.newEntry(visit)
    }

    /** §6.3 E: the sixth digit. */
    private suspend fun sixthDigit(visit: LockVisit) {
        val target = visit.target
        val lock = target.lock
        visit.opening = true
        core.showAgain(visit) // "Opening…"
        val pin = visit.digits.copyOf()
        val outcome = try {
            lock.pin.check(pin, core.keptVkIds(target))
        } finally {
            Bytes.wipe(pin)
            core.newEntry(visit)
            visit.opening = false
        }
        when (outcome) {
            PinOutcome.NotCharged -> Unit
            PinOutcome.SameAsBefore -> { // §6.6: refunded, not counted again
                lock.interruptedAtStart = false
                visit.pinEvent = EntryPolicy.PinEvent.SAME_AGAIN
            }
            PinOutcome.KeyGone -> Unit // F15b: the record says so (`off = key_gone`), and §13.1 falls back
            PinOutcome.CannotCheck -> visit.pinEvent = EntryPolicy.PinEvent.CANNOT_CHECK // F15c: not offered again on this visit
            PinOutcome.Limit -> lock.interruptedAtStart = false // F5: the record says so (`off = limit`)
            is PinOutcome.Wrong -> { // F4: counted before anything is shown
                lock.interruptedAtStart = false
                visit.pinEvent = EntryPolicy.PinEvent.WRONG
            }
            PinOutcome.TooLate -> { // counted as right; its visit has ended, so nothing opens (I11)
                lock.interruptedAtStart = false
                return
            }
            is PinOutcome.Right -> {
                lock.interruptedAtStart = false
                afterRightPin(visit, outcome.keys)
                return
            }
        }
        if (visit.place.hasEnded) return
        core.showLockScreen()
        core.publishSummary()
    }

    /** §6.3 E.6: storage opens the vault with the VK of the matching `vk_id` (I7). */
    private suspend fun afterRightPin(visit: LockVisit, keys: Map<String, ByteArray>) {
        val target = visit.target
        val lock = target.lock
        val result = try {
            core.vault.open(target.ref, keys)
        } finally {
            keys.values.forEach { Bytes.wipe(it) }
        }
        when (result) {
            is VaultOpen.Opened -> {
                if (visit.place.hasEnded) { // ended while storage opened it: the key is dropped and the app stays locked (I11)
                    core.vault.lock()
                    lock.pin.flush()
                    return
                }
                unlocked(visit, UnlockBy.PIN, OpenVault(result.vaultId, result.generation, result.vkId, result.createdMs, result.olderCopy), null, false)
            }
            is VaultOpen.NeedsOtherKey -> { // F16a: the newest usable copy has another key; the PIN still counts as right
                lock.pin.flush()
                if (visit.place.hasEnded) return
                visit.needsOtherKeyCreatedMs = result.createdMs
                core.showLockScreen()
            }
            // The body does not open although the PIN was right: the file is damaged, which is
            // storage's flow (ux.md §5.9). The PIN still counts as right.
            VaultOpen.Damaged -> cannotOpen(visit, CurrentVault.Why.DAMAGED, flushPin = true)
            VaultOpen.Newer -> cannotOpen(visit, CurrentVault.Why.NEWER, flushPin = true)
            VaultOpen.Unreadable -> cannotOpen(visit, CurrentVault.Why.IO, flushPin = true)
        }
    }

    /**
     * Holds the mutex: a right PIN, fingerprint or master password (the pending one of F11 among
     * them), and no generation that can be used (lock.md §13.1 row 2, F17 step 4).
     *
     * - On the current vault's lock screen: "Your passwords can't be opened" ([LockState.Unreadable]),
     *   with "Try again" for an I/O error; the visit ends.
     * - On the lock screen of a vault put aside (build-plan.md §12, decision 6): that vault's screen
     *   stays and says that its copies cannot be opened ([LockScreen.CannotOpen]), with Back only,
     *   for the rest of the visit. The current vault is fine, so its "Your passwords can't be
     *   opened" is not shown: its "Restore from a backup file" would put the current vault aside.
     *   The same words whatever [why] is. Nothing is deleted, and nothing is written but the record
     *   of a right PIN ([flushPin]).
     */
    fun cannotOpen(visit: LockVisit, why: CurrentVault.Why, flushPin: Boolean) {
        if (flushPin) visit.target.lock.pin.flush()
        if (visit.place.hasEnded) return
        if (visit.target.aside != null) {
            visit.cannotOpen = true
            visit.opening = false
            giveUpEntry(visit) // the screen has no pad: a charge held for digits typed meanwhile is refunded (§6.3 A)
            core.showLockScreen()
            core.publishSummary()
            return
        }
        core.endVisitNow()
        core.closeVisit()
        core.target = null
        core.state.value = LockState.Unreadable(why, core.scan?.aside.orEmpty())
        core.publishSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // Which way in (F7 step 1)

    /**
     * Holds the mutex: "Forgot PIN? Use master password", "Use master password". A held charge is
     * refunded (F7 step 1, §6.3 A) and the master-password screen is shown, without a reason when
     * the PIN could have been used.
     */
    fun useMasterPassword() {
        val visit = lockScreenVisit() ?: return
        giveUpEntry(visit)
        visit.masterPasswordChosen = true
        core.showLockScreen()
    }

    /** Holds the mutex: "Use PIN" on a master-password screen; on a practice screen within its grace, "Not now: use PIN" (F10 step 3). */
    suspend fun usePin() {
        val visit = lockScreenVisit() ?: return
        val screen = (core.state.value as? LockState.Locked)?.screen as? LockScreen.MasterPassword ?: return
        if (screen.practice?.notNow != null) {
            core.shortcuts.practiceNotNow()
            return
        }
        visit.masterPasswordChosen = false
        visit.mpError = null
        core.showLockScreen()
    }

    private fun lockScreenVisit(): LockVisit? {
        if (core.state.value !is LockState.Locked) return null
        return core.visit?.takeIf { !it.place.hasEnded && !it.opening }
    }

    // ---------------------------------------------------------------------------------------------
    // The fingerprint: F3 (lock.md §9.2, §14)

    private class Launch(val visit: LockVisit, val number: Int, val alias: String, val wrap: KeyWrap)

    /**
     * Takes the mutex itself, twice, and holds none while the prompt is open (architecture.md
     * §5.2). [auto]: the prompt opens by itself, once per visit (§9.2 rule 7); otherwise the user
     * asked for it with the fingerprint key or "Use fingerprint".
     *
     * F3 step 2: before any prompt the key is probed (`Cipher.init` with the nonce of the wrap for
     * the newest `vk_id`), and a failure is classified by section 14 there: GONE removes the slot
     * (F15a, F15b), TEMPORARY hides the fingerprint key for this visit (F15c). Step 3: the prompt,
     * under a launch number of its own. Step 4: only 32 bytes that then open the vault count (I7).
     * A result whose launch is no longer outstanding, or whose visit has ended, is dropped (§9.2
     * rule 8, I11).
     *
     * A caller that is cancelled while the first turn runs (a scope that a rotation ends) never
     * gets the launch that turn made: the turn runs to its end under the mutex, and `withContext`
     * drops its answer on the way back (architecture.md §3.4). That launch is ended here, so that it
     * does not stay outstanding with no prompt behind it and refuse every later one of the visit
     * (rule 4); and a prompt that was to open by itself may do so at the next resume (rule 3).
     */
    suspend fun fingerprint(auto: Boolean) {
        var made: Launch? = null
        val launch = try {
            core.locked { prepare(auto).also { made = it } }
        } catch (e: CancellationException) {
            made?.let { dropped ->
                withContext(NonCancellable) {
                    core.locked { if (core.launchEnded(dropped.number) && auto) dropped.visit.autoPromptDone = false }
                }
            }
            throw e
        } ?: return
        val result = try {
            core.fingerprintKey.open(launch.alias, launch.wrap.nonce, launch.wrap.ct, PromptUse.UNLOCK, launch.number)
        } catch (e: CancellationException) {
            if (core.outstandingLaunch == launch.number) core.endLaunchNow()
            throw e
        } catch (e: RuntimeException) {
            FingerprintResult.Temporary(null) // lock.md §14.3: any other exception is TEMPORARY
        }
        // The prompt has answered: its result is handled to its end, also when the caller is cancelled
        // while it waits for the mutex, so that VK is wiped (or opens the vault) and the launch ends
        // (§9.2 rule 8). Only a lock or the end of the visit drops a result.
        withContext(NonCancellable) { core.locked { finish(launch, result, auto) } }
    }

    private fun prepare(auto: Boolean): Launch? {
        val visit = lockScreenVisit() ?: return null
        if (core.outstandingLaunch != 0) return null // §9.2 rule 4: a second call while a prompt is open is ignored
        val decision = visit.decision ?: return null
        // Only where the screen shows fingerprint (§9.2 rule 7, §13.2): the pad with its fingerprint
        // key, or the fingerprint-only screen. Never a master-password screen: on a practice screen
        // the fingerprint comes only after "Not now" (§8.3), so a fingerprint there would skip the
        // practice that is due, or required.
        fun offered(): Boolean {
            val shown = (core.state.value as? LockState.Locked)?.screen
            return ((shown is LockScreen.Pad && shown.fingerprintKey) || shown is LockScreen.FingerprintOnly) &&
                visit.decision?.fingerprintUsable == true
        }
        if (!offered()) return null
        if (auto) {
            if (visit.autoPromptDone || !decision.promptOpensItself) return null
            visit.autoPromptDone = true
        }
        val target = visit.target
        val vkId = core.newest(target)?.vkId ?: return null
        val slot = target.lock.fingerprint.slot ?: return null
        val wrap = slot.wraps.firstOrNull { it.vkId == vkId } ?: return null
        val alias = FingerprintKeeper.alias(slot.id)
        if (!auto) { // §6.3 A: the fingerprint key is tapped, so an entry that was begun is given up
            giveUpEntry(visit)
            core.showLockScreen()
            // That decided the screen again, and time may have changed it meanwhile (practice fell
            // due): the prompt opens only if the screen shown now still offers the fingerprint.
            if (!offered()) return null
        }
        val probe = try {
            core.fingerprintKey.probe(alias, wrap.nonce)
        } catch (e: RuntimeException) {
            Ks.Temporary(e)
        }
        when (probe) {
            is Ks.Ok -> Unit
            is Ks.Gone -> {
                fingerprintGone(visit, probe.why)
                return null
            }
            is Ks.Temporary -> { // F15c: its key on the pad is hidden for this visit; nothing else changes
                visit.fingerprintHidden = true
                visit.fingerprintLine = FingerprintLine.CannotCheck
                core.showLockScreen()
                return null
            }
        }
        return Launch(visit, core.newLaunch(), alias, wrap)
    }

    private suspend fun finish(launch: Launch, result: FingerprintResult, auto: Boolean) {
        val visit = launch.visit
        if (!core.launchEnded(launch.number) || visit.place.hasEnded || core.visit !== visit) {
            (result as? FingerprintResult.Ok)?.let { Bytes.wipe(it.bytes) } // dropped: no launch is outstanding, or its visit ended
            return
        }
        when (result) {
            is FingerprintResult.Ok -> {
                if (result.bytes.size != KeystoreErrors.VK_LENGTH) { // §14.3: a result that is not 32 bytes
                    Bytes.wipe(result.bytes)
                    fingerprintGone(visit, KeystoreErrors.GONE_BAD_WRAP)
                    return
                }
                val opened = try {
                    core.vault.open(visit.target.ref, mapOf(launch.wrap.vkId to result.bytes))
                } finally {
                    Bytes.wipe(result.bytes)
                }
                when (opened) {
                    is VaultOpen.Opened -> {
                        if (visit.place.hasEnded) {
                            core.vault.lock() // F3 step 4: the key is dropped and the app stays locked
                            return
                        }
                        // F3 step 5: a held charge is refunded when the visit closes; no count is reset.
                        unlocked(
                            visit,
                            UnlockBy.FINGERPRINT,
                            OpenVault(opened.vaultId, opened.generation, opened.vkId, opened.createdMs, opened.olderCopy),
                            null,
                            false,
                        )
                    }
                    is VaultOpen.NeedsOtherKey -> {
                        visit.needsOtherKeyCreatedMs = opened.createdMs
                        core.showLockScreen()
                    }
                    VaultOpen.Damaged -> cannotOpen(visit, CurrentVault.Why.DAMAGED, flushPin = false)
                    VaultOpen.Newer -> cannotOpen(visit, CurrentVault.Why.NEWER, flushPin = false)
                    VaultOpen.Unreadable -> cannotOpen(visit, CurrentVault.Why.IO, flushPin = false)
                }
            }
            is FingerprintResult.Closed -> core.showLockScreen() // codes 10, 13 and 5 show the pad
            is FingerprintResult.Gone -> fingerprintGone(visit, result.why)
            is FingerprintResult.Temporary -> {
                visit.fingerprintLine = KeystoreErrors.temporaryLine(result.code)
                if (KeystoreErrors.hidesKey(result.code)) visit.fingerprintHidden = true
                core.showLockScreen()
            }
            FingerprintResult.NotShown -> if (auto) visit.autoPromptDone = false // tried again at the next resume (§9.2 rule 3)
        }
    }

    /** GONE (lock.md §14.1): that one slot is removed, its alias deleted, and §13.1 falls back to the next way in (F15a, F15b). */
    private fun fingerprintGone(visit: LockVisit, why: String) {
        val reason = if (why == KeystoreErrors.GONE_BAD_WRAP) FingerprintRecord.OffReason.BAD_WRAP else FingerprintRecord.OffReason.KEY_GONE
        visit.target.lock.fingerprint.removeSlot(reason)
        visit.fingerprintLine = FingerprintLine.TurnedOff
        core.showLockScreen()
        core.publishSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // The master password: lock.md §6.8, F7 step 2

    /**
     * Holds the mutex: the master password typed on the lock screen during the visit [at] (taken
     * when it was submitted: a check that waited for the mutex still belongs to its visit).
     *
     * §6.8: the wait, if one is due; the try counted before Argon2id runs; every distinct slot
     * tried by the vault port; then right, older, wrong or "not enough memory". A result that comes
     * after its visit has ended updates the records as usual and opens nothing (step 8, I11).
     */
    suspend fun masterPasswordOnLockScreen(chars: CharArray, at: Place?): MasterPasswordOutcome {
        val visit = core.visit
        if (at == null || visit == null || visit.place !== at || at.hasEnded || visit.opening) return MasterPasswordOutcome.TooLate
        if ((core.state.value as? LockState.Locked)?.screen !is LockScreen.MasterPassword) return MasterPasswordOutcome.TooLate
        val bytes = MasterPasswordBytes.of(chars)
        try {
            return checkOnLockScreen(visit, bytes)
        } finally {
            Bytes.wipe(bytes)
        }
    }

    /**
     * Holds the mutex: §6.8 for the master-password bytes [bytes] on the lock screen of [visit], which
     * is the visit shown and has not ended: [masterPasswordOnLockScreen] once the characters are
     * bytes, and `OlderCopies` for "Open my passwords from before the change" (F16b step 3), which
     * opens with the password typed a moment ago. [bytes] stays the caller's, which wipes it.
     */
    suspend fun checkOnLockScreen(visit: LockVisit, bytes: ByteArray): MasterPasswordOutcome {
        val at = visit.place
        val target = visit.target
        val lock = target.lock
        val guard = lock.master
        guard.waitUntilElapsedMs()?.let { until ->
            core.showLockScreen()
            return MasterPasswordOutcome.Wait(until)
        }
        var opened: PasswordOpen? = null
        var pending: MasterPasswordOutcome? = null
        visit.opening = true
        core.showAgain(visit) // "Opening…" while Argon2id runs
        try {
            val attempt = guard.attempt(at) {
                val result = core.vault.openWithPassword(target.ref, bytes)
                opened = result
                when (result) {
                    is PasswordOpen.Opened -> MpCheck.RIGHT
                    PasswordOpen.Wrong -> {
                        // F11 step 13: when no password slot opens and a pending password is ready, the
                        // pending wrap is tried (one more Argon2id). If it opens, the flow is F11 step 14
                        // and not §6.8 step 5, and the try is given back.
                        pending = if (target.aside == null && core.pendingReady(lock)) core.passwords.typedPending(lock, bytes, at) else null
                        if (pending != null) MpCheck.NOT_COUNTED else MpCheck.WRONG
                    }
                    is PasswordOpen.OlderPassword -> MpCheck.WRONG // §6.8 step 6: nothing is opened, and the try stays counted
                    PasswordOpen.NoMemory -> MpCheck.NOT_COUNTED
                    // No generation can be used. Storage answers so when a slot opened and no copy under
                    // it could be read, and also when no slot was left to try, and then the password was
                    // never checked. Either way it is neither right (step 5: "a password slot opened and
                    // the vault is open") nor wrong (step 7: "wrong only when no slot opened"): the try is
                    // given back, and nothing that only a correct master password may change is changed.
                    PasswordOpen.Damaged, PasswordOpen.Newer, PasswordOpen.Unreadable -> MpCheck.NOT_COUNTED
                }
            }
            visit.opening = false
            return when (attempt) {
                is MpAttempt.Wait -> {
                    if (!at.hasEnded) core.showLockScreen()
                    MasterPasswordOutcome.Wait(attempt.untilElapsedMs)
                }
                is MpAttempt.Checked -> pending ?: afterMasterPassword(visit, attempt, checkNotNull(opened), bytes)
            }
        } finally {
            if (visit.opening) { // the vault port threw: the field again
                visit.opening = false
                if (!at.hasEnded && core.visit === visit) core.showLockScreen()
            }
        }
    }

    private suspend fun afterMasterPassword(visit: LockVisit, attempt: MpAttempt.Checked, opened: PasswordOpen, bytes: ByteArray): MasterPasswordOutcome {
        val lock = visit.target.lock
        // §6.8 step 5, the part of `pin.rec`: both counts return to zero, also for a result that comes too late (step 8).
        if (attempt.check == MpCheck.RIGHT) lock.pin.masterPasswordCorrect()
        if (attempt.tooLate) {
            if (opened is PasswordOpen.Opened) core.vault.lock() // the key is dropped, and the app stays locked
            return when (attempt.check) {
                MpCheck.RIGHT -> MasterPasswordOutcome.TooLate
                MpCheck.WRONG -> MasterPasswordOutcome.Wrong
                MpCheck.NOT_COUNTED -> if (opened == PasswordOpen.NoMemory) MasterPasswordOutcome.NoMemory else MasterPasswordOutcome.TooLate
            }
        }
        return when (opened) {
            is PasswordOpen.Opened -> {
                val proof = unlocked(
                    visit,
                    UnlockBy.MASTER_PASSWORD,
                    OpenVault(opened.vaultId, opened.generation, opened.vkId, opened.createdMs, opened.olderCopy),
                    bytes,
                    attempt.pendingCancelled,
                )
                if (proof != null) MasterPasswordOutcome.Right(proof) else MasterPasswordOutcome.TooLate
            }
            PasswordOpen.Wrong -> {
                val decision = visit.decision
                visit.mpError = MpError.Wrong(
                    offerForgot = lock.master.counts.failures >= FORGOT_AFTER_WRONG && decision != null &&
                        (decision.pinUsable || decision.fingerprintUsable),
                    replaced = if (visit.target.aside == null) core.replacedLine() else null,
                )
                core.showLockScreen()
                MasterPasswordOutcome.Wrong
            }
            is PasswordOpen.OlderPassword -> { // F16b: its screen, until `OlderCopies` has its answer
                visit.mpError = null
                visit.olderPassword = opened
                core.olderCopies.olderPasswordTyped(visit, bytes)
                core.showLockScreen()
                MasterPasswordOutcome.OlderPassword(opened.changedAtMs, unfinishedChange = core.changeUnfinished(visit.target))
            }
            PasswordOpen.NoMemory -> {
                visit.mpError = MpError.NoMemory
                core.showLockScreen()
                MasterPasswordOutcome.NoMemory
            }
            PasswordOpen.Damaged -> cannotOpenWithPassword(visit, CurrentVault.Why.DAMAGED)
            PasswordOpen.Newer -> cannotOpenWithPassword(visit, CurrentVault.Why.NEWER)
            PasswordOpen.Unreadable -> cannotOpenWithPassword(visit, CurrentVault.Why.IO)
        }
    }

    private fun cannotOpenWithPassword(visit: LockVisit, why: CurrentVault.Why): MasterPasswordOutcome {
        cannotOpen(visit, why, flushPin = false)
        return MasterPasswordOutcome.TooLate
    }

    /**
     * Holds the mutex: the master password typed in the prompt [at] of the open app, checked against
     * the open vault's own slot (lock.md §6.8, F12 step 2; the caller opened [at] before it took the
     * mutex and closes it afterwards). A right one returns both PIN counts to zero, counts as
     * practice, cancels a pending new master password (F11 step 9) and gives a proof.
     */
    suspend fun masterPasswordInApp(chars: CharArray, at: Place): MasterPasswordOutcome {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || at.hasEnded) return MasterPasswordOutcome.TooLate
        val lock = core.lockOf(open.vaultId)
        val bytes = MasterPasswordBytes.of(chars)
        try {
            val attempt = lock.master.attempt(at) {
                when (core.vault.checkPassword(bytes)) {
                    true -> MpCheck.RIGHT
                    false -> MpCheck.WRONG
                    null -> MpCheck.NOT_COUNTED // not enough memory, or no answer for this vault now
                }
            }
            return when (attempt) {
                is MpAttempt.Wait -> MasterPasswordOutcome.Wait(attempt.untilElapsedMs)
                is MpAttempt.Checked -> when (attempt.check) {
                    MpCheck.RIGHT -> {
                        lock.pin.masterPasswordCorrect()
                        if (attempt.pendingCancelled) core.addNotice { Notice.PendingCancelled(it) }
                        core.publishSummary()
                        if (attempt.tooLate) MasterPasswordOutcome.TooLate else MasterPasswordOutcome.Right(core.newProof(bytes, lock.vaultId))
                    }
                    MpCheck.WRONG -> MasterPasswordOutcome.Wrong
                    MpCheck.NOT_COUNTED -> MasterPasswordOutcome.NoMemory
                }
            }
        } finally {
            Bytes.wipe(bytes)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // A PIN typed in a prompt of the open app (lock.md §6.3; F6 step 2, F8 step 3, F11 step 3)

    /**
     * Takes the mutex itself: a counted PIN check in a prompt inside the open app, the whole
     * protocol of §6.3 on the open vault's own `pin.rec`: the charge, its second of age, the check.
     * Nothing is forgiven there, every wrong PIN typed there is reported at the next unlock, and a
     * limit turns the PIN off with the app staying open (§6.5 step 3). A lock or `ON_STOP` while it
     * runs gives the entry up, or makes a right result [PinOutcome.TooLate].
     *
     * What comes back is the guard's outcome, with two additions: [PinOutcome.CannotCheck] also when
     * the charge could not be written, and [PinOutcome.NotCharged] when no PIN can be typed now.
     * The receiver wipes the keys of [PinOutcome.Right] and decides what a right PIN proves
     * (`LockCore.vault.withVaultKey` gives the open vault's key to compare with, F6 step 2). When the
     * receiver is cancelled before the result reaches it, the keys are wiped here.
     *
     * @param useKek F8 step 3 only: receives a copy of `KEK_pin` after a right PIN, to wrap the new
     *   key in F8 step 5; the receiver wipes it.
     */
    suspend fun pinInPrompt(pin: ByteArray, useKek: ((ByteArray) -> Unit)? = null): PinOutcome {
        val at = core.openPrompt() // before the mutex: it belongs to the app as it is open now
        var made: PinOutcome? = null
        try {
            return core.locked { pinAt(pin, at, useKek).also { made = it } }
        } catch (e: CancellationException) {
            // The step ran to its end, and its caller, who was to wipe the keys, is gone (§4.2).
            (made as? PinOutcome.Right)?.keys?.values?.forEach { Bytes.wipe(it) }
            throw e
        } finally {
            core.closePrompt(at)
        }
    }

    /**
     * Holds the mutex: the counted PIN check of [pinInPrompt] in the prompt [at], which the caller
     * opened before it took the mutex and closes afterwards. For a flow that goes on under the same
     * turn of the mutex with what the PIN gave (F8 steps 3 to 6, F11 step 3): no other step can then
     * change the slot between the check and the use of its `KEK_pin`.
     */
    suspend fun pinAt(pin: ByteArray, at: Place, useKek: ((ByteArray) -> Unit)? = null): PinOutcome {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || at.hasEnded) return PinOutcome.NotCharged
        val lock = core.lockOf(open.vaultId)
        lock.startPin()
        when (lock.pin.charge(at)) {
            PinCharge.CHARGED -> Unit
            PinCharge.STORAGE_FULL -> return PinOutcome.CannotCheck
            PinCharge.NOT_USABLE -> return PinOutcome.NotCharged
        }
        // No wrap is trimmed here: a prompt is not an unlock (§5.4), and the list of kept
        // keys is the one of the last lock screen.
        val outcome = lock.pin.check(pin, null, useKek)
        if (outcome is PinOutcome.Right) lock.pin.flush()
        core.publishSummary()
        return outcome
    }

    /**
     * Holds the mutex, from inside a step that found the app open: the app is locked as
     * `LockEngine.lock` locks it (lock.md §10.4): open prompts end (a charge held in one is
     * refunded), the proofs are dropped, a fingerprint launch ends and its prompt is cancelled, the
     * vault port drops its session, and the lock screen of what is there now is shown. It never
     * writes vault data. For a flow that cannot go on in the open app: the vault port ended its
     * session after a write whose end is not known (F8 step 6, F11 step 16), or the lock screen is
     * where the next step is (F16b, "It wasn't me" after more than one replacement).
     */
    suspend fun lockFromInside() {
        core.promptsEnded(core.endPromptsNow())
        core.dropProofs()
        core.passwords.dropProof()
        core.endLaunchNow()
        try {
            core.vault.lock()
        } finally {
            relock()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // After the vault has opened

    /** The vault of [visit]'s lock screen is open in the vault port. A vault put aside goes on in `VaultFlows` (F17 step 4). */
    private suspend fun unlocked(visit: LockVisit, by: UnlockBy, opened: OpenVault, proofBytes: ByteArray?, pendingCancelled: Boolean): MasterProof? {
        val target = visit.target
        if (target.aside != null) return core.vaults.asideOpened(target, by, opened, proofBytes)
        return completeUnlock(target.lock, by, opened, proofBytes, pendingCancelled)
    }

    /**
     * Holds the mutex: the current vault is open in the vault port, by [by]; this is everything an
     * unlock does afterwards, and the app counts as unlocked when it returns. `VaultFlows` calls it
     * too, once a vault put aside has been made current (after `LockCore.rescan` and `retarget`).
     *
     * 1. The visit ends (§6.3): a held charge is refunded (F3 step 5), the digits go.
     * 2. The wraps (§5.4): both shortcut records drop the wraps whose `vk_id` no kept generation
     *    carries; a master-password unlock first lets storage retire the generations under old keys
     *    (F8 step 10). A slot left without a wrap is removed (`off = master_changed`).
     * 3. `state.rec`: a missing or damaged one is made again (§5.3; F14 step 2); `max_generation`
     *    follows the generation that opened, or a lower one is reported (F16c); a change of master
     *    password that never reached the disk is forgotten (§5.6).
     * 4. The notices, in the order of §13.4, and the proof of a typed master password (§5.8). After
     *    a master-password unlock a `fingerprint.rec` that read DAMAGED is replaced by one without a
     *    slot, with the offer "Turn fingerprint unlock on again?" (§5.3, §13.4 item 5).
     * 5. **The list is shown** ([LockState.Unlocked]) before any of these records is written: the
     *    record of a right PIN is written right after, still under the mutex, so that no other step
     *    can overtake it (§6.3 E.6, decision 27), and the other writes follow it. All are best effort.
     *
     * @param proofBytes the verified master-password bytes of this unlock (copied), or null.
     * @param pendingCancelled that master password cancelled a pending new one (F11 step 9).
     * @return the proof made from [proofBytes], or null.
     */
    suspend fun completeUnlock(lock: VaultLock, by: UnlockBy, opened: OpenVault, proofBytes: ByteArray?, pendingCancelled: Boolean): MasterProof? {
        val visit = core.visit
        val chosen = visit?.masterPasswordChosen == true
        val after = visit?.afterUnlock
        core.endVisitNow()
        core.closeVisit()

        // The wraps. A right PIN trimmed its own record in the check; its write waits below.
        val listed = core.target?.takeIf { it.lock === lock }?.let { core.keptVkIds(it) }
        var kept = listed
        if (by == UnlockBy.MASTER_PASSWORD) {
            val retired = try {
                core.vault.retireOldKeys() // F8 step 10: storage decides whether the old keys may go yet
            } catch (e: Exception) {
                null
            }
            // Storage's answer names the keys of the generations it could read just now. When the
            // listing before this unlock could not read every live file, a key whose file is only
            // unreadable for now would be missing from it, so nothing is trimmed then (§5.4, §5.10).
            if (listed != null && retired != null) kept = retired
        }
        // An older copy opened: the damaged ones above it are still on disk, and nothing is removed
        // for a mismatch (§5.10, F16a).
        if (opened.olderCopy) kept = null
        if (kept != null) {
            if (by != UnlockBy.PIN) lock.pin.keepWrapsFor(kept)
            lock.fingerprint.keepWrapsFor(kept)
        }

        // state.rec, in memory first.
        val nowWall = core.clock.wallMs()
        val before = lock.state
        var rolledBackAtMs: Long? = null
        var newMax: StateRecord.MaxGeneration? = null
        var forgetChange = false
        if (before != null) {
            if (opened.generation < before.maxGeneration.n) rolledBackAtMs = before.maxGeneration.atMs
            if (opened.generation > before.maxGeneration.n) newMax = StateRecord.MaxGeneration(opened.generation, nowWall)
            val change = before.mpChange
            forgetChange = by != UnlockBy.MASTER_PASSWORD && change != null && kept != null && change.toVkId !in kept
        }

        // The notices of §13.4, in its order.
        val notices = ArrayList<Notice>()
        if (opened.olderCopy) notices += Notice.OlderCopy(core.nextNoticeId(), opened.createdMs)
        rolledBackAtMs?.let { notices += Notice.OlderThanLastTime(core.nextNoticeId(), it) }
        core.open = opened
        core.publishSummary()
        val facts = core.summary.value
        if (pendingCancelled) {
            notices += Notice.PendingCancelled(core.nextNoticeId())
        } else {
            facts.pending?.let { p ->
                notices += if (p.ready) {
                    Notice.PendingReady(core.nextNoticeId(), p.requestedAtMs, p.by)
                } else {
                    Notice.Pending(core.nextNoticeId(), p.requestedAtMs, p.by, p.remainingMs)
                }
            }
        }
        facts.replaced?.let { notices += Notice.Replaced(core.nextNoticeId(), it.atMs, it.by, it.oldVaultId) }
        wrongTries(lock)?.let { notices += it }
        choosePin(lock, by, chosen)?.let { notices += it }
        val fingerprintOff = lock.fingerprint.record?.takeIf { it.slot == null }?.off?.reason
        // A damaged `fingerprint.rec` is handled as F15a handles a key that is gone (§5.3): the offer
        // comes with the next master-password unlock, and the damaged record goes at that unlock,
        // whatever the answer, so that it no longer blocks the key sweep (§5.7).
        val fingerprintDamaged = by == UnlockBy.MASTER_PASSWORD && lock.fingerprint.read is RecordRead.Damaged
        if ((fingerprintOff in FINGERPRINT_AGAIN || fingerprintDamaged) && !core.noSecureHardware) {
            notices += Notice.FingerprintAgain(core.nextNoticeId())
        }
        if (by == UnlockBy.MASTER_PASSWORD && anyUnreadable(lock)) notices += Notice.StorageUnreadable(core.nextNoticeId())

        val proof = proofBytes?.let { core.newProof(it, lock.vaultId) }
        core.state.value = LockState.Unlocked(notices, proof)
        core.publishSummary()

        // The writes nobody waits for, the right PIN's record first.
        lock.pin.flush()
        if (fingerprintDamaged) lock.fingerprint.replaceDamaged()
        if (before == null) {
            val read = lock.master.lastRead
            if (read is RecordRead.Missing || read is RecordRead.Damaged) {
                // §5.3: a new record after the next successful unlock (F14 step 2 for a vault without lock records).
                val practice = PracticeSchedule.afterMissingRecord(nowWall, byMasterPassword = by == UnlockBy.MASTER_PASSWORD)
                lock.master.establish(newStateRecord(lock.vaultId, practice, opened.generation, nowWall))
            }
        } else if (newMax != null || forgetChange) {
            lock.master.update { record ->
                record.copy(maxGeneration = newMax ?: record.maxGeneration, mpChange = if (forgetChange) null else record.mpChange)
            }
        }
        core.observeCredits()
        after?.invoke(by)
        return proof
    }

    /** §13.4 item 4: wrong tries nobody has been told about, from `unseen` of both records. */
    private fun wrongTries(lock: VaultLock): Notice.WrongTries? {
        val record = lock.pin.record
        val pins = record?.unseen?.count ?: 0
        val masters = lock.master.counts.unseen
        if (pins == 0 && masters == 0) return null
        val total = record?.takeIf { it.slot != null }?.guard?.total ?: 0
        val beforeOff = if (total >= WARN_AT_TOTAL) PinGuard.TOTAL_LIMIT - total else null
        val practice = lock.state?.let { PracticeSchedule(it.practice.lastOkMs, it.practice.step, it.practice.snoozedUntilMs) }
        val practiceRequired = practice?.status(core.clock.wallMs(), lock.state?.pendingMp != null) == PracticeSchedule.Status.REQUIRED
        return Notice.WrongTries(
            id = core.nextNoticeId(),
            wrongPins = pins,
            lastWrongPinAtMs = record?.unseen?.lastAtMs,
            interrupted = record?.unseen?.interrupted == true,
            pinsBeforeOff = beforeOff,
            offerNewMasterPassword = beforeOff != null && practiceRequired,
            wrongMasterPasswords = masters,
        )
    }

    /**
     * §13.4 item 5, "Choose a new PIN": after a master-password unlock without a PIN that works
     * (F5 step 4, F7 step 3, F14, F15b, a damaged record, a PIN never set; ux.md §4), when this
     * phone can hold a key (F1 step 5; otherwise F18, and nothing is offered); after a fingerprint
     * unlock, once per process, the offer that still needs the master password (F5 step 4).
     */
    private fun choosePin(lock: VaultLock, by: UnlockBy, chosen: Boolean): Notice.ChoosePin? {
        if (by == UnlockBy.PIN) return null
        val target = core.target?.takeIf { it.lock === lock && it.aside == null } ?: return null
        val pin = (core.decide(target, null) as? EntryPolicy.Decision.Locked)?.pin ?: return null
        val typed = by == UnlockBy.MASTER_PASSWORD
        val reason = when (pin) {
            EntryPolicy.PinState.USABLE -> if (typed && chosen) ChoosePinReason.FORGOTTEN else null
            // F16b step 6: the PIN of a vault that a replacement put aside went with `off = replaced`,
            // because whoever replaced the master password knew it: "Someone else may know the old one."
            EntryPolicy.PinState.NOT_SET -> when {
                !typed -> null
                lock.pin.record?.off?.reason == PinRecord.OffReason.REPLACED -> ChoosePinReason.SOMEONE_MAY_KNOW
                else -> ChoosePinReason.NONE_HERE
            }
            EntryPolicy.PinState.LIMIT -> ChoosePinReason.TURNED_OFF
            EntryPolicy.PinState.STOPPED_WORKING -> ChoosePinReason.STOPPED_WORKING
            EntryPolicy.PinState.MASTER_CHANGED -> ChoosePinReason.MASTER_CHANGED
            EntryPolicy.PinState.NEWER_APP, EntryPolicy.PinState.CANNOT_CHECK, EntryPolicy.PinState.STORAGE_FULL,
            EntryPolicy.PinState.OLDER_COPY, EntryPolicy.PinState.NOT_OFFERED,
            -> null
        } ?: return null
        if (typed) {
            if (core.canHoldKeys() != KeyCreate.Ok) return null
        } else {
            if (lock.choosePinOffered) return null
            lock.choosePinOffered = true
        }
        return Notice.ChoosePin(core.nextNoticeId(), reason, needsMasterPassword = !typed)
    }

    private fun anyUnreadable(lock: VaultLock): Boolean =
        lock.pinRead is RecordRead.Unreadable || lock.fingerprint.read is RecordRead.Unreadable || lock.stateRead is RecordRead.Unreadable

    /** A new `state.rec` for [vaultId]: [practice], no counts, no mark, and [generation] as the highest this phone has opened. */
    fun newStateRecord(vaultId: String, practice: PracticeSchedule, generation: Long, nowWall: Long): StateRecord = StateRecord(
        vaultId = vaultId,
        practice = StateRecord.Practice(practice.lastOkMs, practice.step, practice.snoozedUntilMs),
        mpGuard = StateRecord.MpGuard(failures = 0, lastFailElapsedMs = null, unseen = 0), // the guard writes its own counts
        mpChange = null,
        pendingMp = null,
        replaced = null,
        maxGeneration = StateRecord.MaxGeneration(generation, nowWall),
    )

    // ---------------------------------------------------------------------------------------------
    // The answers to the notices (lock.md §13.4)

    /** Holds the mutex. */
    suspend fun answerNotice(id: Long, answer: NoticeAnswer) {
        val notice = core.removeNotice(id) ?: return
        val open = core.open
        val lock = open?.let { core.knownLock(it.vaultId) }
        when (notice) {
            is Notice.OlderCopy, is Notice.PendingCancelled, is Notice.ChoosePin -> Unit
            // F12 step 2: the entry that the offer was made on is kept only until the offer is answered.
            // "Back up now" hands it to the backup that this answer starts; any other answer ends its
            // flow, and the proof of this unlock is dropped (§5.8). The offer is the last notice of
            // §13.4, so no notice after it wants that proof.
            is Notice.BackupNow -> if (answer != NoticeAnswer.BACK_UP_NOW) dropUnlockProof()
            is Notice.OlderThanLastTime ->
                // The owner has been told: from now on this copy is the one this phone has opened (F16c).
                if (lock != null) lock.master.update { it.copy(maxGeneration = StateRecord.MaxGeneration(open.generation, core.clock.wallMs())) }
            // "Finish now" is `LockEngine.finishPending`, once the new master password has been typed.
            is Notice.Pending, is Notice.PendingReady -> if (answer == NoticeAnswer.CANCEL_IT) core.passwords.cancelPending()
            is Notice.Replaced -> if (answer == NoticeAnswer.IT_WASNT_ME) core.olderCopies.itWasntMe(notice.oldVaultId)
            is Notice.WrongTries -> if (lock != null) {
                // The number that was shown is taken off `unseen`; a notice never shown takes nothing off.
                lock.pin.unseenShown(notice.wrongPins, notice.interrupted)
                lock.master.unseenShown(notice.wrongMasterPasswords)
            }
            is Notice.FingerprintAgain -> lock?.fingerprint?.explained() // `off` is only for the one-time explanation (§5.4)
            is Notice.StorageUnreadable ->
                if (answer == NoticeAnswer.SET_UP_AGAIN && lock != null && lock.stateRead is RecordRead.Unreadable) {
                    // "Set up again" writes a new record (§5.3); the master password was typed for this unlock.
                    val practice = PracticeSchedule.afterMissingRecord(core.clock.wallMs(), byMasterPassword = true)
                    lock.master.establish(newStateRecord(lock.vaultId, practice, open.generation, core.clock.wallMs()))
                }
        }
        core.publishSummary()
    }

    /** Holds the mutex: the proof of the unlock that the open app shows is zeroed and taken off the state (lock.md §5.8). */
    private fun dropUnlockProof() {
        val now = core.state.value as? LockState.Unlocked ?: return
        val proof = now.proof ?: return
        core.dropProof(proof)
        core.state.value = now.copy(proof = null)
    }

    // ---------------------------------------------------------------------------------------------
    // F1: first setup

    /**
     * Takes the mutex itself: lock.md F1 steps 2 to 5. Both typings are compared before anything is
     * derived, and a password with a code point Unicode has not assigned is refused [V-C9]; then
     * [newVault].
     */
    suspend fun createVault(first: CharArray, again: CharArray, created: suspend () -> Unit): CreateOutcome {
        if (!MasterPasswordBytes.same(first, again)) return CreateOutcome.Mismatch
        MasterPasswordBytes.firstUnassigned(first)?.let { return CreateOutcome.Unassigned(it) }
        return core.locked {
            if (core.state.value !is LockState.NoVault) return@locked CreateOutcome.NotSaved // setup starts on Welcome only
            val firstBytes = MasterPasswordBytes.of(first)
            val againBytes = MasterPasswordBytes.of(again)
            try {
                newVault(firstBytes, againBytes, from = null, putCurrentAside = false, restored = false, created = created)
            } finally {
                Bytes.wipe(firstBytes, againBytes)
            }
        }
    }

    /**
     * Holds the mutex: makes a vault on this phone and opens it (lock.md F1 steps 3 to 5; F13 steps
     * 4 to 6 and F17 step 3 use it with [from] and [restored]).
     *
     * - Step 3: the vault port makes `vault_id`, VK and `vk_id`, writes generation 1 with a slot
     *   from [first], reads it back and opens it with [again] [V-C5]. On failure nothing else exists.
     * - Step 4: `lock/<vault_id>/` and its `state.rec`: practice step 0 and `last_ok` now, or step 3
     *   after a restore (§8.3). Best effort: without it the next start finds a vault without lock
     *   records, which is F14.
     * - [created] runs: setup writes its default settings before the app counts as unlocked (§10.1).
     * - Step 5: can this phone hold a key? Then "Choose a PIN" is offered with the proof of [again]
     *   (step 6); otherwise F18, and the app opens with the master password only.
     *
     * With [putCurrentAside] the vault that was current goes aside in the same step; its lock
     * records and keys stay where they are (F17).
     */
    suspend fun newVault(
        first: ByteArray,
        again: ByteArray,
        from: Vault?,
        putCurrentAside: Boolean,
        restored: Boolean,
        created: suspend () -> Unit,
    ): CreateOutcome {
        val write = try {
            core.vault.createVault(core.random.uuid(), first, again, from, putCurrentAside)
        } catch (e: KdfUnavailable) {
            return CreateOutcome.NoMemory
        }
        if (write !is VaultWrite.Done) {
            if (write is VaultWrite.Unclear) relock() // in place but perhaps not durable: nothing is open, and the next screen says what is there
            return CreateOutcome.NotSaved
        }
        core.endVisitNow()
        core.closeVisit()
        val nowWall = core.clock.wallMs()
        val lock = core.lockOf(write.vaultId)
        val practice = if (restored) PracticeSchedule.restored(nowWall) else PracticeSchedule.newMasterPassword(nowWall)
        lock.master.establish(newStateRecord(write.vaultId, practice, write.generation, nowWall))
        try {
            created()
        } catch (e: Exception) {
            // The vault exists; a setting that was not written reads as its strictest value (§10.1).
        }
        core.rescan()
        core.retarget()
        val notices = if (core.canHoldKeys() == KeyCreate.Ok) {
            listOf(Notice.ChoosePin(core.nextNoticeId(), ChoosePinReason.NONE_HERE, needsMasterPassword = false))
        } else {
            emptyList()
        }
        core.open = OpenVault(write.vaultId, write.generation, write.vkId, write.createdMs, olderCopy = false)
        core.state.value = LockState.Unlocked(notices, core.newProof(again, write.vaultId))
        core.publishSummary()
        return CreateOutcome.Created(write.vaultId)
    }

    private companion object {
        const val ASCII_ZERO = 0x30
        const val PIN_SIZE = 6

        /** "After 2 wrong tries, when a shortcut is usable" (F10 step 5, §13.3). */
        const val FORGOT_AFTER_WRONG = 2

        /** "If `total` is 6 or more" (§13.4 item 4). */
        const val WARN_AT_TOTAL = 6

        /** The `off` reasons of `fingerprint.rec` after which "Turn fingerprint unlock on again?" is offered once (F15a, F15b, F8 step 7). */
        val FINGERPRINT_AGAIN = setOf(
            FingerprintRecord.OffReason.KEY_GONE,
            FingerprintRecord.OffReason.BAD_WRAP,
            FingerprintRecord.OffReason.MASTER_CHANGED,
        )
    }
}
