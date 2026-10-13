package io.github.dilshodbekmx.passwordvault.core.lock.policy

import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.AsideVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.CurrentVault
import io.github.dilshodbekmx.passwordvault.core.lock.flow.FingerprintLine
import io.github.dilshodbekmx.passwordvault.core.lock.flow.GenerationInfo
import io.github.dilshodbekmx.passwordvault.core.lock.flow.LockScreen
import io.github.dilshodbekmx.passwordvault.core.lock.flow.MpReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PadStatus
import io.github.dilshodbekmx.passwordvault.core.lock.flow.PinReason
import io.github.dilshodbekmx.passwordvault.core.lock.flow.Practice
import io.github.dilshodbekmx.passwordvault.core.lock.flow.VaultScan
import io.github.dilshodbekmx.passwordvault.core.lock.guard.Credit
import io.github.dilshodbekmx.passwordvault.core.lock.guard.PracticeSchedule
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordCodec
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord

/**
 * Which screen appears while the app is locked (lock.md §13.1), with the reasons and status lines of
 * §13.3: a pure function of the vault's facts, its three records, the clocks, `canAuthenticate` and
 * what has happened on this visit. The lock engine runs it whenever the app is locked and becomes
 * visible, and again whenever one of its inputs changes during the visit (a record is written, the
 * answer of `canAuthenticate` arrives, a shortcut fails); the screen it gets back is a fresh one
 * (no digits typed, nothing opening, no wait and no error), which the engine fills in as the visit
 * goes on.
 *
 * **The rows of §13.1, the first that matches wins:**
 * 1. no vault: Welcome, listing the vaults put aside ([Decision.NoVault]);
 * 2. no generation can be read: "Your passwords can't be opened" ([Decision.Unreadable]);
 * 3. practice is past its grace, a shortcut is usable and no new master password is pending: the
 *    master password, required, with "I can't remember it";
 * 4. practice is due within its grace and not snoozed, a shortcut is usable and no new master
 *    password is pending: the master password with "Not now: use PIN" (or "…use fingerprint");
 * 5. the PIN is usable: the pad, with the fingerprint key when fingerprint is usable;
 * 6. only fingerprint is usable: the fingerprint-only screen;
 * 7. otherwise the master password, with the first reason that applies.
 *
 * Between rows 4 and 5 comes the user's own choice ("Forgot PIN? Use master password", "Use master
 * password"): the master-password screen without a reason, the highest row of §13.3's reason table.
 * A vault put aside that is being opened from the sheet (F17 step 4) has no rows 1 to 4: its own
 * records decide rows 5 to 7, under its line, and one marked `replaced` offers no PIN, no fingerprint
 * and no pending password.
 *
 * **The PIN is usable** when `pin.rec` reads OK and has a slot with a wrap for the `vk_id` of the
 * newest readable generation, `row` < 5, `total` < 10, the start-up step of §6.3 S succeeded, and the
 * Keystore did not fail to check it on this visit (F15c). **Fingerprint is usable** when
 * `fingerprint.rec` reads OK and has a slot with a wrap for that `vk_id`, `canAuthenticate` gave 0
 * or -1, and it was not hidden on this visit; until `canAuthenticate` has answered it is not usable
 * yet, so the pad comes without the fingerprint key. A fingerprint slot that cannot be used now is
 * left alone; its line says why.
 *
 * **Practice** (§5.3, §8.3): a `state.rec` that is MISSING or DAMAGED means practice due now and
 * still skippable; NEWER or UNREADABLE means practice is not asked. It is never asked while a new
 * master password is pending, without a usable shortcut, on a vault put aside, or on a visit on
 * which it was skipped ([Visit.practiceSkipped]: "I can't remember it", F10 step 4, and "Not now"
 * where no record can hold the snooze, F10 step 3).
 *
 * **A damaged `fingerprint.rec`** (§5.3) is not offered, as after a fingerprint key that is gone
 * (F15a), and the screen says that fingerprint unlock stopped working on this phone
 * ([DAMAGED_FINGERPRINT_LINE]); a master-password unlock then offers to turn fingerprint unlock on
 * again (§13.4 item 5). **A damaged `pin.rec`** is a PIN that stopped working
 * ([PinState.STOPPED_WORKING]), as after a PIN key that is gone (F15b); the master-password unlock
 * that then offers "Choose a new PIN" replaces it with a record without a slot (build-plan.md §12,
 * decisions 1 and 16), which this function reads the same way.
 *
 * The screens hold facts, never sentences (lock.md §11); the lock screens pick the texts of §13.3
 * from them.
 */
