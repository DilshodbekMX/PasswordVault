package io.github.dilshodbekmx.passwordvault.core.lock.flow

import io.github.dilshodbekmx.passwordvault.core.crypto.Aead
import io.github.dilshodbekmx.passwordvault.core.crypto.KdfUnavailable
import io.github.dilshodbekmx.passwordvault.core.crypto.MasterPasswordBytes
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpAttempt
import io.github.dilshodbekmx.passwordvault.core.lock.guard.MpCheck
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinGuard
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PinWrap
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Place
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy
import io.github.dilshodbekmx.passwordvault.core.lock.policy.KeystoreErrors
import io.github.dilshodbekmx.passwordvault.core.lock.policy.Ks
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.Bytes
import io.github.dilshodbekmx.passwordvault.core.util.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The wrap of the vault key under a pending new master password (lock.md §4.2, §4.3, F11 step 5):
 *
 * ```
 * KEK_pending = Argon2id(the constants of §4.2, the new master password's bytes, its own 16-byte salt)
 * AAD_pending = ASCII("pvlock-v1:pending-wrap") ‖ 0x00 ‖ uuid16(vault_id) ‖ uuid16(vk_id)                (55 bytes)
 * wrap        = AES-256-GCM(KEK_pending, 12-byte random nonce, VK, AAD_pending)                          (48 bytes)
 * ```
 *
 * The salt, the nonce, the wrap and the `vk_id` are `pending_mp` in `state.rec` (§5.6). The wrap's
 * tag is what proves, when the wait is over, that the bytes typed then are the password that was
 * chosen and typed twice (F11 step 14).
 */
object PendingWrap {
    /** The size of `AAD_pending`. */
    const val AAD_SIZE = 55

    private val LABEL = "pvlock-v1:pending-wrap".toByteArray(Charsets.US_ASCII)

    /** `AAD_pending` of the wrap of the vault key [vkId] of vault [vaultId]. */
    fun aad(vaultId: String, vkId: String): ByteArray {
        val out = ByteArray(AAD_SIZE)
        LABEL.copyInto(out, 0)
        Uuid.uuid16(vaultId).copyInto(out, LABEL.size + 1)
        Uuid.uuid16(vkId).copyInto(out, LABEL.size + 1 + 16)
        return out
    }

    /** The wrap of [vk] (32 bytes) under [kek] with [nonce] (12 bytes, fresh from `SecureRandom`). */
    fun seal(aead: Aead, kek: ByteArray, nonce: ByteArray, vk: ByteArray, vaultId: String, vkId: String): ByteArray {
        require(vk.size == 32) { "VK has 32 bytes" }
        require(nonce.size == RecordCodec.NONCE_LENGTH) { "a wrap's nonce has 12 bytes" }
        return aead.seal(kek, nonce, vk, aad(vaultId, vkId))
    }

    /** VK from the wrap [ct] made with [nonce], or null when its tag fails under [kek]. */
    fun open(aead: Aead, kek: ByteArray, nonce: ByteArray, ct: ByteArray, vaultId: String, vkId: String): ByteArray? =
        try {
            aead.open(kek, nonce, ct, aad(vaultId, vkId))
        } catch (e: IllegalArgumentException) {
            null // a nonce or key of the wrong size: this wrap can never open
        }
}

/**
 * The flows that change the master password (lock.md §12, build plan 4.4): **F8** change it, **F11**
 * replace a forgotten one. `LockEngine` sends its entry points here, and `UnlockFlows` the pending
 * wrap at a master-password entry ([typedPending]).
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
 *   (`MasterPasswordGuard`, F11 step 9). `EntryPolicy` asks for no practice while one is pending,
 *   and offers a vault marked `replaced` and put aside nothing but its master password (F11 step 18).
 *
 * **Time on disk before a storage step** ([SETTLED_MS]). Two writes of `state.rec` must be on the
 * flash before storage does what depends on them: the `mp_change` mark of F8 step 5 (with the PIN's
 * wrap for the new key), before the new generation is written, and the `replaced` mark of F11 step
 * 15, before the old vault is put aside. A write that `fsync` handed over may not be there yet
 * (§2.3, §6.7, [V-S8]), and a power cut must not keep the storage step and lose the mark: an old
 * vault put aside without its mark would keep the PIN of whoever replaced the master password. So
 * each flow waits until its writes are as old as a charge must be before its PIN is checked (§6.2),
 * as "Delete" does in `VaultFlows`.
 */
internal class PasswordFlows(private val core: LockCore) {

    // ---------------------------------------------------------------------------------------------
    // F8: change the master password

    /** What the first turn of [changeMasterPassword] left for step 7: the vault, its two keys, and whether the PIN was kept. */
    private class Changed(val lock: VaultLock, val vkId1: String, val vkId2: String, val pinKept: Boolean)

    /** The first turn of [changeMasterPassword]: the change, or the outcome already. */
    private sealed interface ChangeTurn {
        class Done(val changed: Changed) : ChangeTurn

        class Ended(val outcome: ChangeOutcome) : ChangeTurn
    }

    /** What [keepFingerprint] holds between its two turns under the mutex: the slot's key and a copy of the new VK. */
    private class Keep(val slotId: String, val alias: String, val vk: ByteArray, val launch: Int)

