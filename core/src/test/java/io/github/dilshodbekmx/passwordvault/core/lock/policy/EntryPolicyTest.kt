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
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.Decision
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.Input
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.PinEvent
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.PinState
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.Row
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.Target
import io.github.dilshodbekmx.passwordvault.core.lock.policy.EntryPolicy.Visit
import io.github.dilshodbekmx.passwordvault.core.lock.record.FingerprintRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.KeyWrap
import io.github.dilshodbekmx.passwordvault.core.lock.record.PinRecord
import io.github.dilshodbekmx.passwordvault.core.lock.record.RecordRead
import io.github.dilshodbekmx.passwordvault.core.lock.record.Shortcut
import io.github.dilshodbekmx.passwordvault.core.lock.record.ShortcutSlot
import io.github.dilshodbekmx.passwordvault.core.lock.record.StateRecord
import io.github.dilshodbekmx.passwordvault.core.util.ParseError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * lock.md test T12, `EntryPolicy`: the table of §13.1 for every combination of the five read
 * results per record, slot and wrap matches, counts, practice states, a pending password, every
 * `canAuthenticate` value, a PIN hidden after a TEMPORARY result, and a vault marked `replaced`;
 * every row of the three reason tables of §13.3 is reached by some input.
 *
 * The combinations are checked against [table], a transcription of §13.1 and §13.3 written for this
 * test from the text of lock.md, sentence by sentence; the scenario tests below it pin the
 * expectations by hand.
 */
class EntryPolicyTest {

    // ================================================================== every combination

