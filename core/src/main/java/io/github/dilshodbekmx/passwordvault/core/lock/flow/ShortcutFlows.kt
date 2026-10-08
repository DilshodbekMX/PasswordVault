package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.pin.PinBlocklist
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The flows of the two shortcuts and of practice (lock.md §12): **F6** set or change the PIN, **F9**
 * turn fingerprint unlock on or off, **F10** practice. Build plan 3.13.
 *
 * Each function says whether it **holds the mutex** (it is called inside `LockCore.locked`, by
 * `LockEngine` or by another flow) or **takes the mutex itself** (it must, when a prompt is open in
 * between: no mutex is held while a fingerprint prompt is open, architecture.md §5.2).
 *
 * **What each action needs** (lock.md §8.2). Setting a PIN and turning fingerprint unlock on add a
 * way in, so they need the master password typed in the same flow: a `MasterProof` that this engine
 * made for the open vault and has not dropped (`LockCore.holds`). Changing the PIN needs the current
 * PIN typed now, as a counted check in a prompt of the open app (§6.3), or the master password
 * ("Use master password instead" is [setPin] with that proof). Turning fingerprint unlock off, and
 * "Not now" on a practice screen within its grace, need nothing. A fingerprint approves none of
 * them.
 *
 * **Keys** (lock.md §5.7). A new PIN or fingerprint key is made only after the canary (F6 step 4,
 * F9 step 4); a key that a new slot *replaces* is never deleted here: the sweep deletes it at a later
 * start, once the record that replaced it has been read back from disk. A new key that no record
 * came to name is deleted at once (F6 step 8, F9 step 9), with one exception, which follows from the
 * same rule of §5.7 and from test T14 ("no key named by a durable record was deleted"): when the
 * record write itself reports a failure, the new key is left to the sweep, because such a write may
 * still have replaced the record (a failed directory sync counts as "not written", §5.9, while the
 * rename is already on disk). If the record did change, the next start finds a key behind it; if it
 * did not, the next start's sweep deletes the key, which no record names.
 *
 * **What the core already does, so that it is not done twice.**
 * - F15 on the lock screen is `UnlockFlows`: a PIN key that is GONE or does not answer (`PinGuard`,
 *   §6.3 E.3), a fingerprint key that is GONE at the probe or after the prompt (the slot removed
 *   with `off = key_gone` or `bad_wrap`, the alias deleted, the line of §13.3), and TEMPORARY (the
 *   key hidden for the visit, nothing stored). After the unlock `completeUnlock` offers "Choose a
 *   new PIN" and "Turn fingerprint unlock on again?" (§13.4 item 5), which lead here. What is left
 *   of F15 for this file is the same reaction inside F6 and F9: a key that cannot be made is NOT NOW
 *   (nothing changes), a PIN key that is GONE or does not answer while the current PIN is checked
 *   in "Change PIN" is refunded and reported as "can't be checked", and F15d, a shortcut that keeps
 *   failing, is set up again here with a new key ([setPin] after "Use master password instead";
 *   [fingerprintOff], then [fingerprintOn]).
 * - The practice screen itself is `EntryPolicy` rows 3 and 4; a correct master password there is
 *   `UnlockFlows.masterPasswordOnLockScreen`, and `MasterPasswordGuard` moves the schedule (§8.3).
 *   "I can't remember it" (F10 step 4) is `PasswordFlows.cantRemember` (build plan 4.4).
 */
internal class ShortcutFlows(private val core: LockCore) {

    // ---------------------------------------------------------------------------------------------
    // F6: set or change the PIN

    /**
     * Holds the mutex. F6 "Set" (and F1 step 6, F5 step 4, F7 step 3, F14, F15b, F16b step 6, and
     * "Change PIN" › "Use master password instead"): [pin] is six ASCII digits, [proof] the master
     * password of this flow for the open vault. Steps 4 to 8: the canary, a new key
     * `pv_pin_<new id>`, `KEK_pin`, a wrap of the open vault's VK with a fresh nonce, the test of that
     * wrap, then `PinGuard.setPin(slot, masterPasswordTyped = true)`: `row` and `total` go to 0
     * (§6.4), `unseen` is kept, and the remembered wrong PINs are forgotten (§6.6). A slot that was
     * there is replaced; its key is left to the sweep.
     *
     * False, with nothing changed (the old record stays, and so does the old PIN where one works):
     * the app is not unlocked, [proof] is not one this engine holds for the open vault, the PIN is
     * one the blocklist refuses (§7.2: the screen checks first, with `LockManager.pinProblem`; this
     * is the engine's own guard), `pin.rec` was written by a newer app (§5.3), this phone cannot
     * hold a key now or ever (F18), or a step of 4 to 7 failed. [pin] stays the caller's, which
     * wipes it.
     *
     * @throws IllegalArgumentException when [pin] is not six ASCII digits: the pad produces nothing else.
     */
    suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean {
        require(PinWrap.isPin(pin)) { "a PIN is six ASCII digits" }
        val open = openVault() ?: return false
        if (!core.holds(proof) || proof.vaultId != open.vaultId) return false
        // A lock or ON_STOP drops the proof at once, without the mutex (§5.8): asked again before the write.
        return newPin(core.lockOf(open.vaultId), pin, masterPasswordTyped = true) { core.holds(proof) }
    }