object EntryPolicy {
    /** The 24 credited hours before a new master password starts working (lock.md F11, §6.9). */
    const val PENDING_WAIT_MS: Long = 24 * 3_600_000L

    /** At most this many tries are left when a fresh pad warns "2 tries left before the PIN turns off" (§6.4, §13.3). */
    const val WARN_AT_TRIES_LEFT = 2

    /**
     * The line of lock.md §13.3 while `fingerprint.rec` reads DAMAGED (§5.3; build-plan.md §12,
     * decision 1): "Fingerprint unlock stopped working on this phone. Use your PIN. You can turn it on
     * again in Settings." Not the line of a key that is gone (F15a), which blames a change of the
     * phone's fingerprints that nobody saw.
     */
    val DAMAGED_FINGERPRINT_LINE: FingerprintLine = FingerprintLine.StoppedWorking

    /** Which vault the lock screen is for. */
    sealed interface Target {
        /**
         * The current vault, as [scan] gives it before anything is typed (`VaultPort.vaults()`).
         * [olderCopyFirst]: the newest readable generation is an older copy, because a newer one on
         * disk is damaged (architecture.md §4.8, storage's `VaultFacts.olderCopyFirst`); a shortcut
         * without a wrap for its key then gives the reason of F16a instead of "You changed your
         * master password".
         */
        data class Current(val scan: VaultScan, val olderCopyFirst: Boolean = false) : Target

        /** A vault put aside, being opened from the "Forgot master password?" sheet (lock.md F17 step 4). */
        data class Aside(val vault: AsideVault) : Target
    }

    /** The last thing that happened to the PIN on this visit, which the status line reports (lock.md §13.3). */
    enum class PinEvent {
        /** A wrong PIN was counted (§6.3 E.5): "Wrong PIN. N tries left.", with N from the record as it is now. */
        WRONG,

        /** The same wrong PIN again (§6.6): "You already tried that PIN. It was not counted again." */
        SAME_AGAIN,

        /** The charge could not be written (§6.3 C.3): "Your phone's storage is full, so the PIN can't be checked now. …" */
        CHARGE_FAILED,

        /** The Keystore said TEMPORARY (§6.3 E.3): the PIN is not offered again on this visit (F15c). */
        CANNOT_CHECK,
    }

    /**
     * What has happened on this visit (lock.md §6.3, §13.2, §13.3). A visit starts when a lock screen
     * becomes visible in a locked app and ends when the app is unlocked or stops being visible; a new
     * visit starts with the defaults.
     *
     * @property masterPasswordChosen the user chose the master-password screen ("Forgot PIN? Use
     *   master password", "Use master password"); "Use PIN" clears it.
     * @property pinEvent the last thing that happened to the PIN on this visit. A line about a wrong
     *   PIN stays until the visit ends, typing included (§6.4).
     * @property interruptedAtStart the start-up step of §6.3 S counted an interrupted entry, and no
     *   PIN has been typed since: "Your last PIN entry was interrupted and counted as a try. …"
     * @property fingerprintHidden the fingerprint key is hidden until the next visit (§13.3, "Any other
     *   fingerprint error").
     * @property fingerprintLine the line of the last fingerprint result on this visit
     *   ([KeystoreErrors.respond], [KeystoreErrors.temporaryLine]; [FingerprintLine.TurnedOff] after a
     *   GONE key, F15a).
     * @property needsOtherKeyCreatedMs a PIN or fingerprint opened no usable copy on this visit:
     *   `VaultOpen.NeedsOtherKey(_, createdMs)` (lock.md F16a). Neither shortcut is offered again on
     *   this visit, and the master-password screen names the copy that opens.
     * @property practiceSkipped practice is not asked on this visit, so rows 3 and 4 of §13.1 are
     *   passed over: "I can't remember it" was chosen, and F11 step 3 wants the PIN or the fingerprint
     *   now (F10 step 4); or "Not now" was chosen where `state.rec` cannot hold the snooze (F10 step
     *   3). Nothing else of the decision changes: what the records say about practice is still
     *   overdue, and the standing banner of §13.4 item 6 says so in the open app.
     */
    data class Visit(
        val masterPasswordChosen: Boolean = false,
        val pinEvent: PinEvent? = null,
        val interruptedAtStart: Boolean = false,
        val fingerprintHidden: Boolean = false,
        val fingerprintLine: FingerprintLine? = null,
        val needsOtherKeyCreatedMs: Long? = null,
        val practiceSkipped: Boolean = false,
    )