    @Test
    fun t12_everyCombinationFollowsTheTableOf13_1() {
        var count = 0
        for (target in TARGETS) {
            for (pin in pinReads()) {
                for (fingerprint in fingerprintReads()) {
                    for (state in stateReads()) {
                        for (canAuthenticate in CAN_AUTHENTICATE) {
                            for (hidden in listOf(false, true)) {
                                val input = Input(
                                    target = target,
                                    pin = pin,
                                    fingerprint = fingerprint,
                                    state = state,
                                    nowWallMs = NOW,
                                    nowElapsedMs = ELAPSED,
                                    canAuthenticate = canAuthenticate,
                                    visit = if (hidden) Visit(pinEvent = PinEvent.CANNOT_CHECK) else Visit(),
                                )
                                verify(input)
                                count++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("$count combinations", count > 300_000)
    }

    @Test
    fun t12_everyCombinationOfTheVisitAndTheProcessFollowsTheTable() {
        // The visit's events and the process's flags, over a smaller set of records.
        for (target in TARGETS) {
            for (pin in pinReads().filterIndexed { i, _ -> i % 2 == 0 } + pinRead(row = 4) + pinRead(total = 9)) {
                for (fingerprint in fingerprintReads().take(7)) {
                    for (state in listOf(RecordRead.Missing, stateRead(), stateRead(practice = DUE), stateRead(pending = NOT_READY), stateRead(replaced = true))) {
                        for (canAuthenticate in listOf(null, 0, 11)) {
                            for (visit in VISITS) {
                                for (startUpFailed in listOf(false, true)) {
                                    for (noSecureHardware in listOf(false, true)) {
                                        verify(
                                            Input(target, pin, fingerprint, state, NOW, ELAPSED, canAuthenticate, startUpFailed, noSecureHardware, visit),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun t12_rows1And2ComeBeforeAnyRecord() {
        for (pin in pinReads().take(6)) {
            for (state in stateReads().take(6)) {
                val noVault = input(target = Target.Current(VaultScan(CurrentVault.NoVault, listOf(ASIDE))), pin = pin, state = state)
                assertEquals(Decision.NoVault(listOf(ASIDE)), EntryPolicy.decide(noVault))
                verify(noVault)
                for (why in CurrentVault.Why.entries) {
                    val unreadable = input(target = Target.Current(VaultScan(CurrentVault.Unreadable(why), listOf(ASIDE))), pin = pin, state = state)
                    assertEquals(Decision.Unreadable(why, listOf(ASIDE)), EntryPolicy.decide(unreadable))
                    verify(unreadable)
                }
            }
        }
    }

    /** [EntryPolicy.decide] agrees with [table] on [input]; what it produced is kept for the coverage test. */
    private fun verify(input: Input) {
        val decision = EntryPolicy.decide(input)
        val expected = table(input)
        if (decision != expected) {
            throw AssertionError("for $input\n  decide: $decision\n  table:  $expected")
        }
        record(decision)
    }

    // ================================================================== §13.1 and §13.3, transcribed

    /**
     * lock.md §13.1 and §13.3, from their text. Each condition quotes the sentence it comes from;
     * where the text leaves an order open, the order of the reason table of §13.3 decides.
     */
    private fun table(input: Input): Decision {
        val visit = input.visit
        // "1 | The vault directory does not exist or is empty | Welcome"; "2 | No generation can be read".
        val target = input.target
        val newest: GenerationInfo?
        val olderCopyFirst: Boolean
        val asideVault: AsideVault?
        if (target is Target.Current) {
            val current = target.scan.current
            if (current == CurrentVault.NoVault) return Decision.NoVault(target.scan.aside)
            if (current is CurrentVault.Unreadable) return Decision.Unreadable(current.why, target.scan.aside)
            newest = (current as CurrentVault.Present).newest
            olderCopyFirst = target.olderCopyFirst
            asideVault = null
        } else {
            asideVault = (target as Target.Aside).vault
            newest = asideVault.newest
            olderCopyFirst = false
        }
        val stateRecord = (input.state as? RecordRead.Ok)?.record
        val pinRecord = (input.pin as? RecordRead.Ok)?.record
        val fpRecord = (input.fingerprint as? RecordRead.Ok)?.record

        // "A vault that was put aside and is marked replaced (5.6) has neither a PIN nor a fingerprint: master password only."
        val markedReplaced = asideVault != null && stateRecord?.replaced != null
        // F16a, found on this visit: no shortcut opens the newest usable copy.
        val otherKeyThisVisit = visit.needsOtherKeyCreatedMs != null

        fun wrapForNewest(slot: ShortcutSlot?) = slot != null && newest != null && slot.wraps.any { it.vkId == newest.vkId }

        // "The PIN is usable when pin.rec reads OK, has a slot with a wrap for the vk_id of the newest readable
        // generation, row < 5, total < 10, the start-up step of 6.3 succeeded, and the Keystore has not failed to
        // check it on this visit (F15c)."
        val pinUsable = !markedReplaced && !otherKeyThisVisit && pinRecord != null && wrapForNewest(pinRecord.slot) &&
            pinRecord.guard.row < 5 && pinRecord.guard.total < 10 && !input.pinStartUpFailed &&
            visit.pinEvent != PinEvent.CANNOT_CHECK
        // "Fingerprint is usable when fingerprint.rec reads OK, has a slot with a wrap for that vk_id,
        // canAuthenticate(BIOMETRIC_STRONG) is 0 or −1, and it was not hidden on this visit."
        val fingerprintOn = !markedReplaced && !otherKeyThisVisit && fpRecord != null && wrapForNewest(fpRecord.slot)
        val fingerprintUsable = fingerprintOn && (input.canAuthenticate == 0 || input.canAuthenticate == -1) && !visit.fingerprintHidden

        // The fingerprint's line (§13.3): what happened on this visit, else why fingerprint unlock, which is on, cannot be used now.
        val fingerprintLine: FingerprintLine? = when {
            markedReplaced -> null
            visit.fingerprintLine != null -> visit.fingerprintLine
            !fingerprintOn || fingerprintUsable -> null
            visit.fingerprintHidden -> FingerprintLine.CannotCheck
            input.canAuthenticate == null -> null
            input.canAuthenticate == 1 -> FingerprintLine.NotForApps
            input.canAuthenticate == 11 -> FingerprintLine.NoneSaved
            input.canAuthenticate == 15 -> FingerprintLine.UpdateNeeded
            else -> FingerprintLine.CannotCheck
        }

        // Why the PIN cannot be used, in the order of the reason table of §13.3.
        val pinSlot = pinRecord?.slot
        val pinReason: PinReason? = when {
            markedReplaced -> null
            // "PIN turned off at a limit"
            pinRecord != null && pinSlot == null && pinRecord.off?.reason == PinRecord.OffReason.LIMIT -> PinReason.LIMIT
            pinRecord != null && pinSlot != null && (pinRecord.guard.row >= 5 || pinRecord.guard.total >= 10) -> PinReason.LIMIT
            // "PIN key gone, or its record is damaged"
            input.pin is RecordRead.Damaged -> PinReason.STOPPED_WORKING
            pinRecord != null && pinSlot == null &&
                (pinRecord.off?.reason == PinRecord.OffReason.KEY_GONE || pinRecord.off?.reason == PinRecord.OffReason.BAD_WRAP) -> PinReason.STOPPED_WORKING
            // "The PIN record was written by a newer version of the app"
            input.pin == RecordRead.Newer -> PinReason.NEWER_APP
            // "PIN cannot be checked (the Keystore is not answering, or its record cannot be read right now)"
            input.pin == RecordRead.Unreadable -> PinReason.CANNOT_CHECK
            pinSlot != null && visit.pinEvent == PinEvent.CANNOT_CHECK -> PinReason.CANNOT_CHECK
            // "The start-up write of 6.3 S failed"
            pinSlot != null && input.pinStartUpFailed -> PinReason.STORAGE_FULL
            else -> null
        }
        // "The master password was changed and the PIN was not kept"; "An older copy with another key (F16a)".
        val pinWithoutNewestKey = pinReason == null && !markedReplaced && pinSlot != null && newest != null && !wrapForNewest(pinSlot)
        val fpWithoutNewestKey = !markedReplaced && fpRecord?.slot != null && newest != null && !wrapForNewest(fpRecord.slot)
        val masterChanged = !markedReplaced &&
            ((pinWithoutNewestKey && !olderCopyFirst) || (pinRecord != null && pinSlot == null && pinRecord.off?.reason == PinRecord.OffReason.MASTER_CHANGED))
        val olderCopyMs: Long? = visit.needsOtherKeyCreatedMs
            ?: if (olderCopyFirst && newest != null && (pinWithoutNewestKey || fpWithoutNewestKey)) newest.createdMs else null

        val pinState = when {
            pinUsable -> PinState.USABLE
            pinReason == PinReason.LIMIT -> PinState.LIMIT
            pinReason == PinReason.STOPPED_WORKING -> PinState.STOPPED_WORKING
            pinReason == PinReason.NEWER_APP -> PinState.NEWER_APP
            pinReason == PinReason.CANNOT_CHECK -> PinState.CANNOT_CHECK
            pinReason == PinReason.STORAGE_FULL -> PinState.STORAGE_FULL
            markedReplaced -> PinState.NOT_OFFERED
            pinRecord != null && pinSlot == null && pinRecord.off?.reason == PinRecord.OffReason.MASTER_CHANGED -> PinState.MASTER_CHANGED
            pinSlot != null && newest == null -> PinState.NOT_OFFERED
            pinWithoutNewestKey -> if (olderCopyFirst) PinState.OLDER_COPY else PinState.MASTER_CHANGED
            pinSlot != null && otherKeyThisVisit -> PinState.OLDER_COPY
            else -> PinState.NOT_SET
        }

        // A new master password pending (F11): not for a vault marked replaced.
        val pending = if (markedReplaced) null else stateRecord?.pendingMp
        val pendingLeft = pending?.let { Credit(it.creditedMs, it.seenElapsedMs).observe(input.nowElapsedMs).remainingMs(24 * HOUR) }
        val pendingNotReady = pendingLeft != null && pendingLeft > 0

        val aside: AsideLine? = asideVault?.let {
            val replaced = stateRecord?.replaced
            if (replaced != null) AsideLine.FromBefore(replaced.atMs) else AsideLine.LockedOn(it.putAsideAtMs)
        }

        fun locked(row: Row, screen: LockScreen) = Decision.Locked(
            screen = screen,
            row = row,
            pin = pinState,
            fingerprintUsable = fingerprintUsable,
            promptOpensItself = fingerprintUsable && (row == Row.PIN_PAD || row == Row.FINGERPRINT_ONLY),
            fingerprintWaitsForAnswer = fingerprintOn && !visit.fingerprintHidden && input.canAuthenticate == null,
        )

        fun masterPassword(row: Row, reason: MpReason?, canUsePin: Boolean, practice: Practice?) = locked(
            row,
            LockScreen.MasterPassword(
                reason = reason,
                canUsePin = canUsePin,
                practice = practice,
                waitUntilElapsedMs = null,
                error = null,
                pendingReadyInMs = if (pendingNotReady) pendingLeft else null,
                opening = false,
                aside = aside,
                fingerprintLine = fingerprintLine,
            ),
        )

        // Rows 3 and 4: "For a vault that was put aside ... without rows 1 to 4."
        val shortcutUsable = pinUsable || fingerprintUsable
        if (asideVault == null && shortcutUsable) {
            // §5.3: MISSING and DAMAGED mean "practice due now and still skippable"; NEWER and UNREADABLE "Practice is not asked".
            // §8.3: "Not asked while a replacement is pending (F11)".
            val status = when (val state = input.state) {
                is RecordRead.Ok -> PracticeSchedule(state.record.practice.lastOkMs, state.record.practice.step, state.record.practice.snoozedUntilMs)
                    .status(input.nowWallMs, state.record.pendingMp != null)
                RecordRead.Missing, is RecordRead.Damaged -> PracticeSchedule.Status.DUE
                else -> PracticeSchedule.Status.NOT_ASKED
            }
            // "3 | Practice is past its grace period, a shortcut is usable, and no new master password is pending"
            if (status == PracticeSchedule.Status.REQUIRED) {
                return masterPassword(Row.PRACTICE_REQUIRED, MpReason.PracticeDue, canUsePin = false, Practice(null))
            }
            // "4 | Practice is due, within grace, not snoozed, a shortcut is usable, and no new master password is pending"
            if (status == PracticeSchedule.Status.DUE) {
                return masterPassword(
                    Row.PRACTICE_IN_GRACE,
                    MpReason.PracticeDue,
                    canUsePin = pinUsable,
                    Practice(if (pinUsable) Shortcut.PIN else Shortcut.FINGERPRINT),
                )
            }
        }

        // "The user chose it ('Forgot PIN?', 'Use master password') | none"
        if (visit.masterPasswordChosen) return masterPassword(Row.MASTER_PASSWORD_CHOSEN, null, canUsePin = pinUsable, practice = null)

        // "5 | The PIN is usable | PIN pad. With the fingerprint key ... if fingerprint is usable"
        if (pinUsable) {
            val left = minOf(5 - pinRecord!!.guard.row, 10 - pinRecord.guard.total)
            // A line about a wrong PIN or about tries left stays until the visit ends.
            val status: PadStatus? = when {
                visit.pinEvent == PinEvent.WRONG && left >= 2 -> PadStatus.WrongPin(left)
                visit.pinEvent == PinEvent.WRONG && pendingNotReady -> PadStatus.LastTryWhilePending
                visit.pinEvent == PinEvent.WRONG -> PadStatus.LastTry
                visit.pinEvent == PinEvent.SAME_AGAIN -> PadStatus.SameAgain
                visit.pinEvent == PinEvent.CHARGE_FAILED -> PadStatus.StorageFull
                visit.interruptedAtStart -> PadStatus.Interrupted(left)
                left <= 2 -> PadStatus.TriesLeft(left)
                else -> fingerprintLine
            }
            return locked(Row.PIN_PAD, LockScreen.Pad(0, status, fingerprintUsable, false, aside))
        }
        // "6 | Only fingerprint is usable | The fingerprint-only screen"
        if (fingerprintUsable) return locked(Row.FINGERPRINT_ONLY, LockScreen.FingerprintOnly(fingerprintLine, pinReason, aside))

        // "7 | Otherwise | Master password, with the first reason that applies (13.3)"
        val noRecords = asideVault == null && input.pin == RecordRead.Missing && input.fingerprint == RecordRead.Missing &&
            input.state == RecordRead.Missing
        val reasons = listOfNotNull(
            if (noRecords) MpReason.WelcomeBack else null,
            stateRecord?.replaced?.takeIf { markedReplaced }?.let { MpReason.FromBefore(it.atMs) },
            pinReason?.let { MpReason.Pin(it) },
            if (masterChanged) MpReason.MasterChanged else null,
            olderCopyMs?.let { MpReason.OlderCopy(it) },
            if (input.noSecureHardware) MpReason.NoSecureHardware else null,
        )
        return masterPassword(Row.MASTER_PASSWORD, reasons.firstOrNull(), canUsePin = false, practice = null)
    }

    // ================================================================== the three tables of §13.3

    @Test
    fun t12_everyRowOfTheThreeReasonTablesOf13_3IsReachedBySomeInput() {
        t12_everyCombinationFollowsTheTableOf13_1()
        t12_everyCombinationOfTheVisitAndTheProcessFollowsTheTable()
        t12_rows1And2ComeBeforeAnyRecord()
        // On the pad: every line the lock screen can show at a decision. (The "TEST BUILD" line is
        // the screen's own, from BuildConfig, and "Wrong PIN" lines with 4 or 3 left need a count.)
        val pad = setOf<PadStatus?>(
            PadStatus.TriesLeft(2), PadStatus.TriesLeft(1), PadStatus.WrongPin(2), PadStatus.LastTry, PadStatus.LastTryWhilePending,
            PadStatus.SameAgain, PadStatus.Interrupted(1), PadStatus.Interrupted(5), PadStatus.StorageFull,
            FingerprintLine.TurnedOff, FingerprintLine.Locked, FingerprintLine.DidNotOpen, FingerprintLine.NotForApps,
            FingerprintLine.NoneSaved, FingerprintLine.UpdateNeeded, FingerprintLine.CannotCheck, null,
        )
        assertEquals(emptySet<PadStatus?>(), pad - padSeen)
        // On the fingerprint-only screen, "Why there is no pad".
        val fingerprintOnly = PinReason.entries.toSet<PinReason?>() + null
        assertEquals(emptySet<PinReason?>(), fingerprintOnly - fingerprintOnlySeen)
        // On the master-password screen, every reason, and none.
        val reasons = setOf(
            MpReason.PracticeDue, MpReason.WelcomeBack, MpReason.FromBefore(REPLACED_AT), MpReason.Pin(PinReason.LIMIT),
            MpReason.Pin(PinReason.STOPPED_WORKING), MpReason.Pin(PinReason.NEWER_APP), MpReason.Pin(PinReason.CANNOT_CHECK),
            MpReason.Pin(PinReason.STORAGE_FULL), MpReason.MasterChanged, MpReason.OlderCopy(NEWEST.createdMs),
            MpReason.OlderCopy(OTHER_KEY_COPY), MpReason.NoSecureHardware, null,
        )
        assertEquals(emptySet<MpReason?>(), reasons - reasonsSeen)
        // and every row of §13.1, with the user's choice
        assertEquals(Row.entries.toSet(), rowsSeen)
    }

    private val padSeen = HashSet<PadStatus?>()
    private val fingerprintOnlySeen = HashSet<PinReason?>()
    private val reasonsSeen = HashSet<MpReason?>()
    private val rowsSeen = HashSet<Row>()

    private fun record(decision: Decision) {
        rowsSeen += decision.row
        val screen = (decision as? Decision.Locked)?.screen ?: return
        when (screen) {
            is LockScreen.Pad -> padSeen += screen.status
            is LockScreen.FingerprintOnly -> fingerprintOnlySeen += screen.pinReason
            is LockScreen.MasterPassword -> reasonsSeen += screen.reason
            is LockScreen.OlderPassword -> throw AssertionError("the policy never chooses F16b's screen")
            is LockScreen.OpenOlderSet -> throw AssertionError("the policy never chooses F16b's question")
        }
    }

    // ================================================================== the rows, by hand

    @Test
    fun t12_row3_practicePastItsGraceRequiresTheMasterPassword() {
        for ((pin, fingerprint, canAuthenticate) in listOf(
            Triple(pinRead(), RecordRead.Missing, null), // the PIN
            Triple(RecordRead.Missing, fingerprintRead(), 0), // only fingerprint
            Triple(pinRead(), fingerprintRead(), -1), // both
        )) {
            val decision = decide(pin = pin, fingerprint = fingerprint, state = stateRead(practice = REQUIRED), canAuthenticate = canAuthenticate)
            assertEquals(Row.PRACTICE_REQUIRED, decision.row)
            assertEquals(
                LockScreen.MasterPassword(MpReason.PracticeDue, false, Practice(null), null, null, null, false, null, null),
                decision.screen,
            )
            assertFalse("the prompt does not open by itself on a practice screen", decision.promptOpensItself)
        }
    }

    @Test
    fun t12_row4_practiceDueWithinGraceOffersNotNow() {
        val withPin = decide(pin = pinRead(), state = stateRead(practice = DUE))
        assertEquals(Row.PRACTICE_IN_GRACE, withPin.row)
        assertEquals(LockScreen.MasterPassword(MpReason.PracticeDue, true, Practice(Shortcut.PIN), null, null, null, false, null, null), withPin.screen)
        val withFingerprint = decide(fingerprint = fingerprintRead(), state = stateRead(practice = DUE), canAuthenticate = 0)
        assertEquals(Practice(Shortcut.FINGERPRINT), (withFingerprint.screen as LockScreen.MasterPassword).practice)
        assertFalse((withFingerprint.screen as LockScreen.MasterPassword).canUsePin)
        assertFalse(withFingerprint.promptOpensItself)
        // Snoozed: the pad as usual. A clock set back by more than an hour: due.
        assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = stateRead(practice = SNOOZED)).row)
        assertEquals(Row.PRACTICE_IN_GRACE, decide(pin = pinRead(), state = stateRead(practice = CLOCK_BEHIND)).row)
        assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = stateRead(practice = NOT_DUE)).row)
    }

    @Test
    fun t12_practiceIsNotAskedWhileANewMasterPasswordIsPendingNorWithoutAShortcut() {
        for (practice in listOf(DUE, REQUIRED)) {
            assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = stateRead(practice = practice, pending = NOT_READY)).row)
            assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = stateRead(practice = practice, pending = READY)).row)
            // no shortcut: every unlock is the master password already, and no practice subtitle
            val none = decide(state = stateRead(practice = practice))
            assertEquals(Row.MASTER_PASSWORD, none.row)
            assertNull((none.screen as LockScreen.MasterPassword).practice)
            // F18: the same
            val f18 = decide(state = stateRead(practice = practice), noSecureHardware = true)
            assertEquals(MpReason.NoSecureHardware, (f18.screen as LockScreen.MasterPassword).reason)
        }
    }

    @Test
    fun t12_aMissingOrDamagedStateRecordMeansPracticeDueAndSkippable_newerOrUnreadableNotAsked() {
        for (state in listOf(RecordRead.Missing, RecordRead.Damaged(ParseError(0, "magic")))) {
            val decision = decide(pin = pinRead(), state = state)
            assertEquals(Row.PRACTICE_IN_GRACE, decision.row)
            assertEquals(Practice(Shortcut.PIN), (decision.screen as LockScreen.MasterPassword).practice)
        }
        for (state in listOf(RecordRead.Newer, RecordRead.Unreadable)) {
            assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = state).row)
        }
    }

    @Test
    fun t12_row5_thePadWithTheFingerprintKeyWhenFingerprintIsUsable() {
        val both = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0)
        assertEquals(Row.PIN_PAD, both.row)
        assertEquals(LockScreen.Pad(0, null, fingerprintKey = true, opening = false, aside = null), both.screen)
        assertTrue(both.pinUsable)
        assertTrue(both.fingerprintUsable)
        assertTrue(both.promptOpensItself)
        val pinOnly = decide(pin = pinRead())
        assertEquals(LockScreen.Pad(0, null, fingerprintKey = false, opening = false, aside = null), pinOnly.screen)
        assertFalse(pinOnly.promptOpensItself)
    }

    @Test
    fun t12_untilCanAuthenticateHasAnsweredThePadComesWithoutTheFingerprintKey() {
        val decision = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = null)
        assertEquals(LockScreen.Pad(0, null, fingerprintKey = false, opening = false, aside = null), decision.screen)
        assertTrue(decision.fingerprintWaitsForAnswer)
        assertFalse(decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0).fingerprintWaitsForAnswer)
        assertFalse(decide(pin = pinRead(), canAuthenticate = null).fingerprintWaitsForAnswer)
    }

    @Test
    fun t12_aFreshPadWarnsWhen2OrFewerTriesAreLeft() {
        assertEquals(PadStatus.TriesLeft(2), padStatus(pinRead(row = 3)))
        assertEquals(PadStatus.TriesLeft(1), padStatus(pinRead(row = 4)))
        assertEquals(PadStatus.TriesLeft(2), padStatus(pinRead(row = 1, total = 8)))
        assertEquals(PadStatus.TriesLeft(1), padStatus(pinRead(total = 9)))
        assertNull(padStatus(pinRead(row = 2, total = 7))) // 3 left
    }

    @Test
    fun t12_theLinesOfThisVisitsPinStayOnThePad() {
        assertEquals(PadStatus.WrongPin(4), padStatus(pinRead(row = 1, total = 1), Visit(pinEvent = PinEvent.WRONG)))
        assertEquals(PadStatus.WrongPin(2), padStatus(pinRead(row = 3, total = 3), Visit(pinEvent = PinEvent.WRONG)))
        assertEquals(PadStatus.LastTry, padStatus(pinRead(row = 4, total = 4), Visit(pinEvent = PinEvent.WRONG)))
        assertEquals(PadStatus.LastTry, padStatus(pinRead(row = 1, total = 9), Visit(pinEvent = PinEvent.WRONG)))
        // "while a new master password is not yet working (F11)"; once it is ready, the plain line
        assertEquals(
            PadStatus.LastTryWhilePending,
            padStatus(pinRead(row = 4, total = 4), Visit(pinEvent = PinEvent.WRONG), stateRead(pending = NOT_READY)),
        )
        assertEquals(PadStatus.LastTry, padStatus(pinRead(row = 4, total = 4), Visit(pinEvent = PinEvent.WRONG), stateRead(pending = READY)))
        assertEquals(PadStatus.SameAgain, padStatus(pinRead(), Visit(pinEvent = PinEvent.SAME_AGAIN)))
        assertEquals(PadStatus.StorageFull, padStatus(pinRead(), Visit(pinEvent = PinEvent.CHARGE_FAILED)))
        assertEquals(PadStatus.Interrupted(4), padStatus(pinRead(row = 1, total = 1), Visit(interruptedAtStart = true)))
        // The PIN's line stays even when the fingerprint has news of its own.
        val visit = Visit(pinEvent = PinEvent.WRONG, fingerprintLine = FingerprintLine.Locked)
        assertEquals(PadStatus.WrongPin(3), padStatus(pinRead(row = 2, total = 2), visit))
        assertEquals(PadStatus.TriesLeft(2), padStatus(pinRead(row = 3), Visit(fingerprintLine = FingerprintLine.Locked)))
    }

    @Test
    fun t12_theFingerprintLinesOnThePad() {
        // canAuthenticate while fingerprint unlock is on (§13.3)
        val byValue = mapOf(1 to FingerprintLine.NotForApps, 11 to FingerprintLine.NoneSaved, 15 to FingerprintLine.UpdateNeeded, 12 to FingerprintLine.CannotCheck, -2 to FingerprintLine.CannotCheck, 7 to FingerprintLine.CannotCheck)
        for ((value, line) in byValue) {
            val decision = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = value)
            assertEquals("canAuthenticate $value", LockScreen.Pad(0, line, false, false, null), decision.screen)
            assertFalse(decision.fingerprintUsable)
        }
        // what happened to the fingerprint on this visit
        for (line in listOf(FingerprintLine.Locked, FingerprintLine.DidNotOpen, FingerprintLine.NotForApps)) {
            val decision = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(fingerprintLine = line))
            assertEquals(LockScreen.Pad(0, line, true, false, null), decision.screen) // the key stays
        }
        val hidden = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(fingerprintHidden = true, fingerprintLine = FingerprintLine.CannotCheck))
        assertEquals(LockScreen.Pad(0, FingerprintLine.CannotCheck, false, false, null), hidden.screen)
        // F15a: the key was invalidated and its slot removed; the pad says so on this visit.
        val gone = decide(pin = pinRead(), fingerprint = fingerprintOff(FingerprintRecord.OffReason.KEY_GONE), canAuthenticate = 0, visit = Visit(fingerprintLine = FingerprintLine.TurnedOff))
        assertEquals(LockScreen.Pad(0, FingerprintLine.TurnedOff, false, false, null), gone.screen)
        // and on the next visit nothing more is said
        assertEquals(LockScreen.Pad(0, null, false, false, null), decide(pin = pinRead(), fingerprint = fingerprintOff(FingerprintRecord.OffReason.KEY_GONE), canAuthenticate = 0).screen)
    }