    /**
     * Takes the mutex itself. F6 "Change": [current] is a counted check in a prompt of the open app
     * (`UnlockFlows.pinInPrompt`, §6.3) that must yield the open vault's key; then [new], which must
     * differ, replaces the slot as in [setPin], with `masterPasswordTyped = false` (§6.4: `total`
     * unchanged). Both arrays stay the caller's.
     *
     * - [new] equal to [current]: [PinChangeOutcome.SameAsCurrent], before anything is charged
     *   (comparing the two typings tells nothing about the PIN).
     * - [new] refused by the blocklist: [PinChangeOutcome.NotSaved], before anything is charged
     *   (the screen checks first; this is the engine's own guard).
     * - Wrong: counted, never forgiven, reported at the next unlock (§6.3, §13.4):
     *   [PinChangeOutcome.Wrong]. The same wrong PIN again: [PinChangeOutcome.SameWrongAgain].
     * - A limit: the PIN is turned off (§6.5) and the app stays open: [PinChangeOutcome.Limit].
     * - The PIN cannot be checked (TEMPORARY, the charge cannot be written, no PIN can be typed now)
     *   or its key is GONE (the slot is removed with `off = key_gone`, F15b): the try is refunded,
     *   and [PinChangeOutcome.CannotCheck] says "Use your master password instead". So does a right
     *   PIN that does not yield the open vault's key: its slot opens only a key from before a
     *   master-password change, and a new PIN under the current key would add a way in (§8.2).
     * - A lock or `ON_STOP` while the change runs ends it (its prompt has ended, I11): nothing
     *   changes, [PinChangeOutcome.NotSaved]. So does a failure of steps 4 to 7.
     *
     * @throws IllegalArgumentException when either PIN is not six ASCII digits, before anything is charged.
     */
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChangeOutcome {
        require(PinWrap.isPin(current) && PinWrap.isPin(new)) { "a PIN is six ASCII digits" }
        if (Bytes.equal(current, new)) return PinChangeOutcome.SameAsCurrent
        if (PinBlocklist.rule(new) != 0) return PinChangeOutcome.NotSaved
        // The whole change belongs to the app as it is open now: a lock or ON_STOP ends this prompt,
        // and then nothing is changed, whichever step it came at (§6.3, "a prompt has a number of its own").
        val at = core.openPrompt()
        try {
            return when (val checked = core.unlock.pinInPrompt(current)) {
                is PinOutcome.Right -> try {
                    core.locked { replacePin(checked.keys, new, at) }
                } finally {
                    checked.keys.values.forEach { Bytes.wipe(it) }
                }
                is PinOutcome.Wrong -> PinChangeOutcome.Wrong(checked.left)
                PinOutcome.SameAsBefore -> PinChangeOutcome.SameWrongAgain
                PinOutcome.Limit -> PinChangeOutcome.Limit
                PinOutcome.CannotCheck, PinOutcome.KeyGone, PinOutcome.NotCharged -> PinChangeOutcome.CannotCheck
                PinOutcome.TooLate -> PinChangeOutcome.NotSaved // right, and counted so; its prompt ended, so nothing opens (I11)
            }
        } finally {
            core.closePrompt(at)
        }
    }

