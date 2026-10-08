package io.github.dilshodbekmx.passwordvault.core.lock.flow

/**
 * The flows of the two shortcuts and of practice (lock.md §12): **F6** set or change the PIN, **F9**
 * turn fingerprint unlock on or off, **F10** practice. Build plan 3.13 fills the bodies; until then
 * every function throws `NotImplementedError`, and `LockEngine` already sends its entry points here.
 *
 * Each function says whether it **holds the mutex** (it is called inside `LockCore.locked`, by
 * `LockEngine` or by another flow) or **takes the mutex itself** (it must, when a prompt is open in
 * between: no mutex is held while a fingerprint prompt is open, architecture.md §5.2).
 *
 * **What the core already does, so that it is not done twice.**
 * - F15 on the lock screen is `UnlockFlows`: a PIN key that is GONE or does not answer (`PinGuard`,
 *   §6.3 E.3), a fingerprint key that is GONE at the probe or after the prompt (`fingerprintGone`:
 *   the slot removed with `off = key_gone` or `bad_wrap`, the alias deleted, the line of §13.3), and
 *   TEMPORARY (the key hidden for the visit, nothing stored). After the unlock `completeUnlock`
 *   offers "Choose a new PIN" and "Turn fingerprint unlock on again?" (§13.4 item 5). What is left
 *   of F15 for this file is the same reaction inside F6 and F9 (a key that cannot be made: NOT NOW).
 * - The practice screen itself is `EntryPolicy` rows 3 and 4; a correct master password there is
 *   `UnlockFlows.masterPasswordOnLockScreen`, and `MasterPasswordGuard` moves the schedule (§8.3).
 *
 * **What the core gives.**
 * - The open vault: `core.open` (its `vault_id` and `vk_id`), `core.lockOf(vaultId)` with
 *   `pin` (`PinGuard.setPin`, the guard of §6.4 for a new PIN), `fingerprint`
 *   (`FingerprintKeeper.setSlot`, `removeSlot`) and `master` (`MasterPasswordGuard.update` for the
 *   practice dates); `core.vault.withVaultKey` lends VK to wrap it.
 * - Proof: `core.holds(proof)` is true only for a proof this engine made and has not dropped; its
 *   `vaultId` must be the open vault's (§8.2: adding a way in needs the master password typed in
 *   the same flow).
 * - Keys: `core.canHoldKeys()` (the canary, F6 step 4 and F9 step 4), `core.pinKey`,
 *   `core.fingerprintKey`, `core.deviceKeys.delete` for a new alias that no record came to name
 *   (F6 step 8, F9 step 9). `PinWrap` has the bytes of §4.3; `FingerprintKeeper.alias` the alias of
 *   a fingerprint slot. A key that a new slot *replaces* is never deleted here: the sweep does that
 *   at a later start (§5.7).
 * - A PIN typed in the open app: `core.unlock.pinInPrompt` is the counted check of §6.3 in a prompt.
 * - A prompt of the open app: `core.openPrompt()` and `core.closePrompt(place)`; a lock or
 *   `ON_STOP` ends it (`place.hasEnded`), and the flow must then stop without changing anything.
 * - A fingerprint launch: `core.newLaunch()` gives its number and marks it outstanding;
 *   `core.launchEnded(number)` says whether its result still counts (§9.2 rule 8).
 * - Afterwards: `core.publishSummary()`, and `core.showLockScreen()` when the lock screen changed.
 */
internal class ShortcutFlows(private val core: LockCore) {

    /**
     * Holds the mutex. F6 "Set" (and F1 step 6, F5 step 4, F7 step 3): [pin] is six ASCII digits that
     * passed the blocklist, [proof] the master password of this flow. Steps 4 to 8: the canary, a
     * new key `pv_pin_<new id>`, `KEK_pin`, a wrap of the open vault's VK with a fresh nonce, the
     * test of that wrap, then `PinGuard.setPin(slot, masterPasswordTyped = true)`. On any failure
     * the new alias is deleted, the old record stays, and the answer is false.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun setPin(pin: ByteArray, proof: MasterProof): Boolean = notYet("F6: set a PIN")

    /**
     * Takes the mutex itself. F6 "Change": [current] is a counted check in a prompt
     * (`core.unlock.pinInPrompt`) that must yield the open vault's key; then [new], which must
     * differ, replaces the slot as in [setPin], with `masterPasswordTyped = false` (§6.4: `total`
     * unchanged). A wrong PIN is counted, never forgiven and reported at the next unlock; a limit
     * turns the PIN off and the app stays open; a PIN that cannot be checked is refunded.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun changePin(current: ByteArray, new: ByteArray): PinChangeOutcome = notYet("F6: change the PIN")

    /**
     * Takes the mutex itself. F9 "On", steps 2 to 9: [proof] as in [setPin]; the canary; a new key
     * `pv_fp_<new id>` (`NoneEnrolled`: "Add a fingerprint in your phone's settings first.");
     * `FingerprintKey.seal` of the open vault's VK behind the prompt `PromptUse.TURN_ON`, under a
     * launch number, with no mutex held while it is open; then `FingerprintKeeper.setSlot` with the
     * one wrap. On any error or cancel the new alias is deleted and the earlier state kept.
     *
     * `VaultPort.withVaultKey` lends VK to a lambda that cannot suspend, and the prompt suspends:
     * take a copy of VK and its `vk_id` out of it, seal the copy, and wipe it in a `finally`. A
     * result whose launch is no longer outstanding (`core.launchEnded(number)` false: a lock or
     * `ON_STOP` came while the prompt was open) changes nothing, and its new alias is deleted.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fingerprintOn(proof: MasterProof): FingerprintOnOutcome = notYet("F9: turn fingerprint unlock on")

    /** Holds the mutex. F9 "Off": `FingerprintKeeper.removeSlot(TURNED_OFF)`, which deletes the alias at once. No proof. */
    suspend fun fingerprintOff(): Unit = notYet("F9: turn fingerprint unlock off")

    /**
     * Holds the mutex. F10 step 3, "Not now: use PIN" or "Not now: use fingerprint" on a practice
     * screen within its grace (the screen shown is a `LockScreen.MasterPassword` whose
     * `practice.notNow` is not null; `UnlockFlows.usePin` has checked that, `LockEngine.notNow` has
     * not): `snoozed_until` = now + 20 hours in `state.rec` (`PracticeSchedule.notNow`, best
     * effort), then the pad, and the fingerprint prompt as on any other visit. Without a record
     * that can hold the snooze (`state.rec` MISSING or DAMAGED), `LockVisit.practiceSkipped` skips
     * practice for this visit.
     */
    suspend fun practiceNotNow(): Unit = notYet("F10: Not now")

    private fun notYet(what: String): Nothing = throw NotImplementedError("$what comes with build plan 3.13 (ShortcutFlows)")
}