    /**
     * Everything [decide] looks at.
     *
     * @property pin `pin.rec` of the target vault as the lock controller means to have it on disk
     *   (lock.md §6.3: after a write that failed, the record in memory).
     * @property fingerprint `fingerprint.rec`, the same way.
     * @property state `state.rec`, the same way.
     * @property nowWallMs `Clock.wallMs()`: practice only (I8).
     * @property nowElapsedMs `Clock.elapsedMs()`: the credit of a pending master password (§6.9).
     * @property canAuthenticate `canAuthenticate(BIOMETRIC_STRONG)`, asked once per visit off the main
     *   thread (§13.1); null until it has answered.
     * @property pinStartUpFailed the start-up write of §6.3 S failed in this process: no PIN until the
     *   next start, with the "storage is full" reason.
     * @property noSecureHardware this phone cannot keep a key in secure hardware (F18) and its text has
     *   not been shown yet: "First time only".
     */
    data class Input(
        val target: Target,
        val pin: RecordRead<PinRecord>,
        val fingerprint: RecordRead<FingerprintRecord>,
        val state: RecordRead<StateRecord>,
        val nowWallMs: Long,
        val nowElapsedMs: Long,
        val canAuthenticate: Int?,
        val pinStartUpFailed: Boolean = false,
        val noSecureHardware: Boolean = false,
        val visit: Visit = Visit(),
    )

    /** The rows of lock.md §13.1, and the user's own choice of the master password. */
    enum class Row {
        /** Row 1: no vault. */
        NO_VAULT,

        /** Row 2: no generation can be read. */
        UNREADABLE,

        /** Row 3: practice past its grace; the master password is required. */
        PRACTICE_REQUIRED,

        /** Row 4: practice due within its grace, with "Not now". */
        PRACTICE_IN_GRACE,

        /** The user chose the master password on this visit (§13.3, the first reason: none). */
        MASTER_PASSWORD_CHOSEN,

        /** Row 5: the PIN pad. */
        PIN_PAD,

        /** Row 6: only fingerprint. */
        FINGERPRINT_ONLY,

        /** Row 7: the master password, with the first reason that applies. */
        MASTER_PASSWORD,
    }

    /** The PIN of the target vault as §13.1 judges it, and why it cannot be used. */
    enum class PinState(
        /** The reason of §13.3 (on the fingerprint-only screen and the master-password screen), or null. */
        val reason: PinReason?,
    ) {
        /** Usable: the pad (row 5). */
        USABLE(null),

        /** No PIN on this phone: never set, skipped at setup, or removed when a replacement was undone (F16b). */
        NOT_SET(null),

        /** Turned off at a limit, or the counts reached one (§6.5). */
        LIMIT(PinReason.LIMIT),

        /** The key is gone (F15b), the wrap did not match its key, or the record is DAMAGED. */
        STOPPED_WORKING(PinReason.STOPPED_WORKING),

        /** The record is NEWER: set up by a newer version of the app. */
        NEWER_APP(PinReason.NEWER_APP),

        /** The record is UNREADABLE, or the Keystore did not answer on this visit (F15c). */
        CANNOT_CHECK(PinReason.CANNOT_CHECK),

        /** The start-up write of §6.3 S failed. */
        STORAGE_FULL(PinReason.STORAGE_FULL),

        /** A master-password change did not keep the PIN: its slot has no wrap for the current key, or was removed for that (F8). */
        MASTER_CHANGED(null),

        /** The newest usable copy is an older one under another key (F16a). */
        OLDER_COPY(null),

        /** This vault offers no PIN now: it was put aside and is marked `replaced`, or its newest copy cannot be read now. */
        NOT_OFFERED(null),
    }