    /** Holds the mutex: F6 steps 3 to 7 of "Change", after the current PIN was right in the prompt [at] and yielded [keys]. */
    private suspend fun replacePin(keys: Map<String, ByteArray>, new: ByteArray, at: Place): PinChangeOutcome {
        val open = openVault()
        if (open == null || at.hasEnded) return PinChangeOutcome.NotSaved
        // F6 step 2: the check "must yield the open vault's key".
        val yields = try {
            core.vault.withVaultKey { vkId, vk -> keys[vkId]?.let { Bytes.equal(it, vk) } == true }
        } catch (e: RuntimeException) {
            false
        }
        if (!yields) return PinChangeOutcome.CannotCheck
        val changed = newPin(core.lockOf(open.vaultId), new, masterPasswordTyped = false) { !at.hasEnded }
        return if (changed) PinChangeOutcome.Changed else PinChangeOutcome.NotSaved
    }

    /**
     * Holds the mutex: F6 steps 4 to 8 for the vault of [lock], which is open. A new slot with one
     * wrap, for the key of the open vault, made, tested and written; true when the record is on disk.
     * [wanted] says whether the flow that asked still stands (its proof is held, its prompt has not
     * ended): a lock or `ON_STOP` ends it at once, without the mutex, so it is asked before the key
     * is made and again just before the record is written.
     */
    private suspend fun newPin(lock: VaultLock, pin: ByteArray, masterPasswordTyped: Boolean, wanted: () -> Boolean): Boolean {
        // The guard writes the new record over the one it read at its start-up step (§6.3 S): that
        // step must have run, or a record on disk would be overwritten unread.
        lock.startPin()
        if (lock.pinRead == RecordRead.Newer) return false // a newer app owns it: never replaced (§5.3)
        if (PinBlocklist.rule(pin) != 0) return false // §7.2: there is no "use it anyway"
        if (core.canHoldKeys() != KeyCreate.Ok) return false // step 4: the canary; F18 or NOT NOW
        if (!wanted()) return false

        val slotId = core.random.uuid()
        val alias = PinWrap.alias(slotId)
        val made = try {
            core.pinKey.create(alias)
        } catch (e: RuntimeException) {
            KeyCreate.NotNow
        }
        if (made != KeyCreate.Ok) { // NOT NOW (§14.3): the half-made alias goes, nothing else changes
            deleteQuietly(alias)
            return false
        }
        val slot = try {
            wrapAndTest(lock.vaultId, slotId, alias, pin)
        } catch (e: RuntimeException) {
            null
        }
        if (slot == null || !wanted()) { // step 8: no record names the new key
            deleteQuietly(alias)
            return false
        }
        // Step 7. When this write fails the old record stays in memory, and the new key is left to the
        // sweep: the write may have replaced the record all the same (see the class).
        if (!lock.pin.setPin(slot, masterPasswordTyped)) return false
        core.publishSummary()
        return true
    }

    /**
     * F6 steps 5 and 6: `KEK_pin` = HMAC of `pin_msg` under the new key [alias], the wrap of the open
     * vault's VK for its `vk_id` with a fresh nonce, and the test: the HMAC computed again opens the
     * new wrap, and what it opens is VK. Null when any of it fails. Every array made here is wiped.
     */
    private suspend fun wrapAndTest(vaultId: String, slotId: String, alias: String, pin: ByteArray): ShortcutSlot? {
        val message = PinWrap.message(vaultId, slotId, pin)
        try {
            val kek = hmac(alias, message) ?: return null
            try {
                val nonce = core.random.bytes(PinWrap.NONCE_SIZE)
                return core.vault.withVaultKey { vkId, vk ->
                    if (!Uuid.isValid(vkId) || vk.size != VK_SIZE || nonce.size != PinWrap.NONCE_SIZE) return@withVaultKey null
                    val wrap = PinWrap.seal(core.aead, kek, nonce, vk, vaultId, vkId, slotId)
                    val again = hmac(alias, message) ?: return@withVaultKey null
                    val opened = try {
                        PinWrap.open(core.aead, again, wrap, vaultId, slotId)
                    } finally {
                        Bytes.wipe(again)
                    }
                    try {
                        if (opened != null && Bytes.equal(opened, vk)) ShortcutSlot(slotId, core.clock.wallMs(), listOf(wrap)) else null
                    } finally {
                        Bytes.wipe(opened)
                    }
                }
            } finally {
                Bytes.wipe(kek)
            }
        } finally {
            Bytes.wipe(message)
        }
    }

    /** `KEK_pin` from the Keystore (lock.md §4.3), or null when it gives no 32-byte result. */
    private fun hmac(alias: String, message: ByteArray): ByteArray? {
        val answer = try {
            core.pinKey.hmac(alias, message)
        } catch (e: RuntimeException) {
            return null
        }
        val kek = when (answer) {
            is Ks.Ok -> answer.value
            is Ks.Gone, is Ks.Temporary -> return null
        }
        if (kek.size != KEK_SIZE) {
            Bytes.wipe(kek)
            return null
        }
        return kek
    }