    @Test
    fun t12_aFingerprintSlotThatCannotBeUsedNowIsLeftAlone() {
        // Not probed (not usable), nothing about it changes: it is the engine's to leave alone; the line says why.
        for (value in listOf(1, 11, 12, -2, 15)) {
            val decision = decide(fingerprint = fingerprintRead(), canAuthenticate = value)
            assertFalse(decision.fingerprintUsable)
            assertEquals(Row.MASTER_PASSWORD, decision.row)
            assertEquals(KeystoreErrors.unavailableLine(value), (decision.screen as LockScreen.MasterPassword).fingerprintLine)
        }
    }

    @Test
    fun t12_row6_onlyFingerprintWithThePinsReason() {
        val reasons = mapOf(
            pinOff(PinRecord.OffReason.LIMIT) to PinReason.LIMIT,
            pinRead(row = 5) to PinReason.LIMIT,
            pinRead(total = 10) to PinReason.LIMIT,
            RecordRead.Unreadable to PinReason.CANNOT_CHECK,
            pinOff(PinRecord.OffReason.KEY_GONE) to PinReason.STOPPED_WORKING,
            pinOff(PinRecord.OffReason.BAD_WRAP) to PinReason.STOPPED_WORKING,
            RecordRead.Damaged(ParseError(0, "hash")) to PinReason.STOPPED_WORKING,
            RecordRead.Newer to PinReason.NEWER_APP,
            RecordRead.Missing to null,
            pinNeverSet() to null,
        )
        for ((pin, reason) in reasons) {
            val decision = decide(pin = pin, fingerprint = fingerprintRead(), canAuthenticate = -1)
            assertEquals("$pin", Row.FINGERPRINT_ONLY, decision.row)
            assertEquals("$pin", LockScreen.FingerprintOnly(null, reason, null), decision.screen)
            assertTrue(decision.promptOpensItself)
        }
        // The PIN the Keystore could not check on this visit, and the start-up write that failed.
        assertEquals(
            LockScreen.FingerprintOnly(null, PinReason.CANNOT_CHECK, null),
            decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(pinEvent = PinEvent.CANNOT_CHECK)).screen,
        )
        assertEquals(
            LockScreen.FingerprintOnly(null, PinReason.STORAGE_FULL, null),
            decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, pinStartUpFailed = true).screen,
        )
    }

    @Test
    fun t12_row7_theFirstReasonThatApplies() {
        val cases = listOf(
            // F14: no lock records at all
            mp(pin = RecordRead.Missing, fingerprint = RecordRead.Missing, state = RecordRead.Missing) to MpReason.WelcomeBack,
            mp(pin = pinOff(PinRecord.OffReason.LIMIT)) to MpReason.Pin(PinReason.LIMIT),
            mp(pin = pinOff(PinRecord.OffReason.KEY_GONE)) to MpReason.Pin(PinReason.STOPPED_WORKING),
            mp(pin = RecordRead.Damaged(ParseError(12, "wraps"))) to MpReason.Pin(PinReason.STOPPED_WORKING),
            mp(pin = RecordRead.Newer) to MpReason.Pin(PinReason.NEWER_APP),
            mp(pin = RecordRead.Unreadable) to MpReason.Pin(PinReason.CANNOT_CHECK),
            mp(pin = pinRead(), visit = Visit(pinEvent = PinEvent.CANNOT_CHECK)) to MpReason.Pin(PinReason.CANNOT_CHECK),
            mp(pin = pinRead(), pinStartUpFailed = true) to MpReason.Pin(PinReason.STORAGE_FULL),
            // F8 without keeping the PIN: a slot whose only wrap is for the old key, and later the slot removed
            mp(pin = pinRead(wraps = listOf(VK_OLD))) to MpReason.MasterChanged,
            mp(pin = pinOff(PinRecord.OffReason.MASTER_CHANGED)) to MpReason.MasterChanged,
            // F16a, seen in the scan, and found by an unlock on this visit
            mp(pin = pinRead(wraps = listOf(VK_OLD)), olderCopyFirst = true) to MpReason.OlderCopy(NEWEST.createdMs),
            mp(fingerprint = fingerprintRead(wraps = listOf(VK_OLD)), olderCopyFirst = true) to MpReason.OlderCopy(NEWEST.createdMs),
            mp(pin = pinRead(), visit = Visit(needsOtherKeyCreatedMs = OTHER_KEY_COPY)) to MpReason.OlderCopy(OTHER_KEY_COPY),
            mp(noSecureHardware = true) to MpReason.NoSecureHardware,
            // none of the above: no PIN is set (it was skipped at setup)
            mp() to null,
            mp(pin = pinNeverSet()) to null,
            mp(pin = pinOff(PinRecord.OffReason.REPLACED)) to null,
        )
        for ((decision, reason) in cases) {
            assertEquals(Row.MASTER_PASSWORD, decision.row)
            val screen = decision.screen as LockScreen.MasterPassword
            assertEquals("$decision", reason, screen.reason)
            assertFalse(screen.canUsePin)
            assertNull(screen.practice)
        }
        // When several apply, the highest: a limit above a changed master password, the PIN above the older copy.
        assertEquals(MpReason.Pin(PinReason.LIMIT), (mp(pin = pinRead(wraps = listOf(VK_OLD), row = 5)).screen as LockScreen.MasterPassword).reason)
        assertEquals(
            MpReason.Pin(PinReason.STOPPED_WORKING),
            (mp(pin = pinOff(PinRecord.OffReason.KEY_GONE), fingerprint = fingerprintRead(wraps = listOf(VK_OLD)), olderCopyFirst = true).screen as LockScreen.MasterPassword).reason,
        )
        assertEquals(MpReason.Pin(PinReason.NEWER_APP), (mp(pin = RecordRead.Newer, noSecureHardware = true).screen as LockScreen.MasterPassword).reason)
        assertEquals(MpReason.WelcomeBack, (mp(pin = RecordRead.Missing, fingerprint = RecordRead.Missing, state = RecordRead.Missing, noSecureHardware = true).screen as LockScreen.MasterPassword).reason)
    }

    @Test
    fun t12_theUsersChoiceIsTheMasterPasswordWithoutAReason() {
        val chosen = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(masterPasswordChosen = true))
        assertEquals(Row.MASTER_PASSWORD_CHOSEN, chosen.row)
        assertEquals(LockScreen.MasterPassword(null, true, null, null, null, null, false, null, null), chosen.screen)
        assertFalse(chosen.promptOpensItself)
        // From the fingerprint-only screen: no "Use PIN".
        val fromFingerprint = decide(fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(masterPasswordChosen = true))
        assertEquals(LockScreen.MasterPassword(null, false, null, null, null, null, false, null, null), fromFingerprint.screen)
        // Rows 1 to 4 still come first.
        assertEquals(Row.PRACTICE_IN_GRACE, decide(pin = pinRead(), state = stateRead(practice = DUE), visit = Visit(masterPasswordChosen = true)).row)
    }

    @Test
    fun t12_aPendingMasterPasswordSaysWhenItStartsWorking() {
        // 20 h credited when it was last seen, an hour ago: 21 h now, so 3 h are left.
        val state = stateRead(pending = StateRecord.PendingMp(NOW - DAY, Shortcut.PIN, 20 * HOUR, ELAPSED - HOUR, VK_NEW, ByteArray(16), ByteArray(12), ByteArray(48)))
        assertEquals(3 * HOUR, (mp(state = state).screen as LockScreen.MasterPassword).pendingReadyInMs)
        // after a reboot only the uptime counts (§6.9): 20 h + 30 min
        val rebooted = decide(state = state, nowElapsedMs = 30 * MINUTE)
        assertEquals(3 * HOUR + 30 * MINUTE, (rebooted.screen as LockScreen.MasterPassword).pendingReadyInMs)
        // ready: no line
        assertNull((mp(state = stateRead(pending = READY)).screen as LockScreen.MasterPassword).pendingReadyInMs)
        assertNull((mp().screen as LockScreen.MasterPassword).pendingReadyInMs)
        // the same on the user's own choice of the master-password screen
        val chosen = decide(pin = pinRead(), state = stateRead(pending = NOT_READY), visit = Visit(masterPasswordChosen = true))
        assertEquals(4 * HOUR, (chosen.screen as LockScreen.MasterPassword).pendingReadyInMs)
    }

    // ================================================================== vaults put aside

    @Test
    fun t12_aVaultPutAsideHasItsOwnScreensUnderItsLineWithoutRows1To4() {
        val target = Target.Aside(ASIDE)
        val pad = decide(target = target, pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, state = stateRead(practice = REQUIRED))
        assertEquals(LockScreen.Pad(0, null, true, false, AsideLine.LockedOn(ASIDE.putAsideAtMs)), pad.screen) // no practice there
        val fingerprintOnly = decide(target = target, fingerprint = fingerprintRead(), canAuthenticate = 0)
        assertEquals(LockScreen.FingerprintOnly(null, null, AsideLine.LockedOn(ASIDE.putAsideAtMs)), fingerprintOnly.screen)
        val master = decide(target = target)
        assertEquals(AsideLine.LockedOn(ASIDE.putAsideAtMs), master.screen.aside)
        // no "Welcome back" for a vault put aside: it is named by its line
        assertNull((decide(target = target, pin = RecordRead.Missing, fingerprint = RecordRead.Missing, state = RecordRead.Missing).screen as LockScreen.MasterPassword).reason)
    }

    @Test
    fun t12_aVaultMarkedReplacedOffersOnlyItsMasterPassword() {
        val target = Target.Aside(ASIDE)
        for (canAuthenticate in CAN_AUTHENTICATE) {
            val decision = decide(
                target = target,
                pin = pinRead(),
                fingerprint = fingerprintRead(),
                state = stateRead(replaced = true, pending = READY),
                canAuthenticate = canAuthenticate,
            )
            assertEquals(Row.MASTER_PASSWORD, decision.row)
            assertEquals(
                LockScreen.MasterPassword(MpReason.FromBefore(REPLACED_AT), false, null, null, null, null, false, AsideLine.FromBefore(REPLACED_AT), null),
                decision.screen,
            )
            assertEquals(PinState.NOT_OFFERED, decision.pin)
            assertFalse(decision.fingerprintUsable)
        }
        // The current vault is never treated so: start-up settles its mark (lock.md §5.11).
        assertEquals(Row.PIN_PAD, decide(pin = pinRead(), state = stateRead(replaced = true)).row)
    }

    @Test
    fun t12_aVaultPutAsideThatCannotBeReadNowOffersNoShortcut() {
        val decision = decide(target = Target.Aside(ASIDE.copy(newest = null)), pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0)
        assertEquals(Row.MASTER_PASSWORD, decision.row)
        assertEquals(PinState.NOT_OFFERED, decision.pin)
        assertNull((decision.screen as LockScreen.MasterPassword).reason)
    }

    // ================================================================== the wraps and the keys

    @Test
    fun t12_aShortcutNeedsAWrapForTheNewestReadableKey() {
        // the newest key and the one before it, while storage still keeps a generation under it (§5.4)
        assertEquals(Row.PIN_PAD, decide(pin = pinRead(wraps = listOf(VK_OLD, VK_NEW))).row)
        assertEquals(Row.PIN_PAD, decide(pin = pinRead(wraps = listOf(VK_NEW, VK_OLD))).row)
        assertEquals(Row.MASTER_PASSWORD, decide(pin = pinRead(wraps = listOf(VK_OLD))).row)
        assertEquals(Row.FINGERPRINT_ONLY, decide(pin = pinRead(wraps = listOf(VK_OLD)), fingerprint = fingerprintRead(wraps = listOf(VK_OLD, VK_NEW)), canAuthenticate = 0).row)
        // F16a found on this visit: neither shortcut is offered again
        val other = decide(pin = pinRead(), fingerprint = fingerprintRead(), canAuthenticate = 0, visit = Visit(needsOtherKeyCreatedMs = OTHER_KEY_COPY))
        assertEquals(Row.MASTER_PASSWORD, other.row)
        assertEquals(PinState.OLDER_COPY, other.pin)
        assertFalse(other.fingerprintUsable)
    }

    // ================================================================== helpers

    private fun decide(
        target: Target = CURRENT,
        pin: RecordRead<PinRecord> = RecordRead.Missing,
        fingerprint: RecordRead<FingerprintRecord> = RecordRead.Missing,
        state: RecordRead<StateRecord> = stateRead(),
        canAuthenticate: Int? = 0,
        pinStartUpFailed: Boolean = false,
        noSecureHardware: Boolean = false,
        visit: Visit = Visit(),
        nowElapsedMs: Long = ELAPSED,
    ): Decision.Locked {
        val input = Input(target, pin, fingerprint, state, NOW, nowElapsedMs, canAuthenticate, pinStartUpFailed, noSecureHardware, visit)
        verify(input) // the hand-made cases agree with the transcription too
        return EntryPolicy.decide(input) as Decision.Locked
    }

    private fun input(
        target: Target = CURRENT,
        pin: RecordRead<PinRecord> = RecordRead.Missing,
        state: RecordRead<StateRecord> = stateRead(),
    ) = Input(target, pin, RecordRead.Missing, state, NOW, ELAPSED, 0)

    private fun mp(
        pin: RecordRead<PinRecord> = RecordRead.Missing,
        fingerprint: RecordRead<FingerprintRecord> = RecordRead.Missing,
        state: RecordRead<StateRecord> = stateRead(),
        pinStartUpFailed: Boolean = false,
        noSecureHardware: Boolean = false,
        visit: Visit = Visit(),
        olderCopyFirst: Boolean = false,
    ) = decide(
        target = Target.Current(SCAN, olderCopyFirst),
        pin = pin,
        fingerprint = fingerprint,
        state = state,
        canAuthenticate = 0,
        pinStartUpFailed = pinStartUpFailed,
        noSecureHardware = noSecureHardware,
        visit = visit,
    )

    private fun padStatus(pin: RecordRead<PinRecord>, visit: Visit = Visit(), state: RecordRead<StateRecord> = stateRead()): PadStatus? {
        val decision = decide(pin = pin, state = state, visit = visit)
        assertEquals(Row.PIN_PAD, decision.row)
        return (decision.screen as LockScreen.Pad).status
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        /** 2026-10-06 09:00 UTC, and three weeks of uptime. */
        const val NOW = 1_791_277_200_000L
        const val ELAPSED = 21 * DAY

        const val VAULT = "0f6b2f0e-5d3b-4c1a-9a7e-2b1c8d4e6f70"
        const val ASIDE_ID = "00000000-0000-4000-8000-000000000001"
        const val NEW_VAULT = "00000000-0000-4000-8000-000000000002"
        const val VK_NEW = "3a1d0c5e-8f2b-4e6a-b1c9-7d4e2f6a8b0c"
        const val VK_OLD = "5e2f7a9b-3c4d-4e5f-a6b7-c8d9e0f1a2b3"
        const val SLOT = "7b0c1f8e-0b9a-4d27-9e3a-55c6f6d1a2b4"
        const val REPLACED_AT = NOW - 2 * DAY
        const val OTHER_KEY_COPY = NOW - 9 * DAY

        val NEWEST = GenerationInfo(57, VK_NEW, NOW - 3 * DAY)
        val ASIDE = AsideVault(ASIDE_ID, putAsideAtMs = NOW - 10 * DAY, newest = GenerationInfo(12, VK_NEW, NOW - 11 * DAY))
        val SCAN = VaultScan(CurrentVault.Present(VAULT, NEWEST, setOf(VK_NEW, VK_OLD)), listOf(ASIDE))
        val CURRENT = Target.Current(SCAN)

        /** The targets of the big runs: the current vault, with and without an older copy first, and two vaults put aside. */
        val TARGETS = listOf(CURRENT, Target.Current(SCAN, olderCopyFirst = true), Target.Aside(ASIDE), Target.Aside(ASIDE.copy(newest = null)))

        /** Every value of §9.4 and §13.3, unanswered, and one nobody knows. */
        val CAN_AUTHENTICATE = listOf(null, 0, -1, 1, 11, 12, -2, 15, 99)

        // Practice (§8.3), against NOW, with step 3: due 14 days after last_ok, grace 7 days.
        val NOT_DUE = StateRecord.Practice(NOW - HOUR, 3, null)
        val DUE = StateRecord.Practice(NOW - 15 * DAY, 3, null)
        val SNOOZED = StateRecord.Practice(NOW - 15 * DAY, 3, NOW + 10 * HOUR)
        val REQUIRED = StateRecord.Practice(NOW - 30 * DAY, 3, null)
        val CLOCK_BEHIND = StateRecord.Practice(NOW + 3 * HOUR, 3, null)

        val NOT_READY = StateRecord.PendingMp(NOW - DAY, Shortcut.PIN, 20 * HOUR, ELAPSED, VK_NEW, ByteArray(16), ByteArray(12), ByteArray(48))
        val READY = StateRecord.PendingMp(NOW - 2 * DAY, Shortcut.FINGERPRINT, 24 * HOUR, ELAPSED, VK_NEW, ByteArray(16), ByteArray(12), ByteArray(48))

        /** The visits of the second big run: every event and line, alone and together. */
        val VISITS = listOf(
            Visit(),
            Visit(masterPasswordChosen = true),
            Visit(pinEvent = PinEvent.WRONG),
            Visit(pinEvent = PinEvent.SAME_AGAIN),
            Visit(pinEvent = PinEvent.CHARGE_FAILED),
            Visit(pinEvent = PinEvent.CANNOT_CHECK),
            Visit(interruptedAtStart = true),
            Visit(fingerprintHidden = true, fingerprintLine = FingerprintLine.CannotCheck),
            Visit(fingerprintLine = FingerprintLine.Locked),
            Visit(fingerprintLine = FingerprintLine.DidNotOpen),
            Visit(fingerprintLine = FingerprintLine.NotForApps),
            Visit(fingerprintLine = FingerprintLine.TurnedOff),
            Visit(needsOtherKeyCreatedMs = OTHER_KEY_COPY),
            Visit(pinEvent = PinEvent.WRONG, interruptedAtStart = true, fingerprintLine = FingerprintLine.Locked),
            Visit(masterPasswordChosen = true, pinEvent = PinEvent.CANNOT_CHECK, fingerprintHidden = true),
        )

        fun pinRead(wraps: List<String> = listOf(VK_NEW), row: Int = 0, total: Int = 0, vaultId: String = VAULT): RecordRead<PinRecord> =
            RecordRead.Ok(
                PinRecord(
                    vaultId,
                    ShortcutSlot(SLOT, NOW - 40 * DAY, wraps.map { KeyWrap(it, ByteArray(12), ByteArray(48)) }),
                    PinRecord.Guard(false, row, total),
                    null,
                    PinRecord.Unseen(0, null, false),
                ),
            )

        fun pinOff(reason: PinRecord.OffReason): RecordRead<PinRecord> =
            RecordRead.Ok(PinRecord(VAULT, null, PinRecord.Guard(false, 5, 7), PinRecord.Off(reason, NOW - DAY), PinRecord.Unseen(5, NOW - DAY, false)))

        fun pinNeverSet(): RecordRead<PinRecord> =
            RecordRead.Ok(PinRecord(VAULT, null, PinRecord.Guard(false, 0, 0), null, PinRecord.Unseen(0, null, false)))

        fun fingerprintRead(wraps: List<String> = listOf(VK_NEW)): RecordRead<FingerprintRecord> =
            RecordRead.Ok(FingerprintRecord(VAULT, ShortcutSlot(SLOT, NOW - 40 * DAY, wraps.map { KeyWrap(it, ByteArray(12), ByteArray(48)) }), null))

        fun fingerprintOff(reason: FingerprintRecord.OffReason): RecordRead<FingerprintRecord> =
            RecordRead.Ok(FingerprintRecord(VAULT, null, FingerprintRecord.Off(reason, NOW - DAY)))

        fun stateRead(
            practice: StateRecord.Practice = NOT_DUE,
            pending: StateRecord.PendingMp? = null,
            replaced: Boolean = false,
        ): RecordRead<StateRecord> = RecordRead.Ok(
            StateRecord(
                VAULT,
                practice,
                StateRecord.MpGuard(0, null, 0),
                null,
                pending,
                if (replaced) StateRecord.Replaced(NEW_VAULT, REPLACED_AT, Shortcut.PIN, 0L, ELAPSED) else null,
                StateRecord.MaxGeneration(57, NOW - 3 * DAY),
            ),
        )

        val DAMAGED: RecordRead.Damaged = RecordRead.Damaged(ParseError(0, "magic"))

        /** The five read results of `pin.rec`, with every form of slot, wrap and count that matters to §13.1. */
        fun pinReads(): List<RecordRead<PinRecord>> = listOf(
            RecordRead.Missing, DAMAGED, RecordRead.Newer, RecordRead.Unreadable,
            pinNeverSet(),
        ) + PinRecord.OffReason.entries.map(::pinOff) + listOf(
            pinRead(), pinRead(row = 2, total = 7), pinRead(row = 3, total = 3), pinRead(row = 4), pinRead(total = 9),
            pinRead(row = 5), pinRead(total = 10), pinRead(row = 4, total = 9),
            pinRead(wraps = listOf(VK_OLD)), pinRead(wraps = listOf(VK_OLD, VK_NEW)), pinRead(wraps = listOf(VK_OLD), row = 5),
        )

        /** The five read results of `fingerprint.rec`, with every form of slot and wrap. */
        fun fingerprintReads(): List<RecordRead<FingerprintRecord>> = listOf(
            RecordRead.Missing, DAMAGED, RecordRead.Newer, RecordRead.Unreadable,
            fingerprintRead(), fingerprintRead(wraps = listOf(VK_OLD)), fingerprintRead(wraps = listOf(VK_NEW, VK_OLD)),
            RecordRead.Ok(FingerprintRecord(VAULT, null, null)),
            fingerprintOff(FingerprintRecord.OffReason.KEY_GONE), fingerprintOff(FingerprintRecord.OffReason.TURNED_OFF),
        )

        /** The five read results of `state.rec`, with every practice state, a pending password not ready and ready, and the mark `replaced`. */
        fun stateReads(): List<RecordRead<StateRecord>> {
            val reads = mutableListOf<RecordRead<StateRecord>>(RecordRead.Missing, DAMAGED, RecordRead.Newer, RecordRead.Unreadable)
            for (practice in listOf(NOT_DUE, DUE, SNOOZED, REQUIRED, CLOCK_BEHIND)) {
                for (pending in listOf(null, NOT_READY, READY)) {
                    for (replaced in listOf(false, true)) reads += stateRead(practice, pending, replaced)
                }
            }
            return reads
        }
    }
}