    /** What [decide] chose. */
    sealed interface Decision {
        val row: Row

        /** Row 1: Welcome (F1), with "Open my previous passwords" for each vault in [aside] (F17). */
        data class NoVault(val aside: List<AsideVault>) : Decision {
            override val row: Row get() = Row.NO_VAULT
        }

        /** Row 2: "Your passwords can't be opened" (ux.md §5.9): never the lock, never an offer to delete. */
        data class Unreadable(val why: CurrentVault.Why, val aside: List<AsideVault>) : Decision {
            override val row: Row get() = Row.UNREADABLE
        }

        /**
         * A lock screen, fresh: no digit typed, nothing opening, no wait and no error.
         *
         * @property pin the PIN as §13.1 judged it; a charge may start only when it is [PinState.USABLE].
         * @property fingerprintUsable the fingerprint may be probed and its prompt shown (F3).
         * @property promptOpensItself the prompt opens by itself once on this visit (§9.2 rule 7): the
         *   pad or the fingerprint-only screen with fingerprint usable; never on a practice screen.
         * @property fingerprintWaitsForAnswer fingerprint would be usable but `canAuthenticate` has not
         *   answered yet: decide again when it has.
         */
        data class Locked(
            val screen: LockScreen,
            override val row: Row,
            val pin: PinState,
            val fingerprintUsable: Boolean,
            val promptOpensItself: Boolean,
            val fingerprintWaitsForAnswer: Boolean,
        ) : Decision {
            val pinUsable: Boolean get() = pin == PinState.USABLE
        }
    }

    /** lock.md §13.1 over [input]. */
    fun decide(input: Input): Decision {
        val visit = input.visit
        val newest: GenerationInfo?
        val olderCopyFirst: Boolean
        val asideVault: AsideVault?
        when (val target = input.target) {
            is Target.Current -> when (val current = target.scan.current) {
                CurrentVault.NoVault -> return Decision.NoVault(target.scan.aside)
                is CurrentVault.Unreadable -> return Decision.Unreadable(current.why, target.scan.aside)
                is CurrentVault.Present -> {
                    newest = current.newest
                    olderCopyFirst = target.olderCopyFirst
                    asideVault = null
                }
            }
            is Target.Aside -> {
                newest = target.vault.newest
                olderCopyFirst = false
                asideVault = target.vault
            }
        }
        val current = asideVault == null
        val state = (input.state as? RecordRead.Ok)?.record
        val replacedAtMs = if (current) null else state?.replaced?.atMs
        val aside = asideVault?.let { if (replacedAtMs != null) AsideLine.FromBefore(replacedAtMs) else AsideLine.LockedOn(it.putAsideAtMs) }

        val pending = if (replacedAtMs != null) null else state?.pendingMp
        val pendingLeftMs = pending?.let {
            Credit(it.creditedMs, it.seenElapsedMs).observe(input.nowElapsedMs).remainingMs(PENDING_WAIT_MS)
        }
        val pendingNotReady = pendingLeftMs != null && pendingLeftMs > 0

        val pin = pinState(input, newest, replacedAtMs != null, olderCopyFirst)
        val fingerprint = fingerprint(input, newest, replacedAtMs != null)
        val pinUsable = pin == PinState.USABLE

        fun locked(row: Row, screen: LockScreen) = Decision.Locked(
            screen = screen,
            row = row,
            pin = pin,
            fingerprintUsable = fingerprint.usable,
            promptOpensItself = fingerprint.usable && (screen is LockScreen.Pad || screen is LockScreen.FingerprintOnly),
            fingerprintWaitsForAnswer = fingerprint.waitsForAnswer,
        )

        fun masterPassword(reason: MpReason?, canUsePin: Boolean, practice: Practice?) = LockScreen.MasterPassword(
            reason = reason,
            canUsePin = canUsePin,
            practice = practice,
            waitUntilElapsedMs = null,
            error = null,
            pendingReadyInMs = if (pendingNotReady) pendingLeftMs else null,
            opening = false,
            aside = aside,
            fingerprintLine = fingerprint.line,
        )

        // Rows 3 and 4: practice, on the current vault, while a shortcut would open it, unless it was
        // skipped for this visit.
        if (current && !visit.practiceSkipped && (pinUsable || fingerprint.usable)) {
            when (practice(input.state, input.nowWallMs)) {
                PracticeSchedule.Status.REQUIRED ->
                    return locked(Row.PRACTICE_REQUIRED, masterPassword(MpReason.PracticeDue, canUsePin = false, Practice(notNow = null)))
                PracticeSchedule.Status.DUE -> {
                    val notNow = if (pinUsable) Shortcut.PIN else Shortcut.FINGERPRINT
                    return locked(Row.PRACTICE_IN_GRACE, masterPassword(MpReason.PracticeDue, canUsePin = pinUsable, Practice(notNow)))
                }
                PracticeSchedule.Status.NOT_DUE, PracticeSchedule.Status.SNOOZED, PracticeSchedule.Status.NOT_ASKED -> Unit
            }
        }

        // The user's choice: the master password without a reason.
        if (visit.masterPasswordChosen) {
            return locked(Row.MASTER_PASSWORD_CHOSEN, masterPassword(reason = null, canUsePin = pinUsable, practice = null))
        }

        // Row 5: the pad.
        if (pinUsable) {
            val record = (input.pin as RecordRead.Ok).record
            val status = padStatus(record, pendingNotReady, visit, fingerprint.line)
            val pad = LockScreen.Pad(digits = 0, status = status, fingerprintKey = fingerprint.usable, opening = false, aside = aside)
            return locked(Row.PIN_PAD, pad)
        }

        // Row 6: only fingerprint.
        if (fingerprint.usable) {
            return locked(Row.FINGERPRINT_ONLY, LockScreen.FingerprintOnly(status = fingerprint.line, pinReason = pin.reason, aside = aside))
        }

        // Row 7: the master password, with the first reason of §13.3 that applies.
        val olderCopyMs = visit.needsOtherKeyCreatedMs
            ?: if (olderCopyFirst && newest != null && (pin == PinState.OLDER_COPY || fingerprint.otherKey)) newest.createdMs else null
        val pinReason = pin.reason
        val reason = when {
            current && input.pin == RecordRead.Missing && input.fingerprint == RecordRead.Missing && input.state == RecordRead.Missing ->
                MpReason.WelcomeBack
            replacedAtMs != null -> MpReason.FromBefore(replacedAtMs)
            pinReason != null -> MpReason.Pin(pinReason)
            pin == PinState.MASTER_CHANGED -> MpReason.MasterChanged
            olderCopyMs != null -> MpReason.OlderCopy(olderCopyMs)
            input.noSecureHardware -> MpReason.NoSecureHardware
            else -> null
        }
        return locked(Row.MASTER_PASSWORD, masterPassword(reason, canUsePin = false, practice = null))
    }