    // ---------------------------------------------------------------------------------------------
    // F9: fingerprint unlock on and off

    /** What [fingerprintOn] holds between its two turns under the mutex: the new key and a copy of VK. */
    private class TurnOn(
        val proof: MasterProof,
        val lock: VaultLock,
        val slotId: String,
        val alias: String,
        val vkId: String,
        val vk: ByteArray,
        val launch: Int,
    )

    /** [fingerprintOn]'s first turn: either what the prompt needs, or the outcome already. */
    private sealed interface Prepared {
        class Ready(val turnOn: TurnOn) : Prepared

        class Done(val outcome: FingerprintOnOutcome) : Prepared
    }

    /**
     * Takes the mutex itself. F9 "On", steps 2 to 9 (also F1 step 7, F15a step 3, F16b step 6):
     * [proof] as in [setPin]; the canary; a new key `pv_fp_<new id>`; `FingerprintKey.seal` of the
     * open vault's VK behind the prompt `PromptUse.TURN_ON`, under a launch number of its own, with
     * no mutex held while the prompt is open; then `FingerprintKeeper.setSlot` with the one wrap
     * (48 bytes, and `cipher.iv` as its nonce). A slot that was there is replaced; its key is left to
     * the sweep. There is no second touch to test the wrap: a wrap that does not match shows at its
     * first use and is removed then (section 14).
     *
     * `VaultPort.withVaultKey` lends VK to a lambda that cannot suspend, and the prompt suspends: a
     * copy of VK and its `vk_id` is taken out of it, sealed, and wiped in a `finally`.
     *
     * On any error or cancel the earlier state is kept and the new alias deleted (the one exception
     * is a failed record write; see the class): [FingerprintOnOutcome.NoneEnrolled] ("Add a
     * fingerprint in your phone's settings first."), [FingerprintOnOutcome.NoSecureHardware] (F18),
     * [FingerprintOnOutcome.Cancelled] (a closed prompt, codes 10, 13 and 5),
     * [FingerprintOnOutcome.NotShown] (the prompt could not be shown now, §9.2 rule 3, or another
     * prompt is open, rule 4), and [FingerprintOnOutcome.Failed] for everything else, a proof this
     * engine does not hold for the open vault among it. A result whose launch is no longer
     * outstanding (`LockCore.launchEnded` false: a lock or `ON_STOP` came while the prompt was open)
     * changes nothing: [FingerprintOnOutcome.Cancelled].
     */
    suspend fun fingerprintOn(proof: MasterProof): FingerprintOnOutcome {
        val turnOn = when (val prepared = core.locked { prepareTurnOn(proof) }) {
            is Prepared.Done -> return prepared.outcome
            is Prepared.Ready -> prepared.turnOn
        }
        val result = try {
            core.fingerprintKey.seal(turnOn.alias, turnOn.vk, PromptUse.TURN_ON, turnOn.launch)
        } catch (e: CancellationException) {
            // Its caller is gone: the launch ends (an open prompt is cancelled) and the new key goes.
            if (core.outstandingLaunch == turnOn.launch) core.endLaunchNow()
            withContext(NonCancellable) { core.locked { deleteQuietly(turnOn.alias) } }
            throw e
        } catch (e: RuntimeException) {
            FingerprintResult.Temporary(null) // lock.md §14.3: any other exception is TEMPORARY
        } finally {
            Bytes.wipe(turnOn.vk)
        }
        // The prompt has answered: its result is handled to its end, also when the caller is cancelled
        // while it waits for the mutex, so that the new key is either recorded or deleted.
        return withContext(NonCancellable) { core.locked { finishTurnOn(turnOn, result) } }
    }