    /**
     * Takes the mutex itself. F8 steps 2 to 9, with [proof] the current master password of this
     * flow (step 1), [first] and [again] the two typings of the new one (compared before anything is
     * derived), and [pin] the PIN typed at step 3 to keep it, or null for "Not now". The order of
     * steps 5 to 7 is decision 20: `mp_change`, the PIN's wrap for the new key, the vault, the
     * fingerprint.
     *
     * - **Step 3.** With a PIN slot that holds a wrap for the vault's current key, [pin] is a counted
     *   check in a prompt (§6.3). Wrong: [ChangeOutcome.PinWrong], and nothing has changed (the same
     *   wrong PIN again is not counted again, and answers the same). A limit turns the PIN off (§6.5)
     *   and the flow goes on without it; so it does when the PIN cannot be checked right now, as after
     *   "Not now". The right PIN yields `KEK_pin`, which is kept until step 5 and then zeroed.
     * - **Steps 4 and 5.** VK2 and `vk_id2` from storage; `state.rec` with `mp_change`, which must be
     *   on disk before the vault is written (it is what lets the old master password reach "Open my
     *   passwords from before the change", F16b), then `pin.rec` with the wrap of VK2 under the same
     *   `KEK_pin`, tested first, and every wrap but the current key's dropped (§5.4). Both writes are
     *   [SETTLED_MS] old before step 6 (see the class).
     * - **Step 6.** `VaultPort.changePassword`: written, read back, proven with the second typing.
     *   Nothing in place, or quarantined: `mp_change = null`, [ChangeOutcome.NothingChanged]. In
     *   place and perhaps not durable: `mp_change` stays set, [ChangeOutcome.Unclear]; the session
     *   goes on under whichever key the vault port holds now, without any proof, or the app is locked
     *   when the port has ended its session.
     * - **Step 8** follows step 6 at once, before the fingerprint prompt, which can stay open for as
     *   long as the user likes: the change is proven, so `mp_change = null` (§5.6: "set exactly while
     *   the new copy is unproven", T15), practice step 0 and `last_ok` now, the counters and the PIN
     *   guard at zero. Every proof made before goes, and `LockState.Unlocked` holds the proof of the
     *   new master password, for "Choose a PIN" (step 3) and "Back up now" (step 9).
     * - **Step 7**, if a fingerprint slot exists and fingerprint can be used now (§13.1: a slot that
     *   cannot be used now is left alone): the prompt "Keep fingerprint unlock" on the existing key,
     *   with no mutex held; then `fingerprint.rec` keeps the wrap for the key before, drops any other
     *   and gains the wrap of VK2. "Not now", a closed prompt or a TEMPORARY error: the slot keeps
     *   only its old wrap. GONE: the slot is removed, as in F15a or F15b.
     *
     * A lock or `ON_STOP` before step 6 discards the flow, and nothing was changed (§10.1). From
     * step 6 on the change runs to its end, as a save does.
     *
     * @throws IllegalArgumentException when [pin] is given and is not six ASCII digits.
     */
    suspend fun changeMasterPassword(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?): ChangeOutcome {
        require(pin == null || PinWrap.isPin(pin)) { "a PIN is six ASCII digits" }
        if (!MasterPasswordBytes.same(first, again)) return ChangeOutcome.Mismatch
        MasterPasswordBytes.firstUnassigned(first)?.let { return ChangeOutcome.Unassigned(it) }
        // The whole change belongs to the app as it is open now: a lock or ON_STOP ends this prompt.
        val at = core.openPrompt()
        try {
            val changed = when (val turn = core.locked { changeLocked(proof, first, again, pin, at) }) {
                is ChangeTurn.Ended -> return turn.outcome
                is ChangeTurn.Done -> turn.changed
            }
            return ChangeOutcome.Changed(changed.pinKept, keepFingerprint(changed, at))
        } finally {
            core.closePrompt(at)
        }
    }

    /** Holds the mutex: F8 steps 3 to 6 and 8. */
    private suspend fun changeLocked(proof: MasterProof, first: CharArray, again: CharArray, pin: ByteArray?, at: Place): ChangeTurn {
        val nothing = ChangeTurn.Ended(ChangeOutcome.NothingChanged)
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || at.hasEnded) return nothing
        if (!core.holds(proof) || proof.vaultId != open.vaultId) return nothing // step 1: the current one, typed in this flow
        val lock = core.lockOf(open.vaultId)
        val vkId1 = openKeyId() ?: return nothing
        if (lock.master.record == null) return nothing // `state.rec` cannot hold the mark of step 5 (§5.3 NEWER, UNREADABLE)