    // ------------------------------------------------------------------ the parts

    private fun pinState(input: Input, newest: GenerationInfo?, replaced: Boolean, olderCopyFirst: Boolean): PinState {
        if (replaced) return PinState.NOT_OFFERED
        return when (val read = input.pin) {
            RecordRead.Missing -> PinState.NOT_SET
            is RecordRead.Damaged -> PinState.STOPPED_WORKING
            RecordRead.Newer -> PinState.NEWER_APP
            RecordRead.Unreadable -> PinState.CANNOT_CHECK
            is RecordRead.Ok -> {
                val record = read.record
                val slot = record.slot
                if (slot == null) {
                    when (record.off?.reason) {
                        PinRecord.OffReason.LIMIT -> PinState.LIMIT
                        PinRecord.OffReason.KEY_GONE, PinRecord.OffReason.BAD_WRAP -> PinState.STOPPED_WORKING
                        PinRecord.OffReason.MASTER_CHANGED -> PinState.MASTER_CHANGED
                        PinRecord.OffReason.REPLACED, null -> PinState.NOT_SET
                    }
                } else {
                    when {
                        record.guard.row >= ROW_LIMIT || record.guard.total >= TOTAL_LIMIT -> PinState.LIMIT
                        input.visit.pinEvent == PinEvent.CANNOT_CHECK -> PinState.CANNOT_CHECK
                        input.pinStartUpFailed -> PinState.STORAGE_FULL
                        newest == null -> PinState.NOT_OFFERED
                        slot.wraps.none { it.vkId == newest.vkId } -> if (olderCopyFirst) PinState.OLDER_COPY else PinState.MASTER_CHANGED
                        input.visit.needsOtherKeyCreatedMs != null -> PinState.OLDER_COPY
                        else -> PinState.USABLE
                    }
                }
            }
        }
    }