    /** Holds the mutex: F9 steps 2, 4 and 5, up to the launch of the prompt. */
    private suspend fun prepareTurnOn(proof: MasterProof): Prepared {
        val open = openVault() ?: return Prepared.Done(FingerprintOnOutcome.Failed)
        if (!core.holds(proof) || proof.vaultId != open.vaultId) return Prepared.Done(FingerprintOnOutcome.Failed)
        if (core.outstandingLaunch != 0) return Prepared.Done(FingerprintOnOutcome.NotShown) // §9.2 rule 4: one prompt at a time
        val lock = core.lockOf(open.vaultId)
        if (lock.fingerprint.read == RecordRead.Newer) return Prepared.Done(FingerprintOnOutcome.Failed) // never replaced (§5.3)
        when (core.canHoldKeys()) { // step 4: the canary
            KeyCreate.Ok -> Unit
            KeyCreate.NoSecureHardware -> return Prepared.Done(FingerprintOnOutcome.NoSecureHardware)
            KeyCreate.NotNow, KeyCreate.NoneEnrolled -> return Prepared.Done(FingerprintOnOutcome.Failed)
        }
        val slotId = core.random.uuid()
        val alias = FingerprintKeeper.alias(slotId)
        val made = try {
            core.fingerprintKey.create(alias)
        } catch (e: RuntimeException) {
            KeyCreate.NotNow
        }
        val refused = when (made) {
            KeyCreate.Ok -> null
            KeyCreate.NoneEnrolled -> FingerprintOnOutcome.NoneEnrolled
            KeyCreate.NoSecureHardware -> FingerprintOnOutcome.NoSecureHardware
            KeyCreate.NotNow -> FingerprintOnOutcome.Failed
        }
        if (refused != null) {
            deleteQuietly(alias)
            return Prepared.Done(refused)
        }
        val key = try {
            core.vault.withVaultKey { vkId, vk -> vkId to vk.copyOf() }
        } catch (e: RuntimeException) {
            null
        }
        if (key == null || !Uuid.isValid(key.first) || key.second.size != VK_SIZE) {
            key?.second?.let { Bytes.wipe(it) }
            deleteQuietly(alias)
            return Prepared.Done(FingerprintOnOutcome.Failed)
        }
        return Prepared.Ready(TurnOn(proof, lock, slotId, alias, key.first, key.second, core.newLaunch()))
    }

    /** Holds the mutex: F9 steps 7 to 9, with what the prompt of [turnOn] came to. */
    private suspend fun finishTurnOn(turnOn: TurnOn, result: FingerprintResult): FingerprintOnOutcome {
        val sealed = result as? FingerprintResult.Ok
        try {
            // Dropped: no launch is outstanding (a lock or ON_STOP ended it), the proof of the flow is gone
            // with it, or the key that was sealed is no longer the open vault's.
            val current = core.launchEnded(turnOn.launch) && openVault()?.vaultId == turnOn.lock.vaultId &&
                core.holds(turnOn.proof) && openKeyIs(turnOn.vkId)
            if (!current) {
                deleteQuietly(turnOn.alias)
                return FingerprintOnOutcome.Cancelled
            }
            val outcome = when (result) {
                is FingerprintResult.Ok -> null
                is FingerprintResult.Closed -> FingerprintOnOutcome.Cancelled
                is FingerprintResult.Gone, is FingerprintResult.Temporary -> FingerprintOnOutcome.Failed
                FingerprintResult.NotShown -> FingerprintOnOutcome.NotShown
            }
            if (outcome != null || sealed == null) {
                deleteQuietly(turnOn.alias)
                return outcome ?: FingerprintOnOutcome.Failed
            }
            if (sealed.bytes.size != RecordCodec.CT_LENGTH || sealed.nonce.size != RecordCodec.NONCE_LENGTH ||
                turnOn.lock.fingerprint.read == RecordRead.Newer
            ) {
                deleteQuietly(turnOn.alias)
                return FingerprintOnOutcome.Failed
            }
            // Step 8: one wrap, for the key of the open vault. When the write fails the earlier record
            // stays in memory, and the new key is left to the sweep (see the class).
            val wrap = KeyWrap(turnOn.vkId, sealed.nonce.copyOf(), sealed.bytes.copyOf())
            if (!turnOn.lock.fingerprint.setSlot(ShortcutSlot(turnOn.slotId, core.clock.wallMs(), listOf(wrap)))) {
                return FingerprintOnOutcome.Failed
            }
            core.publishSummary()
            return FingerprintOnOutcome.On
        } finally {
            sealed?.let { Bytes.wipe(it.bytes) }
        }
    }