        var kek: ByteArray? = null
        var vk2: ByteArray? = null
        val firstBytes = MasterPasswordBytes.of(first)
        val againBytes = MasterPasswordBytes.of(again)
        try {
            // Step 3.
            if (pin != null && pinFor(lock, vkId1) == PinNow.EXISTS) {
                when (val checked = core.unlock.pinAt(pin, at) { kek = it }) {
                    is PinOutcome.Right -> {
                        val yields = yieldsOpenKey(checked.keys, vkId1)
                        checked.keys.values.forEach { Bytes.wipe(it) }
                        if (!yields) {
                            Bytes.wipe(kek)
                            kek = null
                        }
                    }
                    is PinOutcome.Wrong -> return ChangeTurn.Ended(ChangeOutcome.PinWrong(checked.left))
                    PinOutcome.SameAsBefore -> return ChangeTurn.Ended(ChangeOutcome.PinWrong(lock.pin.triesLeft))
                    PinOutcome.Limit, PinOutcome.CannotCheck, PinOutcome.KeyGone, PinOutcome.NotCharged -> Unit // on, without the PIN
                    PinOutcome.TooLate -> return nothing // right, and counted so; the flow has ended meanwhile (I11)
                }
            }
            if (at.hasEnded || !core.holds(proof)) return nothing

            // Step 4.
            val (vkId2, made) = core.vault.newKey()
            vk2 = made
            if (!Uuid.isValid(vkId2) || vkId2 == vkId1 || made.size != VK_SIZE) return nothing

            // Step 5: the mark first, then the PIN's wrap for the new key.
            if (!lock.master.update(bestEffort = false) { it.copy(mpChange = StateRecord.MpChange(vkId2, core.clock.wallMs())) }) return nothing
            val pinKept = kek?.let { keepPin(lock, it, vkId1, vkId2, made) } == true
            Bytes.wipe(kek)
            kek = null
            core.clock.delayUntilElapsed(core.clock.elapsedMs() + SETTLED_MS)
            if (at.hasEnded || !core.holds(proof)) { // a lock or ON_STOP meanwhile: nothing of the vault was changed
                lock.master.update { it.copy(mpChange = null) }
                return nothing
            }

            // Step 6.
            val write = try {
                core.vault.changePassword(firstBytes, againBytes, vkId2, made)
            } catch (e: KdfUnavailable) {
                lock.master.update { it.copy(mpChange = null) }
                return ChangeTurn.Ended(ChangeOutcome.NoMemory)
            }
            when (write) {
                VaultWrite.NothingChanged, VaultWrite.Quarantined -> {
                    lock.master.update { it.copy(mpChange = null) }
                    core.rescan()
                    core.publishSummary()
                    return nothing
                }
                VaultWrite.Unclear -> {
                    unclearChange(open)
                    return ChangeTurn.Ended(ChangeOutcome.Unclear)
                }
                is VaultWrite.Done -> {
                    // Step 8: the new master password has just been typed twice and has opened the
                    // new copy from disk, so it brings what every correct master password brings
                    // (§6.8 step 5), with practice at step 0 (§8.3).
                    if (lock.master.counts.failures != 0) lock.master.attempt(at) { MpCheck.RIGHT }
                    val practice = PracticeSchedule.newMasterPassword(core.clock.wallMs())
                    lock.master.update {
                        it.copy(practice = StateRecord.Practice(practice.lastOkMs, practice.step, practice.snoozedUntilMs), mpChange = null)
                    }
                    lock.pin.masterPasswordCorrect()
                    core.dropProofs() // every proof made before holds the old master password
                    core.open = OpenVault(write.vaultId, write.generation, write.vkId, write.createdMs, olderCopy = false)
                    core.rescan()
                    (core.state.value as? LockState.Unlocked)?.let { now ->
                        core.state.value = now.copy(proof = if (at.hasEnded) null else core.newProof(againBytes, write.vaultId))
                    }
                    core.publishSummary()
                    return ChangeTurn.Done(Changed(lock, vkId1, vkId2, pinKept))
                }
            }
        } finally {
            Bytes.wipe(kek, vk2, firstBytes, againBytes)
        }
    }

    /**
     * F8 step 5 for `pin.rec`: the wrap of VK2 for `vk_id2` under the `KEK_pin` that the right PIN
     * gave, with a fresh nonce, tested as F6 step 6 tests a new wrap (it opens under the same key and
     * gives VK2), then written with every wrap but the current key's dropped (§5.4). Best effort, as
     * `PinGuard.changeWraps`: true when the slot holds the new wrap in the record the lock means to
     * have, which the next write stores if this one failed.
     */
    private fun keepPin(lock: VaultLock, kek: ByteArray, vkId1: String, vkId2: String, vk2: ByteArray): Boolean {
        val slot = lock.pin.record?.slot ?: return false
        val wrap = try {
            PinWrap.seal(core.aead, kek, core.random.bytes(PinWrap.NONCE_SIZE), vk2, lock.vaultId, vkId2, slot.id)
        } catch (e: RuntimeException) {
            return false
        }
        val back = PinWrap.open(core.aead, kek, wrap, lock.vaultId, slot.id)
        val good = back != null && Bytes.equal(back, vk2)
        Bytes.wipe(back)
        if (!good) return false
        lock.pin.changeWraps(vkId1, wrap)
        return lock.pin.record?.slot?.wraps?.any { it.vkId == vkId2 } == true
    }

    /**
     * Holds the mutex. F8 step 6, "Saving may not have finished": the new copy is in place and
     * perhaps not durable, so `mp_change` stays set. No proof stands: which master password the
     * vault has is not known. The session goes on under the key the vault port holds now (the real
     * port opens the new copy again, so that later saves do not bury it under the old key); when the
     * port has ended its session instead, the app is locked.
     */
    private suspend fun unclearChange(open: OpenVault) {
        core.dropProofs()
        core.rescan()
        val vkId = openKeyId()
        if (vkId == null) {
            core.unlock.lockFromInside()
            return
        }
        core.open = OpenVault(open.vaultId, open.generation, vkId, open.createdMs, open.olderCopy)
        (core.state.value as? LockState.Unlocked)?.let { now -> core.state.value = now.copy(proof = null) }
        core.publishSummary()
    }

    /**
     * Takes the mutex itself, twice, and holds none while the prompt is open (architecture.md §5.2).
     * F8 step 7. True when the fingerprint slot holds the wrap of the new key.
     *
     * A caller that is cancelled while the first turn runs never gets what that turn made
     * (architecture.md §3.4): the copy of VK2 is wiped and the launch ended here. A prompt that has
     * answered is handled to its end, also when the caller is cancelled meanwhile.
     */
    private suspend fun keepFingerprint(changed: Changed, at: Place): Boolean {
        var made: Keep? = null
        val keep = try {
            core.locked { prepareKeep(changed, at).also { made = it } }
        } catch (e: CancellationException) {
            made?.let { dropped ->
                Bytes.wipe(dropped.vk)
                withContext(NonCancellable) { core.locked { core.launchEnded(dropped.launch) } }
            }
            throw e
        } ?: return false
        val result = try {
            core.fingerprintKey.seal(keep.alias, keep.vk, PromptUse.KEEP_AFTER_CHANGE, keep.launch)
        } catch (e: CancellationException) {
            if (core.outstandingLaunch == keep.launch) core.endLaunchNow()
            throw e
        } catch (e: RuntimeException) {
            FingerprintResult.Temporary(null) // lock.md §14.3: any other exception is TEMPORARY
        } finally {
            Bytes.wipe(keep.vk)
        }
        return withContext(NonCancellable) { core.locked { finishKeep(changed, keep, result) } }
    }

    /** Holds the mutex: what the prompt of F8 step 7 needs, or null when there is none to show. */
    private suspend fun prepareKeep(changed: Changed, at: Place): Keep? {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || open.vaultId != changed.lock.vaultId || at.hasEnded) return null
        if (core.outstandingLaunch != 0) return null // §9.2 rule 4: one prompt at a time
        val slot = changed.lock.fingerprint.slot ?: return null // "Fingerprint, if a slot exists"
        // §13.1: while the phone says that fingerprint cannot be used, the key is not touched and the
        // slot is left alone; Settings then shows "Fingerprint unlock: turn on again".
        if (!KeystoreErrors.mayProbe(core.askAvailability())) return null
        val vk = try {
            core.vault.withVaultKey { vkId, vk -> if (vkId == changed.vkId2 && vk.size == VK_SIZE) vk.copyOf() else null }
        } catch (e: RuntimeException) {
            null
        } ?: return null
        return Keep(slot.id, FingerprintKeeper.alias(slot.id), vk, core.newLaunch())
    }

    /** Holds the mutex: F8 step 7 with what the prompt came to. */
    private suspend fun finishKeep(changed: Changed, keep: Keep, result: FingerprintResult): Boolean {
        val sealed = result as? FingerprintResult.Ok
        try {
            // Dropped: no launch is outstanding (a lock or ON_STOP ended it), or the slot or the key
            // that was sealed is no longer the one of the open vault.
            val keeper = changed.lock.fingerprint
            val current = core.launchEnded(keep.launch) && core.state.value is LockState.Unlocked &&
                core.open?.vaultId == changed.lock.vaultId && keeper.slot?.id == keep.slotId && openKeyId() == changed.vkId2
            if (!current) return false
            when (result) {
                is FingerprintResult.Ok -> {
                    if (result.bytes.size != RecordCodec.CT_LENGTH || result.nonce.size != RecordCodec.NONCE_LENGTH) return false
                    keeper.changeWraps(changed.vkId1, KeyWrap(changed.vkId2, result.nonce.copyOf(), result.bytes.copyOf()))
                }
                is FingerprintResult.Gone -> { // the key was invalidated or is missing: F15a, F15b
                    keeper.removeSlot(
                        if (result.why == KeystoreErrors.GONE_BAD_WRAP) FingerprintRecord.OffReason.BAD_WRAP else FingerprintRecord.OffReason.KEY_GONE,
                    )
                }
                is FingerprintResult.Closed, is FingerprintResult.Temporary, FingerprintResult.NotShown -> Unit // the slot keeps only its old wrap
            }
            core.publishSummary()
            return keeper.wrapFor(changed.vkId2) != null
        } finally {
            sealed?.let { Bytes.wipe(it.bytes) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F11, asking: "Proof now" (step 3)

    /**
     * The proof of F11 step 3, in memory only, like a `MasterProof` (§5.8): made by the PIN typed now
     * (or by the fingerprint when no PIN exists), handed to [requestNewMasterPassword], and dropped
     * when that request is made, at a lock and when the app stops being visible. [place] is a prompt
     * of the open app, which a lock or `ON_STOP` ends at once, without the mutex; [dropProof] follows
     * under it.
     */
    private class ReplacementProof(val place: Place, val by: Shortcut, val vaultId: String)

    private var proof: ReplacementProof? = null

    /** The visit on which "I can't remember it" was chosen last: its hook is chained once. */
    private var askedOn: LockVisit? = null

    /** Whether F11 step 3 finds a PIN for the vault's current key. */
    private enum class PinNow {
        /** `pin.rec` holds a slot with a wrap for the vault's current key: the proof is the PIN typed now. */
        EXISTS,

        /** `pin.rec` cannot be read at the moment (UNREADABLE): the proof is the PIN, and it cannot be checked. */
        CANNOT_BE_READ,

        /** MISSING, DAMAGED or NEWER, no slot, or a slot without a wrap for the current key: a fingerprint is accepted. */
        NONE,
    }

    /** Holds the mutex. "The PIN, whenever there is one" (F11 step 3), for the vault of [lock] under the key [vkId]. */
    private fun pinFor(lock: VaultLock, vkId: String): PinNow {
        lock.startPin()
        return when (val read = lock.pinRead) {
            RecordRead.Unreadable -> PinNow.CANNOT_BE_READ
            RecordRead.Missing, is RecordRead.Damaged, RecordRead.Newer -> PinNow.NONE
            is RecordRead.Ok -> if (read.record.slot?.wraps?.any { it.vkId == vkId } == true) PinNow.EXISTS else PinNow.NONE
        }
    }

    /** Holds the mutex: the proof that stands for the vault [vaultId], or null; one that has ended is dropped. */
    private fun heldProof(vaultId: String): ReplacementProof? {
        val held = proof ?: return null
        if (held.place.hasEnded || held.vaultId != vaultId) {
            dropProof()
            return null
        }
        return held
    }

    /**
     * Holds the mutex: the proof of F11 step 3 is gone (§5.8): its request was made, or the app
     * locked or stopped being visible. `LockEngine.lock` and `appStopped` call it under the mutex,
     * after they have ended every prompt at once, so that a proof made by a step that held the mutex
     * meanwhile goes too.
     */
    fun dropProof() {
        proof?.let { core.closePrompt(it.place) }
        proof = null
    }

    /**
     * Holds the mutex. "I can't remember it" on a practice screen (F10 step 4): F11 from the lock
     * screen. Practice is skipped for this visit (`LockVisit.practiceSkipped`), so that the PIN, or
     * the fingerprint when no PIN exists, opens the app as an ordinary entry of the visit (F11 step
     * 3); `LockVisit.afterUnlock` says how it was opened, and that entry is the proof of step 3 when
     * it is the one that step accepts: the PIN whenever there is one, the fingerprint only when
     * there is none. A fingerprint that opens the app while a PIN exists opens it and proves nothing;
     * [proveNow] then asks for the PIN in the open app.
     *
     * The first option of the "Forgot master password?" sheet, "Use my PIN" or "Use my fingerprint"
     * (F11 step 1, F17 step 1), arrives here too: the screens send the same intent
     * (`LockIntent.CantRemember`) from any master-password screen of the current vault, a practice
     * screen or not.
     *
     * Nothing changes on the lock screen of a vault put aside (it offers no replacement), while
     * another question is shown, or while neither shortcut can open the app ("Neither: the route is
     * not offered"). The standing banner of §13.4 item 6 does the rest: after such an unlock practice
     * is still overdue, and the summary says so until a master password has been typed or a new one
     * chosen (F10 step 4).
     */
    suspend fun cantRemember() {
        if (core.state.value !is LockState.Locked) return
        val visit = core.visit?.takeIf { !it.place.hasEnded && !it.opening } ?: return
        val target = visit.target
        if (target.aside != null || visit.olderPassword != null || visit.olderSet != null) return
        val decision = core.decide(target, visit) as? EntryPolicy.Decision.Locked ?: return
        if (!decision.pinUsable && !decision.fingerprintUsable && !decision.fingerprintWaitsForAnswer) return
        visit.practiceSkipped = true
        visit.masterPasswordChosen = false
        visit.mpError = null
        if (askedOn !== visit) {
            askedOn = visit
            val lock = target.lock
            val earlier = visit.afterUnlock
            visit.afterUnlock = { by ->
                earlier?.invoke(by)
                provenByUnlock(lock, by)
            }
        }
        core.showLockScreen()
        core.publishSummary()
    }

    /** Holds the mutex: the visit on which "I can't remember it" was chosen has just opened the app, by [by]. */
    private fun provenByUnlock(lock: VaultLock, by: UnlockBy) {
        askedOn = null
        dropProof()
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || open.vaultId != lock.vaultId) return
        val pin = pinFor(lock, open.vkId)
        val shortcut = when (by) {
            UnlockBy.PIN -> if (pin == PinNow.EXISTS) Shortcut.PIN else return
            UnlockBy.FINGERPRINT -> if (pin == PinNow.NONE) Shortcut.FINGERPRINT else return // never in place of a PIN
            UnlockBy.MASTER_PASSWORD -> return // nothing is forgotten
        }
        proof = ReplacementProof(core.openPrompt(), shortcut, lock.vaultId)
    }

    /** What the first turn of [proveNow] came to: the outcome, or the fingerprint prompt to show. */
    private sealed interface ProveTurn {
        class Done(val outcome: ProofOutcome) : ProveTurn

        class Confirm(val lock: VaultLock, val slotId: String, val alias: String, val wrap: KeyWrap, val launch: Int) : ProveTurn
    }

    /**
     * Takes the mutex itself. F11 step 3 in the open app, "Proof now. An app that is already open is
     * not enough."
     *
     * - A proof that stands already (the PIN, or the fingerprint where no PIN exists, opened the app
     *   after "I can't remember it"): [ProofOutcome.Proven], and nothing is asked again.
     * - **The PIN, whenever there is one**: `pin.rec` holds a slot with a wrap for the vault's
     *   current key. Without [pin]: [ProofOutcome.NeedsPin]. With it: a counted check in a prompt
     *   (§6.3) that must yield the open vault's key. Wrong is counted as always; a limit turns the
     *   PIN off (§6.5), and the slot is then gone ([ProofOutcome.PinGone]): the next call, without a
     *   PIN, goes on with the fingerprint if it is usable. A PIN that cannot be checked right now
     *   (TEMPORARY, storage full, a record that cannot be read) stops the flow
     *   ([ProofOutcome.CannotCheck]); it never falls back to the fingerprint.
     * - **A fingerprint, only when there is no PIN** and fingerprint is usable: the probe of F3 step
     *   2, then the prompt "Confirm with your fingerprint", with no mutex held. Only 32 bytes that
     *   are the open vault's key count (I7); a callback alone proves nothing.
     * - Neither: [ProofOutcome.NotOffered].
     *
     * @throws IllegalArgumentException when [pin] is given and is not six ASCII digits.
     */
    suspend fun proveNow(pin: ByteArray?): ProofOutcome {
        require(pin == null || PinWrap.isPin(pin)) { "a PIN is six ASCII digits" }
        val at = core.openPrompt()
        try {
            var made: ProveTurn? = null
            val turn = try {
                core.locked { proveLocked(pin, at).also { made = it } }
            } catch (e: CancellationException) {
                (made as? ProveTurn.Confirm)?.let { dropped -> withContext(NonCancellable) { core.locked { core.launchEnded(dropped.launch) } } }
                throw e
            }
            val confirm = when (turn) {
                is ProveTurn.Done -> return turn.outcome
                is ProveTurn.Confirm -> turn
            }
            val result = try {
                core.fingerprintKey.open(confirm.alias, confirm.wrap.nonce, confirm.wrap.ct, PromptUse.CONFIRM_REPLACEMENT, confirm.launch)
            } catch (e: CancellationException) {
                if (core.outstandingLaunch == confirm.launch) core.endLaunchNow()
                throw e
            } catch (e: RuntimeException) {
                FingerprintResult.Temporary(null) // lock.md §14.3: any other exception is TEMPORARY
            }
            return withContext(NonCancellable) { core.locked { confirmed(confirm, result, at) } }
        } finally {
            core.closePrompt(at)
        }
    }

    /** Holds the mutex: step 3 up to the PIN's result, or up to the launch of the fingerprint prompt. */
    private suspend fun proveLocked(pin: ByteArray?, at: Place): ProveTurn {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null) return ProveTurn.Done(ProofOutcome.NotOffered)
        if (at.hasEnded) return ProveTurn.Done(ProofOutcome.Cancelled)
        val lock = core.lockOf(open.vaultId)
        val vkId = openKeyId() ?: return ProveTurn.Done(ProofOutcome.NotOffered)
        heldProof(lock.vaultId)?.let { held ->
            // It still is the proof this step accepts: never a fingerprint once a PIN exists.
            if (held.by == Shortcut.PIN || pinFor(lock, vkId) == PinNow.NONE) return ProveTurn.Done(ProofOutcome.Proven(held.by))
            dropProof()
        }
        return when (pinFor(lock, vkId)) {
            PinNow.CANNOT_BE_READ -> ProveTurn.Done(ProofOutcome.CannotCheck)
            PinNow.EXISTS -> ProveTurn.Done(if (pin == null) ProofOutcome.NeedsPin else provePin(lock, vkId, pin, at))
            PinNow.NONE -> if (pin != null) ProveTurn.Done(ProofOutcome.PinGone) else prepareConfirm(lock, vkId)
        }
    }

    /** Holds the mutex: the PIN typed now, a counted check in the prompt [at]. */
    private suspend fun provePin(lock: VaultLock, vkId: String, pin: ByteArray, at: Place): ProofOutcome =
        when (val checked = core.unlock.pinAt(pin, at)) {
            is PinOutcome.Right -> {
                val yields = yieldsOpenKey(checked.keys, vkId)
                checked.keys.values.forEach { Bytes.wipe(it) }
                when {
                    !yields -> ProofOutcome.CannotCheck
                    at.hasEnded -> ProofOutcome.Cancelled
                    else -> {
                        dropProof()
                        proof = ReplacementProof(core.openPrompt(), Shortcut.PIN, lock.vaultId)
                        ProofOutcome.Proven(Shortcut.PIN)
                    }
                }
            }
            is PinOutcome.Wrong -> ProofOutcome.PinWrong(checked.left)
            PinOutcome.SameAsBefore -> ProofOutcome.SamePinAgain
            PinOutcome.Limit, PinOutcome.KeyGone -> ProofOutcome.PinGone
            PinOutcome.CannotCheck, PinOutcome.NotCharged -> ProofOutcome.CannotCheck
            PinOutcome.TooLate -> ProofOutcome.Cancelled // right, and counted so; its prompt has ended (I11)
        }

    /** Holds the mutex: F3 step 2 for the fingerprint as the proof, where no PIN exists. */
    private fun prepareConfirm(lock: VaultLock, vkId: String): ProveTurn {
        val slot = lock.fingerprint.slot ?: return ProveTurn.Done(ProofOutcome.NotOffered)
        val wrap = slot.wraps.firstOrNull { it.vkId == vkId } ?: return ProveTurn.Done(ProofOutcome.NotOffered)
        if (core.outstandingLaunch != 0) return ProveTurn.Done(ProofOutcome.NotShown) // §9.2 rule 4
        if (!KeystoreErrors.mayProbe(core.askAvailability())) return ProveTurn.Done(ProofOutcome.NotOffered) // fingerprint is not usable now
        val alias = FingerprintKeeper.alias(slot.id)
        val probe = try {
            core.fingerprintKey.probe(alias, wrap.nonce)
        } catch (e: RuntimeException) {
            Ks.Temporary(e)
        }
        return when (probe) {
            is Ks.Ok -> ProveTurn.Confirm(lock, slot.id, alias, wrap, core.newLaunch())
            is Ks.Gone -> {
                fingerprintGone(lock, probe.why)
                ProveTurn.Done(ProofOutcome.NotOffered)
            }
            is Ks.Temporary -> ProveTurn.Done(ProofOutcome.FingerprintFailed(FingerprintLine.CannotCheck))
        }
    }

    /** Holds the mutex: what the prompt "Confirm with your fingerprint" came to. */
    private suspend fun confirmed(confirm: ProveTurn.Confirm, result: FingerprintResult, at: Place): ProofOutcome {
        val opened = result as? FingerprintResult.Ok
        try {
            val lock = confirm.lock
            val vkId = openKeyId()
            val current = core.launchEnded(confirm.launch) && !at.hasEnded && core.state.value is LockState.Unlocked &&
                core.open?.vaultId == lock.vaultId && lock.fingerprint.slot?.id == confirm.slotId && vkId == confirm.wrap.vkId
            if (!current || vkId == null) return ProofOutcome.Cancelled
            return when (result) {
                is FingerprintResult.Ok -> when {
                    result.bytes.size != VK_SIZE -> { // §14.3: a result that is not 32 bytes
                        fingerprintGone(lock, KeystoreErrors.GONE_BAD_WRAP)
                        ProofOutcome.NotOffered
                    }
                    !yieldsOpenKey(mapOf(vkId to result.bytes), vkId) -> ProofOutcome.FingerprintFailed(FingerprintLine.DidNotOpen)
                    pinFor(lock, vkId) != PinNow.NONE -> ProofOutcome.NeedsPin // a PIN was set while the prompt was open
                    else -> {
                        dropProof()
                        proof = ReplacementProof(core.openPrompt(), Shortcut.FINGERPRINT, lock.vaultId)
                        ProofOutcome.Proven(Shortcut.FINGERPRINT)
                    }
                }
                is FingerprintResult.Closed -> ProofOutcome.Cancelled
                is FingerprintResult.Gone -> {
                    fingerprintGone(lock, result.why)
                    ProofOutcome.NotOffered
                }
                is FingerprintResult.Temporary -> ProofOutcome.FingerprintFailed(KeystoreErrors.temporaryLine(result.code))
                FingerprintResult.NotShown -> ProofOutcome.NotShown
            }
        } finally {
            opened?.let { Bytes.wipe(it.bytes) }
        }
    }

    /** GONE (lock.md §14.1) for the fingerprint of the open vault: that one slot is removed and its alias deleted (F15a, F15b). */
    private fun fingerprintGone(lock: VaultLock, why: String) {
        lock.fingerprint.removeSlot(
            if (why == KeystoreErrors.GONE_BAD_WRAP) FingerprintRecord.OffReason.BAD_WRAP else FingerprintRecord.OffReason.KEY_GONE,
        )
        core.publishSummary()
    }

    // ---------------------------------------------------------------------------------------------
    // F11, asking: the request (steps 4 and 5), and cancelling it (step 8)

    /**
     * Takes the mutex itself. F11 step 5, with the proof of step 3 that this engine holds: the PIN
     * typed now, on the lock screen after [cantRemember] or in [proveNow]; a fingerprint only when
     * there is no PIN. Without one: [RequestOutcome.NotAllowed] (T16). [first] and [again] are the
     * two typings of step 4, compared before anything is derived.
     *
     * `KEK_pending` is derived with a new salt, the open vault's VK is wrapped with a fresh nonce
     * ([PendingWrap]), the wrap is opened once more and compared with VK, and `state.rec` is written
     * with `pending_mp` and no credit. That write must reach the disk: otherwise nothing is pending
     * ([RequestOutcome.NotSaved]). An earlier request is replaced, and its wait starts again. The
     * proof is used up. A lock or `ON_STOP` while Argon2id runs drops the proof at once, and then
     * nothing is written ([RequestOutcome.Cancelled]).
     */
    suspend fun requestNewMasterPassword(first: CharArray, again: CharArray): RequestOutcome {
        if (!MasterPasswordBytes.same(first, again)) return RequestOutcome.Mismatch
        MasterPasswordBytes.firstUnassigned(first)?.let { return RequestOutcome.Unassigned(it) }
        return core.locked { requestLocked(first) }
    }

    private suspend fun requestLocked(first: CharArray): RequestOutcome {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null) return RequestOutcome.NotAllowed
        val lock = core.lockOf(open.vaultId)
        val given = heldProof(lock.vaultId) ?: return RequestOutcome.NotAllowed
        val vkIdNow = openKeyId() ?: return RequestOutcome.NotAllowed
        if (given.by == Shortcut.FINGERPRINT && pinFor(lock, vkIdNow) != PinNow.NONE) { // never a fingerprint while a PIN exists
            dropProof()
            return RequestOutcome.NotAllowed
        }
        if (lock.master.record == null) return RequestOutcome.NotSaved // `state.rec` cannot hold the request (§5.3)

        val bytes = MasterPasswordBytes.of(first)
        var kek: ByteArray? = null
        try {
            val salt = core.random.bytes(RecordCodec.SALT_LENGTH)
            val nonce = core.random.bytes(RecordCodec.NONCE_LENGTH)
            val derived = try {
                core.kdf.kek(bytes, salt)
            } catch (e: KdfUnavailable) {
                return RequestOutcome.NoMemory
            }
            kek = derived
            val wrapped = try {
                core.vault.withVaultKey { vkId, vk ->
                    if (!Uuid.isValid(vkId) || vk.size != VK_SIZE || derived.size != VK_SIZE) return@withVaultKey null
                    val ct = PendingWrap.seal(core.aead, derived, nonce, vk, lock.vaultId, vkId)
                    val back = PendingWrap.open(core.aead, derived, nonce, ct, lock.vaultId, vkId)
                    val good = ct.size == RecordCodec.CT_LENGTH && back != null && Bytes.equal(back, vk)
                    Bytes.wipe(back)
                    if (good) vkId to ct else null
                }
            } catch (e: RuntimeException) {
                null
            } ?: return RequestOutcome.NotSaved
            // A lock or ON_STOP dropped the proof at once, without the mutex (§5.8): asked again before the write.
            if (given.place.hasEnded || proof !== given) return RequestOutcome.Cancelled
            val pending = StateRecord.PendingMp(
                requestedMs = core.clock.wallMs(),
                by = given.by,
                creditedMs = 0L,
                seenElapsedMs = core.clock.elapsedMs(),
                vkId = wrapped.first,
                salt = salt,
                nonce = nonce,
                ct = wrapped.second,
            )
            if (!lock.master.update(bestEffort = false) { it.copy(pendingMp = pending) }) return RequestOutcome.NotSaved
            dropProof()
            removePendingNotices() // those of an earlier request, which this one replaced
            core.publishSummary()
            return RequestOutcome.Requested(EntryPolicy.PENDING_WAIT_MS)
        } finally {
            Bytes.wipe(bytes, kek)
        }
    }

    /**
     * Holds the mutex. F11 step 8, "Cancel it", in the open app: `pending_mp = null`; no proof, because
     * it is the safe direction. Also the answer CANCEL_IT of its notices. Best effort, as every
     * record the lock means to have: it is gone here at once, and a write that failed is made good by
     * the next one of `state.rec`.
     */
    suspend fun cancelPending() {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null) return
        val lock = core.knownLock(open.vaultId) ?: return
        if (lock.state?.pendingMp == null) return
        lock.master.update { it.copy(pendingMp = null) }
        removePendingNotices()
        core.publishSummary()
    }

    /** Holds the mutex: the notices of a pending new master password that are still shown go; it is no longer pending. */
    private fun removePendingNotices() {
        val now = core.state.value as? LockState.Unlocked ?: return
        val kept = now.notices.filterNot { it is Notice.Pending || it is Notice.PendingReady }
        if (kept.size != now.notices.size) core.state.value = now.copy(notices = kept)
    }

    // ---------------------------------------------------------------------------------------------
    // F11, finishing (steps 13 to 19)

    /** What trying the pending wrap with typed bytes gave. */
    private sealed interface PendingKey {
        /** The wrap opened: VK of the key [vkId] (the receiver wipes it), asked for with [by]. */
        class Opened(val vkId: String, val vk: ByteArray, val by: Shortcut) : PendingKey

        /** Its tag failed: not the password that was chosen. */
        data object Closed : PendingKey

        /** Argon2id could not run. */
        data object NoMemory : PendingKey
    }

    /** What steps 15 to 17 came to. */
    private sealed interface Replacement {
        /** Vault N is current, with its `state.rec`; the old vault is put aside and marked. */
        class Finished(val write: VaultWrite.Done) : Replacement

        /** "Couldn't finish now. Nothing was changed. Try again later." */
        data object NotFinished : Replacement

        /** Argon2id could not run; nothing was changed. */
        data object NoMemory : Replacement

        /** Vault N is in place and perhaps not durable; the vault port holds no session. The mark stays: start-up settles it (§5.11 step 3). */
        data object Unclear : Replacement
    }

    /** F11 step 13: one more Argon2id over the pending salt, then the wrap. */
    private suspend fun openPending(lock: VaultLock, pending: StateRecord.PendingMp, passwordBytes: ByteArray): PendingKey {
        val kek = try {
            core.kdf.kek(passwordBytes, pending.salt)
        } catch (e: KdfUnavailable) {
            return PendingKey.NoMemory
        }
        try {
            val vk = PendingWrap.open(core.aead, kek, pending.nonce, pending.ct, lock.vaultId, pending.vkId) ?: return PendingKey.Closed
            if (vk.size != VK_SIZE) {
                Bytes.wipe(vk)
                return PendingKey.Closed
            }
            return PendingKey.Opened(pending.vkId, vk, pending.by)
        } finally {
            Bytes.wipe(kek)
        }
    }

    /**
     * Holds the mutex. F11 steps 15 to 17, for the vault of [lock], which is open in the vault port,
     * with [passwordBytes] the new master password, which has just opened the pending wrap.
     *
     * - **Step 15, mark first.** A new `vault_id` N, and this vault's `state.rec` with `replaced`;
     *   `pending_mp` stays. The write must reach the disk, or the flow stops; and it is [SETTLED_MS]
     *   old before storage is called (see the class).
     * - **Step 16.** `VaultPort.replaceVault`: vault N from the open entries, written beside the
     *   current one, read back and opened with the typed bytes; only then is the old vault put aside,
     *   whole, and N made current. It fails before that or inside it: `replaced = null`, and the
     *   pending password stays ready.
     * - **Step 17.** `lock/N/` with its `state.rec`: practice step 0, `last_ok` now, counters zero.
     *   Best effort: without it the next start finds a vault without lock records (F14).
     *
     * Nothing in the old vault's lock directory is removed here (step 18): its two slots, their keys
     * and its `pending_mp` go at a later start that finds vault N on disk (§5.11 step 3). From the
     * moment N is current the old vault is one put aside and marked `replaced`, which `EntryPolicy`
     * offers nothing but its master password.
     */
    private suspend fun replace(lock: VaultLock, by: Shortcut, passwordBytes: ByteArray): Replacement {
        val n = core.random.uuid()
        val mark = StateRecord.Replaced(n, core.clock.wallMs(), by, creditedMs = 0L, seenElapsedMs = core.clock.elapsedMs())
        if (!lock.master.update(bestEffort = false) { it.copy(replaced = mark) }) return Replacement.NotFinished
        core.clock.delayUntilElapsed(core.clock.elapsedMs() + SETTLED_MS)
        val write = try {
            core.vault.replaceVault(n, passwordBytes)
        } catch (e: KdfUnavailable) {
            lock.master.update { it.copy(replaced = null) }
            return Replacement.NoMemory
        }
        return when (write) {
            VaultWrite.NothingChanged, VaultWrite.Quarantined -> {
                lock.master.update { it.copy(replaced = null) }
                Replacement.NotFinished
            }
            VaultWrite.Unclear -> Replacement.Unclear
            is VaultWrite.Done -> {
                val nowWall = core.clock.wallMs()
                core.lockOf(write.vaultId).master.establish(
                    core.unlock.newStateRecord(write.vaultId, PracticeSchedule.newMasterPassword(nowWall), write.generation, nowWall),
                )
                Replacement.Finished(write)
            }
        }
    }

    /**
     * Holds the mutex. Steps 17 to 19 for the app: the session continues on vault N. Every proof
     * made before goes; vault N gets its ways in as a restored vault does (F1 step 5: "Choose a PIN"
     * when this phone can hold a key), with the proof of the new master password when [withProof].
     */
    private fun continueOn(write: VaultWrite.Done, passwordBytes: ByteArray, withProof: Boolean): MasterProof? {
        core.dropProofs()
        dropProof()
        core.rescan()
        core.retarget()
        core.open = OpenVault(write.vaultId, write.generation, write.vkId, write.createdMs, olderCopy = false)
        val notices = if (core.canHoldKeys() == KeyCreate.Ok) {
            listOf(Notice.ChoosePin(core.nextNoticeId(), ChoosePinReason.NONE_HERE, needsMasterPassword = !withProof))
        } else {
            emptyList()
        }
        val made = if (withProof) core.newProof(passwordBytes, write.vaultId) else null
        core.state.value = LockState.Unlocked(notices, made)
        core.publishSummary()
        return made
    }

    /**
     * Takes the mutex itself. "Finish now" (F11 steps 13 to 19) in the open app: [chars] is typed
     * under the guard of §6.8, in a prompt.
     *
     * - Nothing is pending: [MasterPasswordOutcome.TooLate]. The 24 hours are not credited yet:
     *   [MasterPasswordOutcome.NotFinished], and nothing is tried (step 12, T16).
     * - [chars] open the pending wrap, and the wrap gives the open vault's key (step 14): the mark,
     *   the new vault and its lock directory (steps 15 to 17, [replace]); the session continues on
     *   vault N, and [MasterPasswordOutcome.Right] carries the proof of the new master password. The
     *   try is given back: it is step 14, not §6.8 step 5.
     * - [chars] are the vault's own master password: it is right (§6.8 step 5), which cancels the
     *   request, as a correct current master password typed anywhere does (F11 step 9).
     * - Neither: wrong, and counted.
     *
     * A lock or `ON_STOP` before step 15 leaves everything as it was ([MasterPasswordOutcome.TooLate]).
     * Steps 15 to 17 run to the end once they have started, also when the app is locked or left
     * meanwhile; then no proof is handed out ([MasterPasswordOutcome.TooLate]), and a lock that
     * waits for the mutex locks the new vault.
     */
    suspend fun finishPending(chars: CharArray): MasterPasswordOutcome {
        val at = core.openPrompt()
        try {
            return core.locked { finishLocked(chars, at) }
        } finally {
            core.closePrompt(at)
        }
    }

    private suspend fun finishLocked(chars: CharArray, at: Place): MasterPasswordOutcome {
        val open = core.open
        if (core.state.value !is LockState.Unlocked || open == null || at.hasEnded) return MasterPasswordOutcome.TooLate
        val lock = core.lockOf(open.vaultId)
        val pending = lock.state?.pendingMp ?: return MasterPasswordOutcome.TooLate
        if (!core.pendingReady(lock)) return MasterPasswordOutcome.NotFinished
        val bytes = MasterPasswordBytes.of(chars)
        try {
            var replaced: Replacement? = null
            var noMemory = false
            var endedFirst = false
            val attempt = lock.master.attempt(at) {
                when (val key = openPending(lock, pending, bytes)) {
                    PendingKey.NoMemory -> {
                        noMemory = true
                        MpCheck.NOT_COUNTED
                    }
                    is PendingKey.Opened -> {
                        try {
                            when {
                                at.hasEnded -> endedFirst = true // I11: nothing has started
                                !yieldsOpenKey(mapOf(key.vkId to key.vk), key.vkId) -> replaced = Replacement.NotFinished
                                else -> replaced = replace(lock, key.by, bytes)
                            }
                        } finally {
                            Bytes.wipe(key.vk)
                        }
                        MpCheck.NOT_COUNTED
                    }
                    PendingKey.Closed -> when (core.vault.checkPassword(bytes)) {
                        true -> MpCheck.RIGHT
                        false -> MpCheck.WRONG
                        null -> {
                            noMemory = true
                            MpCheck.NOT_COUNTED
                        }
                    }
                }
            }
            val checked = when (attempt) {
                is MpAttempt.Wait -> return MasterPasswordOutcome.Wait(attempt.untilElapsedMs)
                is MpAttempt.Checked -> attempt
            }
            return when (val done = replaced) {
                is Replacement.Finished -> {
                    val made = continueOn(done.write, bytes, withProof = !at.hasEnded)
                    if (made != null) MasterPasswordOutcome.Right(made) else MasterPasswordOutcome.TooLate
                }
                Replacement.NotFinished -> MasterPasswordOutcome.NotFinished
                Replacement.NoMemory -> MasterPasswordOutcome.NoMemory
                Replacement.Unclear -> {
                    core.unlock.lockFromInside() // the vault port holds no session: the lock screen of what is there now
                    MasterPasswordOutcome.TooLate
                }
                null -> when (checked.check) {
                    MpCheck.RIGHT -> { // the current master password, typed here: step 9
                        lock.pin.masterPasswordCorrect()
                        if (checked.pendingCancelled) {
                            removePendingNotices()
                            core.addNotice { Notice.PendingCancelled(it) }
                        }
                        core.publishSummary()
                        if (checked.tooLate) MasterPasswordOutcome.TooLate else MasterPasswordOutcome.Right(core.newProof(bytes, lock.vaultId))
                    }
                    MpCheck.WRONG -> MasterPasswordOutcome.Wrong
                    MpCheck.NOT_COUNTED -> when {
                        noMemory -> MasterPasswordOutcome.NoMemory
                        endedFirst -> MasterPasswordOutcome.TooLate
                        else -> MasterPasswordOutcome.NotFinished
                    }
                }
            }
        } finally {
            Bytes.wipe(bytes)
        }
    }

    /**
     * Holds the mutex, inside `MasterPasswordGuard.attempt` on the lock screen. F11 step 13: no
     * password slot of [lock]'s vault opened with [passwordBytes], and its pending password is
     * ready (`LockCore.pendingReady`). One more Argon2id over the pending salt; if the wrap opens,
     * storage opens the vault with the VK it gives (step 14) and steps 15 to 19 follow, for the visit
     * [at]. No PIN and no fingerprint is asked for, on purpose: an owner who lost both during the
     * wait must still get in.
     *
     * - The wrap opens and the replacement is finished: the app is open on vault N, with the proof
     *   of the new master password ([MasterPasswordOutcome.Right]).
     * - The visit ended before step 15 (the screen went off while Argon2id ran): the key is dropped
     *   and nothing has changed ([MasterPasswordOutcome.TooLate], I11); the new master password
     *   finishes at the next try. It ended during steps 15 to 17, which run to their end: the
     *   replacement stands, nothing opens, and the next visit shows the lock screen of vault N.
     * - The mark or the new vault could not be written: the vault is locked again as it was
     *   ([MasterPasswordOutcome.NotFinished]); not enough memory: [MasterPasswordOutcome.NoMemory].
     * - No generation that the key opens can be used: as after any right entry (`cannotOpen`, F16a).
     *
     * @return what the entry came to when the pending wrap opened or Argon2id could not run (the
     *   guard then gives the try back); null when the wrap did not open, and the password is wrong.
     */
    suspend fun typedPending(lock: VaultLock, passwordBytes: ByteArray, at: Place): MasterPasswordOutcome? {
        val visit = core.visit?.takeIf { it.place === at && it.target.lock === lock && it.target.aside == null } ?: return null
        val pending = lock.state?.pendingMp ?: return null
        if (!core.pendingReady(lock)) return null // "The new password is not tried before its time."
        val key = when (val tried = openPending(lock, pending, passwordBytes)) {
            PendingKey.Closed -> return null
            PendingKey.NoMemory -> return stay(visit, MpError.NoMemory, MasterPasswordOutcome.NoMemory)
            is PendingKey.Opened -> tried
        }
        try {
            // Step 14: "If the app was locked, storage opens the vault with it."
            val opened = when (val result = core.vault.open(VaultRef.Current, mapOf(key.vkId to key.vk))) {
                is VaultOpen.Opened -> result
                is VaultOpen.NeedsOtherKey -> { // the newest usable copy has another key (F16a)
                    visit.needsOtherKeyCreatedMs = result.createdMs
                    return stay(visit, null, MasterPasswordOutcome.TooLate)
                }
                VaultOpen.Damaged -> return cannotOpen(visit, CurrentVault.Why.DAMAGED)
                VaultOpen.Newer -> return cannotOpen(visit, CurrentVault.Why.NEWER)
                VaultOpen.Unreadable -> return cannotOpen(visit, CurrentVault.Why.IO)
            }
            if (at.hasEnded || opened.vaultId != lock.vaultId) { // I11: the key is dropped, and the app stays locked
                core.vault.lock()
                return stay(visit, null, MasterPasswordOutcome.TooLate)
            }
            return when (val done = replace(lock, key.by, passwordBytes)) {
                is Replacement.Finished -> {
                    if (at.hasEnded) {
                        // The visit ended while steps 15 to 17 ran: they stand, and nothing opens (I11).
                        visit.opening = false
                        core.dropProofs()
                        dropProof()
                        try {
                            core.vault.lock()
                        } finally {
                            core.unlock.relock()
                        }
                        MasterPasswordOutcome.TooLate
                    } else {
                        core.endVisitNow()
                        core.closeVisit()
                        MasterPasswordOutcome.Right(checkNotNull(continueOn(done.write, passwordBytes, withProof = true)))
                    }
                }
                Replacement.NotFinished -> {
                    core.vault.lock()
                    stay(visit, null, MasterPasswordOutcome.NotFinished)
                }
                Replacement.NoMemory -> {
                    core.vault.lock()
                    stay(visit, MpError.NoMemory, MasterPasswordOutcome.NoMemory)
                }
                Replacement.Unclear -> {
                    visit.opening = false
                    try {
                        core.vault.lock()
                    } finally {
                        core.unlock.relock() // nothing is open: the lock screen of what is there now
                    }
                    MasterPasswordOutcome.TooLate
                }
            }
        } finally {
            Bytes.wipe(key.vk)
        }
    }

    /** Holds the mutex: the lock screen of [visit] again, with [error] as the line after this try; nothing was opened. */
    private fun stay(visit: LockVisit, error: MpError?, outcome: MasterPasswordOutcome): MasterPasswordOutcome {
        visit.opening = false
        visit.mpError = error
        if (!visit.place.hasEnded && core.visit === visit) core.showLockScreen()
        return outcome
    }

    private fun cannotOpen(visit: LockVisit, why: CurrentVault.Why): MasterPasswordOutcome {
        visit.opening = false
        core.unlock.cannotOpen(visit, why, flushPin = false)
        return MasterPasswordOutcome.TooLate
    }

    // ---------------------------------------------------------------------------------------------

    /** Holds the mutex: the `vk_id` the open vault is under now, as the vault port says; null when none is open. */
    private suspend fun openKeyId(): String? =
        try {
            core.vault.withVaultKey { vkId, _ -> vkId }
        } catch (e: RuntimeException) {
            null
        }

    /** Holds the mutex: whether [keys] hold, under [vkId], the key the open vault is under now. */
    private suspend fun yieldsOpenKey(keys: Map<String, ByteArray>, vkId: String): Boolean =
        try {
            core.vault.withVaultKey { openVkId, vk -> openVkId == vkId && keys[vkId]?.let { Bytes.equal(it, vk) } == true }
        } catch (e: RuntimeException) {
            false
        }

    private companion object {
        /** The length of VK, and of a KEK (lock.md §4.2). */
        const val VK_SIZE = 32

        /**
         * How old the mark of F8 step 5 and the mark of F11 step 15 must be before the storage step
         * that depends on them: the charge age of lock.md §6.2, this design's time for a handed-over
         * write to reach the flash (§2.3, §6.7).
         */
        const val SETTLED_MS = PinGuard.CHARGE_AGE_MS
    }
}