    /**
     * The fingerprint of the target vault. [on]: a slot with a wrap for the newest key ("fingerprint
     * unlock is on" in §13.3); [otherKey]: a slot without one; [line]: the line of §13.3 it shows.
     */
    private class Fingerprint(
        val usable: Boolean,
        val on: Boolean,
        val otherKey: Boolean,
        val line: FingerprintLine?,
        val waitsForAnswer: Boolean,
    )

    private fun fingerprint(input: Input, newest: GenerationInfo?, replaced: Boolean): Fingerprint {
        if (replaced) return Fingerprint(usable = false, on = false, otherKey = false, line = null, waitsForAnswer = false)
        val visit = input.visit
        val slot = (input.fingerprint as? RecordRead.Ok)?.record?.slot
        val matches = slot != null && newest != null && slot.wraps.any { it.vkId == newest.vkId }
        val otherKey = slot != null && newest != null && !matches
        val on = matches && visit.needsOtherKeyCreatedMs == null
        val canAuthenticate = input.canAuthenticate
        val usable = on && !visit.fingerprintHidden && canAuthenticate != null && KeystoreErrors.mayProbe(canAuthenticate)
        val unavailable = when {
            // §5.3: a damaged record is not offered, and the screen says that fingerprint unlock stopped working.
            input.fingerprint is RecordRead.Damaged -> DAMAGED_FINGERPRINT_LINE
            !on || usable -> null
            visit.fingerprintHidden -> FingerprintLine.CannotCheck
            canAuthenticate == null -> null
            else -> KeystoreErrors.unavailableLine(canAuthenticate)
        }
        return Fingerprint(
            usable = usable,
            on = on,
            otherKey = otherKey,
            line = visit.fingerprintLine ?: unavailable,
            waitsForAnswer = on && !visit.fingerprintHidden && canAuthenticate == null,
        )
    }

    /** Practice for the current vault (§5.3, §8.3). */
    private fun practice(state: RecordRead<StateRecord>, nowWallMs: Long): PracticeSchedule.Status = when (state) {
        is RecordRead.Ok -> {
            val p = state.record.practice
            PracticeSchedule(p.lastOkMs, p.step, p.snoozedUntilMs).status(nowWallMs, newPasswordPending = state.record.pendingMp != null)
        }
        RecordRead.Missing, is RecordRead.Damaged -> PracticeSchedule.STATUS_WITHOUT_RECORD
        RecordRead.Newer, RecordRead.Unreadable -> PracticeSchedule.Status.NOT_ASKED
    }

    /**
     * The status line of a fresh pad (§13.3, "On the pad"): this visit's PIN line first, because it
     * stays until the visit ends; then an entry counted at start; then the warning when 2 or fewer
     * tries are left; then the fingerprint's line.
     */
    private fun padStatus(record: PinRecord, pendingNotReady: Boolean, visit: Visit, fingerprintLine: FingerprintLine?): PadStatus? {
        val left = minOf(ROW_LIMIT - record.guard.row, TOTAL_LIMIT - record.guard.total)
        return when (visit.pinEvent) {
            PinEvent.WRONG -> when {
                left >= 2 -> PadStatus.WrongPin(left)
                pendingNotReady -> PadStatus.LastTryWhilePending
                else -> PadStatus.LastTry
            }
            PinEvent.SAME_AGAIN -> PadStatus.SameAgain
            PinEvent.CHARGE_FAILED -> PadStatus.StorageFull
            PinEvent.CANNOT_CHECK, null -> when {
                visit.interruptedAtStart -> PadStatus.Interrupted(left)
                left <= WARN_AT_TRIES_LEFT -> PadStatus.TriesLeft(left)
                else -> fingerprintLine
            }
        }
    }

    /** The limits of lock.md §6.2, as the record holds them: 5 wrong PINs in a row, 10 not forgiven. */
    private const val ROW_LIMIT = RecordCodec.ROW_LIMIT
    private const val TOTAL_LIMIT = RecordCodec.TOTAL_LIMIT
}