    /**
     * Holds the mutex. F9 "Off", in the open app and without proof (§8.2: making the lock stricter
     * needs nothing): `fingerprint.rec` is written with `slot = null` and `off = turned_off`, and the
     * alias is deleted at once (§5.7), also when that write fails, so that a finger can no longer open
     * the app whatever the record on disk says. Nothing else changes; the PIN is untouched (I6).
     */
    suspend fun fingerprintOff() {
        val open = openVault() ?: return
        core.lockOf(open.vaultId).fingerprint.removeSlot(FingerprintRecord.OffReason.TURNED_OFF)
        core.publishSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // F10: practice

    /**
     * Holds the mutex. F10 step 3, "Not now: use PIN" or "Not now: use fingerprint" on a practice
     * screen within its grace (a `LockScreen.MasterPassword` whose `practice.notNow` is not null;
     * `UnlockFlows.usePin` has checked that, `LockEngine.notNow` too, and it is checked again here
     * against the clock of now): `snoozed_until` = now + 20 hours in `state.rec`
     * (`PracticeSchedule.notNow`, best effort: a write that fails keeps the snooze in memory for this
     * process), then the lock screen is decided again, which shows the pad (or the fingerprint-only
     * screen), and the fingerprint prompt opens by itself as on any other visit (§8.3, §9.2 rule 7).
     *
     * Without a record that can hold the snooze (`state.rec` MISSING or DAMAGED, §5.3: "due now and
     * still skippable"), `LockVisit.practiceSkipped` skips practice for this visit, and the record
     * that the unlock of this visit makes by a shortcut (§5.3: `last_ok` one day ago, so practice
     * stays due) gets the snooze, so that the app still asks at most once a day (§8.3).
     *
     * A tap that comes when practice is no longer within its grace (the grace ended while the screen
     * was shown) snoozes nothing: the screen is decided again and asks for the master password.
     */
    suspend fun practiceNotNow() {
        val visit = core.visit?.takeIf { !it.place.hasEnded && !it.opening } ?: return
        if (visit.target.aside != null) return // practice is never asked on a vault put aside
        val screen = (core.state.value as? LockState.Locked)?.screen as? LockScreen.MasterPassword ?: return
        if (screen.practice?.notNow == null) return
        val lock = visit.target.lock
        if (lock.master.record != null) {
            snooze(lock)
        } else {
            visit.practiceSkipped = true
            val earlier = visit.afterUnlock
            visit.afterUnlock = { by ->
                earlier?.invoke(by)
                // A master password counts as practice and needs no snooze (§8.3).
                if (by != UnlockBy.MASTER_PASSWORD) snooze(lock)
            }
        }
        visit.mpError = null
        visit.masterPasswordChosen = false
        core.showLockScreen()
        core.publishSummary()
    }

    /** "Not now" in `state.rec` of [lock] (best effort), when practice is due within its grace now; nothing otherwise. */
    private fun snooze(lock: VaultLock) {
        val record = lock.master.record ?: return
        if (snoozed(record) == null) return
        lock.master.update { snoozed(it) ?: it }
    }

    /** [record] with practice snoozed for 20 hours from now (§8.3), or null when practice is not due within its grace now. */
    private fun snoozed(record: StateRecord): StateRecord? {
        val p = record.practice
        val schedule = PracticeSchedule(p.lastOkMs, p.step, p.snoozedUntilMs)
        val now = core.clock.wallMs()
        if (schedule.status(now, newPasswordPending = record.pendingMp != null) != PracticeSchedule.Status.DUE) return null
        val next = schedule.notNow(now)
        return record.copy(practice = StateRecord.Practice(next.lastOkMs, next.step, next.snoozedUntilMs))
    }

    // ---------------------------------------------------------------------------------------------

    /** Holds the mutex: the open vault, while the app is unlocked; null otherwise. */
    private fun openVault(): OpenVault? = core.open?.takeIf { core.state.value is LockState.Unlocked }

    /** Holds the mutex: whether the vault port's open vault is under the key [vkId] now. */
    private suspend fun openKeyIs(vkId: String): Boolean =
        try {
            core.vault.withVaultKey { openVkId, _ -> openVkId == vkId }
        } catch (e: RuntimeException) {
            false // no vault is open
        }

    /** Deletes [alias]; errors are ignored, and the sweep retries (lock.md §5.7). */
    private fun deleteQuietly(alias: String) {
        try {
            core.deviceKeys.delete(alias)
        } catch (e: RuntimeException) {
            // Ignored: no record names it, so the sweep deletes it at a later start.
        }
    }

    private companion object {
        /** The length of VK (lock.md §4.2). */
        const val VK_SIZE = 32

        /** The length of `KEK_pin`, an HMAC-SHA256 (lock.md §4.2). */
        const val KEK_SIZE = 32
    }
}
